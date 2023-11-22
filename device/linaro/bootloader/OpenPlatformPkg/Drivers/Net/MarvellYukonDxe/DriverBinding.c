/**  <at> file
  Implementation of driver entry point and driver binding protocol.

Copyright (c) 2004 - 2010, Intel Corporation. All rights reserved.<BR>
Copyright (c) 2011 - 2016, ARM Limited. All rights reserved.

This program and the accompanying materials are licensed
and made available under the terms and conditions of the BSD License which
accompanies this distribution. The full text of the license may be found at
http://opensource.org/licenses/bsd-license.php

THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include <Library/NetLib.h>
#include <Library/DevicePathLib.h>
#include "MarvellYukon.h"
#include "if_msk.h"

STATIC LIST_ENTRY MarvellYukonDrvDataHead;

/**
  Test to see if this driver supports ControllerHandle. This service
  is called by the EFI boot service ConnectController(). In
  order to make drivers as small as possible, there are a few calling
  restrictions for this service. ConnectController() must
  follow these calling restrictions. If any other agent wishes to call
  Supported() it must also follow these calling restrictions.

   <at> param  This                   Protocol instance pointer.
   <at> param  ControllerHandle       Handle of device to test.
   <at> param  RemainingDevicePath    Optional parameter use to pick a specific child
                                                             device to start.

   <at> retval EFI_SUCCESS            This driver supports this device.
   <at> retval EFI_ALREADY_STARTED    This driver is already running on this device.
   <at> retval other                  This driver does not support this device.

**/
EFI_STATUS
EFIAPI
MarvellYukonDriverSupported (
    IN EFI_DRIVER_BINDING_PROTOCOL    *This,
    IN EFI_HANDLE                     Controller,
    IN EFI_DEVICE_PATH_PROTOCOL       *RemainingDevicePath
    )
{
  EFI_STATUS              Status;
  EFI_PCI_IO_PROTOCOL     *PciIo;

  //
  // Test that the PCI IO Protocol is attached to the controller handle and no other driver is consuming it
  //
  Status = gBS->OpenProtocol (
        Controller,
        &gEfiPciIoProtocolGuid,
        (VOID **) &PciIo,
        This->DriverBindingHandle,
        Controller,
        EFI_OPEN_PROTOCOL_BY_DRIVER
        );

  if (!EFI_ERROR (Status)) {
    //
    // Test whether the controller is on a supported NIC
    //
    Status = mskc_probe (PciIo);
    if (EFI_ERROR (Status)) {
      Status = EFI_UNSUPPORTED;
    } else {
      DEBUG ((EFI_D_NET, "Marvell Yukon: MarvellYukonDriverSupported: Supported Controller = %p\n", Controller));
    }

    gBS->CloseProtocol (
          Controller,
          &gEfiPciIoProtocolGuid,
          This->DriverBindingHandle,
          Controller
          );
  }

  return Status;
}

