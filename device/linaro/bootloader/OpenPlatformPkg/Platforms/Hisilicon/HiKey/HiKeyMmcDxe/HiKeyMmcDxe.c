/** @file
*
*  Copyright (c) 2017, Linaro. All rights reserved.
*
*  This program and the accompanying materials
*  are licensed and made available under the terms and conditions of the BSD License
*  which accompanies this distribution.  The full text of the license may be found at
*  http://opensource.org/licenses/bsd-license.php
*
*  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
*  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.
*
**/

#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/DebugLib.h>
#include <Library/DevicePathLib.h>
#include <Library/IoLib.h>
#include <Library/TimerLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiLib.h>
#include <Library/UefiRuntimeServicesTableLib.h>
#include <Library/UsbSerialNumberLib.h>

#include <Protocol/EmbeddedGpio.h>
#include <Protocol/PlatformDwMmc.h>

#include <Hi6220.h>

#define DETECT_SD_CARD           8     // GPIO 1_0

DW_MMC_HC_SLOT_CAP
DwMmcCapability[2] = {
  {
    .Ddr50       = 1,
    .HighSpeed   = 1,
    .BusWidth    = 8,
    .SlotType    = EmbeddedSlot,
    .CardType    = EmmcCardType,
    .BaseClkFreq = 100000
  }, {
    .HighSpeed   = 1,
    .BusWidth    = 4,
    .SlotType    = RemovableSlot,
    .CardType    = SdCardType,
    .Voltage30   = 1,
    .BaseClkFreq = 100000
  }
};

EFI_STATUS
EFIAPI
HiKeyGetCapability (
  IN     EFI_HANDLE           Controller,
  IN     UINT8                Slot,
     OUT DW_MMC_HC_SLOT_CAP   *Capability
  )
{
  if (Capability == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  if (DwMmcCapability[0].Controller == 0) {
    DwMmcCapability[0].Controller = Controller;
    CopyMem (Capability, &DwMmcCapability[0], sizeof (DW_MMC_HC_SLOT_CAP));
  } else if (DwMmcCapability[0].Controller == Controller) {
    CopyMem (Capability, &DwMmcCapability[0], sizeof (DW_MMC_HC_SLOT_CAP));
  } else if (DwMmcCapability[1].Controller == 0) {
    DwMmcCapability[1].Controller = Controller;
    CopyMem (Capability, &DwMmcCapability[1], sizeof (DW_MMC_HC_SLOT_CAP));
  } else if (DwMmcCapability[1].Controller == Controller) {
    CopyMem (Capability, &DwMmcCapability[1], sizeof (DW_MMC_HC_SLOT_CAP));
  } else {
    return EFI_INVALID_PARAMETER;
  }
  return EFI_SUCCESS;
}

BOOLEAN
EFIAPI
HiKeyCardDetect (
  IN UINT8                    Slot
  )
{
  EFI_STATUS            Status;
  EMBEDDED_GPIO         *Gpio;
  UINTN                 Value;

  if (Slot == 0) {
    return TRUE;
  } else if (Slot == 1) {
    Status = gBS->LocateProtocol (&gEmbeddedGpioProtocolGuid, NULL, (VOID **)&Gpio);
    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Failed to get GPIO protocol: %r\n", Status));
      return FALSE;
    }
    Status = Gpio->Set (Gpio, DETECT_SD_CARD, GPIO_MODE_INPUT);
    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Failed to sed GPIO as input mode: %r\n", Status));
      return FALSE;
    }
    Status = Gpio->Get (Gpio, DETECT_SD_CARD, &Value);
    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Failed to get GPIO value: %r\n", Status));
      return FALSE;
    }
    if (Value == 0) {
      return TRUE;
    }
    return FALSE;
  } else {
    return FALSE;
  }
}

PLATFORM_DW_MMC_PROTOCOL mDwMmcDevice = {
  HiKeyGetCapability,
  HiKeyCardDetect
};

EFI_STATUS
EFIAPI
HiKeyMmcEntryPoint (
  IN EFI_HANDLE                            ImageHandle,
  IN EFI_SYSTEM_TABLE                      *SystemTable
  )
{
  EFI_STATUS        Status;

  Status = gBS->InstallProtocolInterface (
                  &ImageHandle,
                  &gPlatformDwMmcProtocolGuid,
                  EFI_NATIVE_INTERFACE,
                  &mDwMmcDevice
                  );
  if (EFI_ERROR (Status)) {
    return Status;
  }
  return Status;
}
