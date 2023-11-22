/** @file

  Copyright (c) 2014 - 2016, AMD Inc. All rights reserved.<BR>

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include "FdtDxe.h"

extern  EFI_BOOT_SERVICES       *gBS;

EFI_EVENT mFdtReadyToBootEvent;

VOID
EFIAPI
FdtReadyToBoot (
  IN      EFI_EVENT      Event,
  IN      VOID           *Context
  );

EFI_STATUS
EFIAPI
FdtOverrideDevicePath(
  IN CHAR16 *FdtFileName,
  OUT EFI_DEVICE_PATH **FdtDevicePath
  );


/**
 *---------------------------------------------------------------------------------------
 *
 *  FdtDxeEntryPoint
 *
 *  Description:
 *    Entry point of the FDT Runtime Driver.
 *
 *  Control flow:
 *    Configure reserved regions.
 *
 *  Parameters:
 *    @param[in]      ImageHandle          The firmware allocate handle for the
 *                                         EFI image.
 *    @param[in]      *SystemTable         Pointer to the EFI System Table.
 *
 *    @return         EFI_STATUS
 *
 *------------------------------------------------------------------------------------
 **/
EFI_STATUS
EFIAPI
FdtDxeEntryPoint (
  IN EFI_HANDLE         ImageHandle,
  IN EFI_SYSTEM_TABLE   *SystemTable
  )
{
  EFI_STATUS              Status;

  DEBUG ((EFI_D_ERROR, "FdtDxe Loaded\n"));

  //
  // Ready-To-Boot callback
  //
  Status = EfiCreateEventReadyToBootEx(
                               TPL_CALLBACK,
                               FdtReadyToBoot,
                               NULL,
                               &mFdtReadyToBootEvent
                               );
  ASSERT_EFI_ERROR (Status);

  return Status;
}

/**
 *---------------------------------------------------------------------------------------
 *
 *  FdtReadyToBoot
 *
 *  Description:
 *  Ready-2-Boot Event Callback for EFI_EVENT_SIGNAL_READY_TO_BOOT.
 *
 *  Control flow:
 *    1. Read FDT blob
 *    2. Edit FDT table
 *    3. Submit FDT to EFI system table
 *
 *  Parameters:
 *    @param[in]      Event                EFI_EVENT notification.
 *    @param[in]      *Context             Pointer to the Event Context.
 *
 *    @return         VOID
 *
 *---------------------------------------------------------------------------------------
 **/
VOID
EFIAPI
FdtReadyToBoot (
  IN      EFI_EVENT      Event,
  IN      VOID           *Context
  )
{
  EFI_FIRMWARE_VOLUME2_PROTOCOL *FvProtocol;
  EFI_HANDLE                    *HandleBuffer;
  UINTN                         HandleCount;
  UINTN                         Index;
  EFI_STATUS                    Status;
  UINT32                        AuthenticationStatus;
  EFI_GUID                      *FdtGuid = FixedPcdGetPtr(PcdStyxFdt);
  UINT8                         *FdtBlobBase = NULL;
  UINTN                         FdtBlobSize = 0;
  EFI_DEVICE_PATH               *FdtDevicePath;

  // Search for FDT blob in EFI partition
  Status = FdtOverrideDevicePath(L"fdt.dtb", &FdtDevicePath);
  if (!EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "%a: Loading Override FDT blob...\n", __FUNCTION__));

    FdtBlobBase = (UINT8 *)(UINTN)LINUX_FDT_MAX_OFFSET;
    Status = BdsLoadImage (FdtDevicePath,
                           AllocateMaxAddress,
                           (EFI_PHYSICAL_ADDRESS *)&FdtBlobBase,
                           &FdtBlobSize);
    if (!EFI_ERROR (Status) && FdtBlobBase && FdtBlobSize)
      goto LOAD_FDT_BLOB;
    else
      goto LOAD_FDT_ERROR;
  }

  DEBUG ((EFI_D_ERROR, "%a: Loading Embedded FDT blob...\n", __FUNCTION__));
  HandleBuffer = NULL;
  Status = gBS->LocateHandleBuffer (
                  ByProtocol,
                  &gEfiFirmwareVolume2ProtocolGuid,
                  NULL,
                  &HandleCount,
                  &HandleBuffer
                  );
  ASSERT_EFI_ERROR (Status);

  for (Index = 0; Index < HandleCount; Index++) {
    Status = gBS->HandleProtocol (
                  HandleBuffer[Index],
                  &gEfiFirmwareVolume2ProtocolGuid,
                  (VOID **) &FvProtocol
                  );
    if (!EFI_ERROR (Status)) {
      Status = FvProtocol->ReadSection (
                            FvProtocol,
                            FdtGuid,
                            EFI_SECTION_RAW,
                            0,
                            (VOID **)&FdtBlobBase,
                            &FdtBlobSize,
                            &AuthenticationStatus
                            );
      if (!EFI_ERROR (Status) && FdtBlobBase && FdtBlobSize)
        goto LOAD_FDT_BLOB;
    }
  }