/**
  Start this driver on Controller by opening PciIo and DevicePath protocols.
  Initialize PXE structures, create a copy of the Controller Device Path with the
  NIC's MAC address appended to it, install the NetworkInterfaceIdentifier protocol
  on the newly created Device Path.

  @param [in] pThis                   Protocol instance pointer.
  @param [in] Controller              Handle of device to work with.
  @param [in] pRemainingDevicePath    Not used, always produce all possible children.

  @retval EFI_SUCCESS                 This driver is added to Controller.
  @retval other                       This driver does not support this device.

**/
EFI_STATUS
EFIAPI
MarvellYukonDriverStart (
    IN EFI_DRIVER_BINDING_PROTOCOL * pThis,
    IN EFI_HANDLE Controller,
    IN EFI_DEVICE_PATH_PROTOCOL * pRemainingDevicePath
    )
{

  EFI_STATUS                       Status;
  EFI_DEVICE_PATH_PROTOCOL         *ParentDevicePath;
  MAC_ADDR_DEVICE_PATH             MacDeviceNode;
  VOID                            *ChildPciIo;
  YUKON_DRIVER                    *YukonDriver;
  struct msk_softc                *ScData;
  EFI_PCI_IO_PROTOCOL             *PciIo;
  UINTN                            Port;

  Status = gBS->OpenProtocol (
        Controller,
        &gEfiPciIoProtocolGuid,
        (VOID **) &PciIo,
        pThis->DriverBindingHandle,
        Controller,
        EFI_OPEN_PROTOCOL_BY_DRIVER
        );

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: OpenProtocol: EFI_PCI_IO_PROTOCOL ERROR Status = %r\n", Status));
    gBS->FreePool (YukonDriver);
    return Status;
  }

  //
  // Initialize Marvell Yukon controller
  // Get number of ports and MAC address for each port
  //
  Status = mskc_attach (PciIo, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Status = MarvellYukonAddControllerData (Controller, ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  for (Port = 0; Port < ScData->msk_num_port; Port++) {

    Status = gBS->AllocatePool (EfiBootServicesData,
                                sizeof (YUKON_DRIVER),
                                (VOID**) &YukonDriver);
    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Marvell Yukon: AllocatePool() failed with Status = %r\n", Status));
      return Status;
    }

    if (ScData->msk_if[Port] == NULL) {
      DEBUG ((DEBUG_ERROR, "Marvell Yukon: AllocatePool() failed with Status = %r\n", EFI_BAD_BUFFER_SIZE));
      return EFI_BAD_BUFFER_SIZE;
    }

    gBS->SetMem (YukonDriver, sizeof (YUKON_DRIVER), 0);
    EfiInitializeLock (&YukonDriver->Lock, TPL_NOTIFY);

    //
    //  Set the structure signature
    //
    YukonDriver->Signature = YUKON_DRIVER_SIGNATURE;

    //
    // Set MAC address
    //
    gBS->CopyMem (&YukonDriver->SnpMode.PermanentAddress, &(ScData->msk_if[Port])->MacAddress,
                  sizeof (EFI_MAC_ADDRESS));

    //
    // Set Port number
    //
    YukonDriver->Port = Port;

    //
    //  Initialize the simple network protocol
    //
    Status = InitializeSNPProtocol (YukonDriver);

    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Marvell Yukon: InitializeSNPProtocol: ERROR Status = %r\n", Status));
      gBS->CloseProtocol (
            Controller,
            &gEfiPciIoProtocolGuid,
            pThis->DriverBindingHandle,
            Controller
            );
    }

    //
    // Set Device Path
    //
    Status = gBS->OpenProtocol (
          Controller,
          &gEfiDevicePathProtocolGuid,
          (VOID **) &ParentDevicePath,
          pThis->DriverBindingHandle,
          Controller,
          EFI_OPEN_PROTOCOL_GET_PROTOCOL
          );

    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Marvell Yukon: OpenProtocol:EFI_DEVICE_PATH_PROTOCOL error. Status = %r\n", Status));

      gBS->CloseProtocol (
            Controller,
            &gEfiPciIoProtocolGuid,
            pThis->DriverBindingHandle,
            Controller
            );

      gBS->FreePool (YukonDriver);
      return Status;
    }

    gBS->SetMem (&MacDeviceNode, sizeof (MAC_ADDR_DEVICE_PATH), 0);
    MacDeviceNode.Header.Type = MESSAGING_DEVICE_PATH;
    MacDeviceNode.Header.SubType = MSG_MAC_ADDR_DP;

    SetDevicePathNodeLength (&MacDeviceNode, sizeof (MacDeviceNode));

    //
    // Assign fields for device path
    //
    gBS->CopyMem (&YukonDriver->SnpMode.CurrentAddress, &YukonDriver->SnpMode.PermanentAddress,
                  sizeof (EFI_MAC_ADDRESS));
    gBS->CopyMem (&MacDeviceNode.MacAddress, &YukonDriver->SnpMode.CurrentAddress, sizeof (EFI_MAC_ADDRESS));

    MacDeviceNode.IfType = YukonDriver->SnpMode.IfType;
    YukonDriver->DevicePath = AppendDevicePathNode (ParentDevicePath, &MacDeviceNode.Header);
    if (YukonDriver->DevicePath == NULL) {
      DEBUG ((DEBUG_ERROR, "Marvell Yukon: AppendDevicePathNode: ERROR Status = %r\n", EFI_OUT_OF_RESOURCES));
      gBS->CloseProtocol (
            Controller,
            &gEfiPciIoProtocolGuid,
            pThis->DriverBindingHandle,
            Controller
            );
      gBS->FreePool (YukonDriver);
      return EFI_OUT_OF_RESOURCES;
    }

    //
    //  Install both the simple network and device path protocols.
    //
    Status = gBS->InstallMultipleProtocolInterfaces (
          &YukonDriver->Controller,
          &gEfiSimpleNetworkProtocolGuid,
          &YukonDriver->Snp,
          &gEfiDevicePathProtocolGuid,
          YukonDriver->DevicePath,
          NULL
          );

    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Marvell Yukon: InstallMultipleProtocolInterfaces error. Status = %r\n", Status));

      gBS->CloseProtocol (
            Controller,
            &gEfiPciIoProtocolGuid,
            pThis->DriverBindingHandle,
            Controller
            );

      gBS->FreePool (YukonDriver->DevicePath);
      gBS->FreePool (YukonDriver);
      return Status;
    } else {

      //
      // Hook as a child device
      //
      Status = gBS->OpenProtocol (Controller,
                                  &gEfiPciIoProtocolGuid,
                                  &ChildPciIo,
                                  pThis->DriverBindingHandle,
                                  YukonDriver->Controller,
                                  EFI_OPEN_PROTOCOL_BY_CHILD_CONTROLLER);
      if (EFI_ERROR (Status)) {
        DEBUG ((DEBUG_ERROR, "Marvell Yukon: OpenProtocol: child controller error. Status = %r\n", Status));

        gBS->UninstallMultipleProtocolInterfaces (
              Controller,
              &gEfiSimpleNetworkProtocolGuid,
              &YukonDriver->Snp,
              &gEfiDevicePathProtocolGuid,
              YukonDriver->DevicePath,
              NULL
              );

        gBS->CloseProtocol (
              Controller,
              &gEfiPciIoProtocolGuid,
              pThis->DriverBindingHandle,
              Controller
              );

        gBS->FreePool (YukonDriver->DevicePath);
        gBS->FreePool (YukonDriver);
        return Status;
      } else {
        DEBUG ((DEBUG_NET, "Marvell Yukon: MarvellYukonDriverSupported: New Controller Handle = %p\n",
               YukonDriver->Controller));
      }

      Status = MarvellYukonAddControllerData (YukonDriver->Controller, ScData);
      if (EFI_ERROR (Status)) {
        DEBUG ((DEBUG_ERROR, "Marvell Yukon: Failed to register port %d with controller handle %p\n", Port,
               YukonDriver->Controller));
      }

    }

    if (!EFI_ERROR (Status)) {
      Status = gBS->CreateEvent (EVT_SIGNAL_EXIT_BOOT_SERVICES, TPL_CALLBACK,
                      &MarvellYukonNotifyExitBoot, YukonDriver, &YukonDriver->ExitBootEvent);
    }
  }

  return Status;
}