LOAD_FDT_ERROR:
  DEBUG ((EFI_D_ERROR, "%a: Error loading FDT blob!\n", __FUNCTION__));
  goto LOAD_FDT_DONE;

LOAD_FDT_BLOB:
  Status = AmdStyxPrepareFdt(NULL, 0, 0, (EFI_PHYSICAL_ADDRESS *)&FdtBlobBase, &FdtBlobSize);
  ASSERT_EFI_ERROR (Status);

  // Install the FDT blob into EFI system configuration table
  Status = gBS->InstallConfigurationTable (&gFdtTableGuid, (VOID *)FdtBlobBase);
  ASSERT_EFI_ERROR (Status);
  DEBUG ((EFI_D_ERROR, "%a: FDT ready!\n", __FUNCTION__));

LOAD_FDT_DONE:
  gBS->CloseEvent (mFdtReadyToBootEvent);
  return;
}

/**
*---------------------------------------------------------------------------------------
*
*  FdtOverrideDevicePath
*
*  Description:
*    Looks for a user-provided FDT blob to override the default file built with the UEFI image.
*
*  Parameters:
*    @param[in]      FdtFileName       Name of the FDT blob located in the EFI partition.
*    @param[out]     FdtDevicePath     EFI Device Path of the FDT blob.
*
*    @return EFI_SUCCESS           The function completed successfully.
*    @return EFI_NOT_FOUND         The protocol could not be located.
*    @return EFI_OUT_OF_RESOURCES  There are not enough resources to find the protocol.
*
*---------------------------------------------------------------------------------------
**/
EFI_STATUS
EFIAPI
FdtOverrideDevicePath(
  IN CHAR16 *FdtFileName,
  OUT EFI_DEVICE_PATH **FdtDevicePath
  )
{
  EFI_DEVICE_PATH_PROTOCOL        *DevPathProtocol;
  EFI_HANDLE                      *HandleBuffer;
  UINTN                           HandleCount;
  UINTN                           Index;
  EFI_STATUS                      Status;
  CHAR16                          *DevPathText;
  EFI_SIMPLE_FILE_SYSTEM_PROTOCOL *VolProtocol;
  EFI_FILE_PROTOCOL               *FileProtocol;
  EFI_FILE_PROTOCOL               *FileHandle;
  CHAR16                          FilePathText[120];

  HandleBuffer = NULL;
  Status = gBS->LocateHandleBuffer (
                ByProtocol,
                &gEfiSimpleFileSystemProtocolGuid,
                NULL,
                &HandleCount,
                &HandleBuffer);
  if (EFI_ERROR (Status))
    return Status;

  for (Index = 0; Index < HandleCount; Index++) {
    DevPathProtocol = NULL;
    Status = gBS->HandleProtocol (
                  HandleBuffer[Index],
                  &gEfiDevicePathProtocolGuid,
                  (VOID **) &DevPathProtocol);

    if (!EFI_ERROR (Status)) {
      VolProtocol = NULL;
      Status = gBS->HandleProtocol (
                    HandleBuffer[Index],
                    &gEfiSimpleFileSystemProtocolGuid,
                    (VOID **) &VolProtocol);

      if (!EFI_ERROR (Status)) {
        FileProtocol = NULL;
        Status = VolProtocol->OpenVolume(VolProtocol, &FileProtocol);

        if (!EFI_ERROR (Status)) {
          FileHandle = NULL;
          Status = FileProtocol->Open(FileProtocol,
                                      &FileHandle,
                                      FdtFileName,
                                      EFI_FILE_MODE_READ,
                                      0);

          if (!EFI_ERROR (Status)) {
            FileProtocol->Close(FileHandle);
            DevPathText = ConvertDevicePathToText(DevPathProtocol, TRUE, FALSE);
            StrCpy(FilePathText, DevPathText);
            StrCat(FilePathText, L"/");
            StrCat(FilePathText, FdtFileName);
            *FdtDevicePath = ConvertTextToDevicePath (FilePathText);
            return EFI_SUCCESS;
          }
        }
      }
    }
  }

  return Status;
}