/**
  Stop this driver on Controller by removing NetworkInterfaceIdentifier protocol and
  closing the DevicePath and PciIo protocols on Controller.

  @param [in] pThis                   Protocol instance pointer.
  @param [in] Controller              Handle of device to stop driver on.
  @param [in] NumberOfChildren        How many children need to be stopped.
  @param [in] pChildHandleBuffer      Not used.

  @retval EFI_SUCCESS                 This driver is removed Controller.
  @retval EFI_DEVICE_ERROR            The device could not be stopped due to a device error.
  @retval other                       This driver was not removed from this device.

**/
EFI_STATUS
EFIAPI
MarvellYukonDriverStop (
    IN  EFI_DRIVER_BINDING_PROTOCOL * pThis,
    IN  EFI_HANDLE Controller,
    IN  UINTN NumberOfChildren,
    IN  EFI_HANDLE * ChildHandleBuffer
    )
{
  EFI_SIMPLE_NETWORK_PROTOCOL  *SimpleNetwork;
  EFI_STATUS                   Status;
  YUKON_DRIVER                 *YukonDriver;
  EFI_TPL                      OldTpl;
  UINTN                        ChildController;
  struct msk_softc             *ScData;

  if (pThis == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: MarvellYukonDriverStop() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  if (NumberOfChildren > 0 && ChildHandleBuffer == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: MarvellYukonDriverStop() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  for (ChildController = 0; ChildController < NumberOfChildren; ChildController ++) {

    Status = gBS->OpenProtocol (
          ChildHandleBuffer[ChildController],
          &gEfiSimpleNetworkProtocolGuid,
          (VOID **) &SimpleNetwork,
          pThis->DriverBindingHandle,
          Controller,
          EFI_OPEN_PROTOCOL_GET_PROTOCOL
          );

    if (!EFI_ERROR(Status)) {

      YukonDriver = YUKON_DEV_FROM_THIS_SNP (SimpleNetwork);

      Status = MarvellYukonGetControllerData (YukonDriver->Controller, &ScData);
      if (EFI_ERROR (Status)) {
        continue;
      }

      OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

      ASSERT (YukonDriver->Controller == ChildHandleBuffer[ChildController]);
      if (YukonDriver->SnpMode.State != EfiSimpleNetworkStopped) {

         //
         // Device in use, cannot stop driver instance
         //
         Status = EFI_DEVICE_ERROR;
         DEBUG ((DEBUG_ERROR,
                "Marvell Yukon: MarvellYukonDriverStop: Error: SNP is not stopped. Status %r\n", Status));
       } else {

         //
         // Unhook the child controller
         //
         Status = gBS->CloseProtocol (Controller,
                             &gEfiPciIoProtocolGuid,
                             pThis->DriverBindingHandle,
                             YukonDriver->Controller);

         if (EFI_ERROR (Status)) {
           DEBUG ((DEBUG_ERROR,
                  "Marvell Yukon: MarvellYukonDriverStop:Close Child EfiPciIoProtocol error. Status %r\n", Status));
         }

         Status = gBS->UninstallMultipleProtocolInterfaces (
              YukonDriver->Controller,
              &gEfiSimpleNetworkProtocolGuid,
              &YukonDriver->Snp,
              &gEfiDevicePathProtocolGuid,
              YukonDriver->DevicePath,
              NULL
              );

         if (EFI_ERROR(Status)){
           DEBUG ((DEBUG_ERROR,
                  "Marvell Yukon: MarvellYukonDriverStop:UninstallMultipleProtocolInterfaces error. Status %r\n",
                  Status));
         }

         MarvellYukonDelControllerData (YukonDriver->Controller);

         gBS->CloseEvent (YukonDriver->ExitBootEvent);
         gBS->FreePool (YukonDriver->DevicePath);
         gBS->FreePool (YukonDriver);
       }
       gBS->RestoreTPL (OldTpl);
    }
  }

  Status = gBS->CloseProtocol (
        Controller,
        &gEfiPciIoProtocolGuid,
        pThis->DriverBindingHandle,
        Controller
        );

  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_ERROR, "Marvell Yukon: MarvellYukonDriverStop:Close EfiPciIoProtocol error. Status %r\n", Status));
  }

  Status = MarvellYukonGetControllerData (Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  mskc_detach (ScData);
  gBS->FreePool (ScData);
  Status = MarvellYukonDelControllerData (Controller);

  return Status;
}

/**
  Process exit boot event.

  @param [in] Event                   Event id.
  @param [in] Context                 Driver context.

**/
VOID
EFIAPI
MarvellYukonNotifyExitBoot (
  IN EFI_EVENT Event,
  IN VOID *Context
  )
{
  YUKON_DRIVER    *YukonDriver;
  EFI_STATUS      Status;

  if (Context == NULL) {
    DEBUG ((DEBUG_ERROR,
           "Marvell Yukon: MarvellYukonNotifyExitBoot() failed with Status = %r\n", EFI_INVALID_PARAMETER));
  } else {

    YukonDriver = Context;

    if (YukonDriver->SnpMode.State != EfiSimpleNetworkStopped) {
      Status  = YukonDriver->Snp.Shutdown(&YukonDriver->Snp);
      if (!EFI_ERROR (Status)) {
        YukonDriver->Snp.Stop(&YukonDriver->Snp);
      }
    }
  }
}

/**
  Get driver's data structure associated with controller

  @param [in] Controller           Controller Id.
  @param [out] Data                Driver's data.

**/
EFI_STATUS
EFIAPI
MarvellYukonGetControllerData (
  IN EFI_HANDLE Controller,
  OUT struct msk_softc **Data
  )
{
  MSK_LINKED_DRV_BUF *DrvNode;
  EFI_STATUS         Status;

  Status = MarvellYukonFindControllerNode (Controller, &DrvNode);
  if (!EFI_ERROR (Status)) {
    *Data = DrvNode->Data;
  }
  return Status;
}

/**
  Add driver's data structure associated with controller

  @param [in] Controller           Controller Id.
  @param [in] Data                 Driver's data.

**/
EFI_STATUS
EFIAPI
MarvellYukonAddControllerData (
  IN EFI_HANDLE Controller,
  IN struct msk_softc *Data
  )
{
  MSK_LINKED_DRV_BUF *DrvNode;
  EFI_STATUS         Status;

  Status = MarvellYukonFindControllerNode (Controller, &DrvNode);
  if (EFI_ERROR (Status)) {
    Status = gBS->AllocatePool (EfiBootServicesData,
                                sizeof (MSK_LINKED_DRV_BUF),
                                (VOID**) &DrvNode);
    if (!EFI_ERROR (Status)) {
      DrvNode->Signature = MSK_DRV_SIGNATURE;
      DrvNode->Controller = Controller;
      DrvNode->Data = Data;
      InsertTailList (&MarvellYukonDrvDataHead, &DrvNode->Link);
    }
  } else {
    Status = EFI_ALREADY_STARTED;
  }

  return Status;
}

/**
  Delete driver's data structure associated with controller

  @param [in] Controller           Controller Id.

**/
EFI_STATUS
EFIAPI
MarvellYukonDelControllerData (
  IN EFI_HANDLE Controller
  )
{
  MSK_LINKED_DRV_BUF *DrvNode;
  EFI_STATUS         Status;

  Status = MarvellYukonFindControllerNode (Controller, &DrvNode);
  if (!EFI_ERROR (Status)) {
    RemoveEntryList (&DrvNode->Link);
    gBS->FreePool (DrvNode);
  }

  return Status;
}

/**
  Find node associated with controller

  @param [in] Controller           Controller Id.
  @param [out] DrvLinkedBuff       Controller's node.

**/
EFI_STATUS
EFIAPI
MarvellYukonFindControllerNode (
  IN EFI_HANDLE Controller,
  OUT MSK_LINKED_DRV_BUF **DrvLinkedBuff
  )
{
  MSK_LINKED_DRV_BUF *DrvBuffNode;
  EFI_STATUS         Status;
  LIST_ENTRY         *Node;

  Status = EFI_NOT_FOUND;

  Node = GetFirstNode (&MarvellYukonDrvDataHead);
  while (!IsNull (&MarvellYukonDrvDataHead, Node)) {
    DrvBuffNode = MSK_DRV_INFO_FROM_THIS (Node);
    if (DrvBuffNode->Controller == Controller) {
      *DrvLinkedBuff = DrvBuffNode;
      Status = EFI_SUCCESS;
      break;
    }
    Node = GetNextNode (&MarvellYukonDrvDataHead, Node);
  }

  return Status;
}

//
// Simple Network Protocol Driver Global Variables
//
EFI_DRIVER_BINDING_PROTOCOL gMarvellYukonDriverBinding = {
  MarvellYukonDriverSupported,
  MarvellYukonDriverStart,
  MarvellYukonDriverStop,
  0xa,
  NULL,
  NULL
};

/**
  The Marvell Yukon driver entry point.

   <at> param ImageHandle             The driver image handle.
   <at> param SystemTable             The system table.

   <at> retval EFI_SUCCESS            Initialization routine has found and initialized
                                      hardware successfully.
   <at> retval Other                  Return value from HandleProtocol for
                                      DeviceIoProtocol or LoadedImageProtocol

**/
EFI_STATUS
EFIAPI
InitializeMarvellYukonDriver (
    IN EFI_HANDLE       ImageHandle,
    IN EFI_SYSTEM_TABLE *SystemTable
    )
{
  EFI_STATUS                           Status;

  DEBUG ((EFI_D_NET, "Marvell Yukon: InitializeMarvellYukonDriver()\n"));

  if (SystemTable == NULL) {
    DEBUG ((DEBUG_ERROR,
           "Marvell Yukon: InitializeMarvellYukonDriver() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  Status = EfiLibInstallDriverBindingComponentName2 (
        ImageHandle,
        SystemTable,
        &gMarvellYukonDriverBinding,
        NULL,
        &gSimpleNetworkComponentName,
        &gSimpleNetworkComponentName2
        );

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: InitializeMarvellYukonDriver(): Driver binding failed\n"));
    return Status;
  }

  InitializeListHead (&MarvellYukonDrvDataHead);

  return Status;
}
