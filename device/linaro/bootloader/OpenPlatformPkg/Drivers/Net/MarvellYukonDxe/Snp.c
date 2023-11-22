/**  <at> file
Provides the Simple Network functions.

Copyright (c) 2004 - 2010, Intel Corporation. All rights reserved.<BR>
Copyright (c) 2011 - 2016, ARM Limited. All rights reserved.

This program and the accompanying materials are licensed
and made available under the terms and conditions of the BSD License which
accompanies this distribution. The full text of the license may be found at
http://opensource.org/licenses/bsd-license.php

THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include "MarvellYukon.h"
#include "if_msk.h"

EFI_STATUS
InitializeSNPProtocol (
    IN OUT  YUKON_DRIVER *YukonDriver
    )
{
  EFI_STATUS    Status;

  Status = RETURN_SUCCESS;

  YukonDriver->Snp.Revision       = EFI_SIMPLE_NETWORK_PROTOCOL_REVISION;
  YukonDriver->Snp.Start          = SnpStart;
  YukonDriver->Snp.Stop           = SnpStop;
  YukonDriver->Snp.Initialize     = SnpInitialize;
  YukonDriver->Snp.Reset          = SnpReset;
  YukonDriver->Snp.Shutdown       = SnpShutdown;
  YukonDriver->Snp.ReceiveFilters = SnpReceiveFilters;
  YukonDriver->Snp.StationAddress = SnpStationAddress;
  YukonDriver->Snp.Statistics     = SnpStatistics;
  YukonDriver->Snp.MCastIpToMac   = SnpMcastIpToMac;
  YukonDriver->Snp.NvData         = SnpNvData;
  YukonDriver->Snp.GetStatus      = SnpGetStatus;
  YukonDriver->Snp.Transmit       = SnpTransmit;
  YukonDriver->Snp.Receive        = SnpReceive;
  YukonDriver->Snp.WaitForPacket  = NULL;

  YukonDriver->Snp.Mode           = &YukonDriver->SnpMode;

  //
  //  Initialize simple network protocol mode structure
  //
  YukonDriver->SnpMode.State               = EfiSimpleNetworkStopped;
  YukonDriver->SnpMode.HwAddressSize       = NET_ETHER_ADDR_LEN;
  YukonDriver->SnpMode.MediaHeaderSize     = sizeof (ETHER_HEAD);
  YukonDriver->SnpMode.MaxPacketSize       = MAX_SUPPORTED_PACKET_SIZE;
  YukonDriver->SnpMode.NvRamAccessSize     = 0;
  YukonDriver->SnpMode.NvRamSize           = 0;
  YukonDriver->SnpMode.IfType              = NET_IFTYPE_ETHERNET;
  YukonDriver->SnpMode.MaxMCastFilterCount = MAX_MCAST_FILTER_CNT;
  YukonDriver->SnpMode.MCastFilterCount    = 0;
  gBS->SetMem (&YukonDriver->SnpMode.MCastFilter, MAX_MCAST_FILTER_CNT * sizeof(EFI_MAC_ADDRESS), 0);

  //
  //  Set broadcast address
  //
  gBS->SetMem (&YukonDriver->SnpMode.BroadcastAddress, sizeof (EFI_MAC_ADDRESS), 0xFF);

  YukonDriver->SnpMode.MediaPresentSupported = FALSE;
  YukonDriver->SnpMode.MacAddressChangeable = FALSE;
  YukonDriver->SnpMode.MultipleTxSupported = FALSE;
  YukonDriver->SnpMode.ReceiveFilterMask = EFI_SIMPLE_NETWORK_RECEIVE_UNICAST;
  YukonDriver->SnpMode.ReceiveFilterSetting = 0;

  YukonDriver->SnpMode.MediaPresent = TRUE;

  return Status;
}

/**
  Reads the current interrupt status and recycled transmit buffer status from a
  network interface.

  This function gets the current interrupt and recycled transmit buffer status
  from the network interface. The interrupt status is returned as a bit mask in
  InterruptStatus. If InterruptStatus is NULL, the interrupt status will not be
  read. If TxBuf is not NULL, a recycled transmit buffer address will be retrieved.
  If a recycled transmit buffer address is returned in TxBuf, then the buffer has
  been successfully transmitted, and the status for that buffer is cleared. If
  the status of the network interface is successfully collected, EFI_SUCCESS
  will be returned. If the driver has not been initialized, EFI_DEVICE_ERROR will
  be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param InterruptStatus         A pointer to the bit mask of the currently active
                                      interrupts (see "Related Definitions"). If this is NULL,
                                      the interrupt status will not be read from the device.
                                      If this is not NULL, the interrupt status will be read
                                      from the device. When the interrupt status is read, it
                                      will also be cleared. Clearing the transmit interrupt does
                                      not empty the recycled transmit buffer array.
   <at> param TxBuf                   Recycled transmit buffer address. The network interface
                                      will not transmit if its internal recycled transmit
                                      buffer array is full. Reading the transmit buffer does
                                      not clear the transmit interrupt. If this is NULL, then
                                      the transmit buffer status will not be read. If there
                                      are no transmit buffers to recycle and TxBuf is not NULL,
                                      TxBuf will be set to NULL.

   <at> retval EFI_SUCCESS            The status of the network interface was retrieved.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_INVALID_PARAMETER  This parameter was NULL or did not point to a valid
                                      EFI_SIMPLE_NETWORK_PROTOCOL structure.
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network
                                      interface.

**/
EFI_STATUS
EFIAPI
SnpGetStatus (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    OUT UINT32                     *InterruptStatus, OPTIONAL
    OUT VOID                       **TxBuf           OPTIONAL
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *Snp;
  EFI_TPL       OldTpl;
  struct msk_softc *ScData;

  if (This == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  Snp = YUKON_DEV_FROM_THIS_SNP (This);
  if (Snp == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  Status = MarvellYukonGetControllerData (Snp->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (Snp->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_EXIT;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_EXIT;
  }

  mskc_getstatus (ScData->msk_if[Snp->Port], InterruptStatus, TxBuf);
  Status = EFI_SUCCESS;

ON_EXIT:
  gBS->RestoreTPL (OldTpl);

  return Status;
}

/**
  Resets a network adapter and allocates the transmit and receive buffers
  required by the network interface; optionally, also requests allocation of
  additional transmit and receive buffers.

  This function allocates the transmit and receive buffers required by the network
  interface. If this allocation fails, then EFI_OUT_OF_RESOURCES is returned.
  If the allocation succeeds and the network interface is successfully initialized,
  then EFI_SUCCESS will be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.

   <at> param ExtraRxBufferSize       The size, in bytes, of the extra receive buffer space
                                      that the driver should allocate for the network interface.
                                      Some network interfaces will not be able to use the
                                      extra buffer, and the caller will not know if it is
                                      actually being used.
   <at> param ExtraTxBufferSize       The size, in bytes, of the extra transmit buffer space
                                      that the driver should allocate for the network interface.
                                      Some network interfaces will not be able to use the
                                      extra buffer, and the caller will not know if it is
                                      actually being used.

   <at> retval EFI_SUCCESS            The network interface was initialized.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_OUT_OF_RESOURCES   There was not enough memory for the transmit and
                                      receive buffers.
   <at> retval EFI_INVALID_PARAMETER  This parameter was NULL or did not point to a valid
                                                                   EFI_SIMPLE_NETWORK_PROTOCOL structure.
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network interface.
   <at> retval EFI_UNSUPPORTED        The increased buffer size feature is not supported.

**/
EFI_STATUS
EFIAPI
SnpInitialize (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN UINTN                       ExtraRxBufferSize OPTIONAL,
    IN UINTN                       ExtraTxBufferSize OPTIONAL
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;
  struct msk_softc *ScData;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpInitialize()\n"));
  if (This == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpInitialize() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  Status = MarvellYukonGetControllerData (YukonDriver->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkStarted:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_ERROR_RESTORE_TPL;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_ERROR_RESTORE_TPL;
  }

  YukonDriver->SnpMode.MCastFilterCount      = 0;
  YukonDriver->SnpMode.ReceiveFilterSetting  = 0;
  gBS->SetMem (YukonDriver->SnpMode.MCastFilter, sizeof YukonDriver->SnpMode.MCastFilter, 0);
  gBS->CopyMem (&YukonDriver->SnpMode.CurrentAddress, &YukonDriver->SnpMode.PermanentAddress, sizeof (EFI_MAC_ADDRESS));

  Status = mskc_init (ScData->msk_if[YukonDriver->Port]);

  if (EFI_ERROR (Status)) {
     goto ON_ERROR_RESTORE_TPL;
  }

  YukonDriver->SnpMode.State = EfiSimpleNetworkInitialized;
  goto ON_EXIT;

ON_ERROR_RESTORE_TPL:
  DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpInitialize() failed with Status = %r\n", Status));

ON_EXIT:
  gBS->RestoreTPL (OldTpl);
  return Status;
}

/**
  Converts a multicast IP address to a multicast HW MAC address.

  This function converts a multicast IP address to a multicast HW MAC address
  for all packet transactions. If the mapping is accepted, then EFI_SUCCESS will
  be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param IPv6                    Set to TRUE if the multicast IP address is IPv6 [RFC 2460].
                                      Set to FALSE if the multicast IP address is IPv4 [RFC 791].
   <at> param IP                      The multicast IP address that is to be converted to a multicast
                                      HW MAC address.
   <at> param MAC                     The multicast HW MAC address that is to be generated from IP.

   <at> retval EFI_SUCCESS            The multicast IP address was mapped to the
                                      multicast HW MAC address.
   <at> retval EFI_NOT_STARTED        The Simple Network Protocol interface has not
                                      been started by calling Start().
   <at> retval EFI_INVALID_PARAMETER  IP is NULL.
   <at> retval EFI_INVALID_PARAMETER  MAC is NULL.
   <at> retval EFI_INVALID_PARAMETER  IP does not point to a valid IPv4 or IPv6
                                      multicast address.
   <at> retval EFI_DEVICE_ERROR       The Simple Network Protocol interface has not
                                      been initialized by calling Initialize().
   <at> retval EFI_UNSUPPORTED        IPv6 is TRUE and the implementation does not
                                      support IPv6 multicast to MAC address conversion.

**/
EFI_STATUS
EFIAPI
SnpMcastIpToMac (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN BOOLEAN                     IPv6,
    IN EFI_IP_ADDRESS              *IP,
    OUT EFI_MAC_ADDRESS            *MAC
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpMcastIpToMac()\n"));

  //
  // Get pointer to SNP driver instance for *this.
  //
  if (This == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  if (IP == NULL || MAC == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_EXIT;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_EXIT;
  }

  Status = EFI_UNSUPPORTED;

ON_EXIT:
  gBS->RestoreTPL (OldTpl);

  return Status;
}

/**
  Performs read and write operations on the NVRAM device attached to a network
  interface.

  This function performs read and write operations on the NVRAM device attached
  to a network interface. If ReadWrite is TRUE, a read operation is performed.
  If ReadWrite is FALSE, a write operation is performed. Offset specifies the
  byte offset at which to start either operation. Offset must be a multiple of
  NvRamAccessSize , and it must have a value between zero and NvRamSize.
  BufferSize specifies the length of the read or write operation. BufferSize must
  also be a multiple of NvRamAccessSize, and Offset + BufferSize must not exceed
  NvRamSize.
  If any of the above conditions is not met, then EFI_INVALID_PARAMETER will be
  returned.
  If all the conditions are met and the operation is "read," the NVRAM device
  attached to the network interface will be read into Buffer and EFI_SUCCESS
  will be returned. If this is a write operation, the contents of Buffer will be
  used to update the contents of the NVRAM device attached to the network
  interface and EFI_SUCCESS will be returned.

  It does the basic checking on the input parameters and retrieves snp structure
  and then calls the read_nvdata() call which does the actual reading

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param ReadWrite               TRUE for read operations, FALSE for write operations.
   <at> param Offset                  Byte offset in the NVRAM device at which to start the read or
                                      write operation. This must be a multiple of NvRamAccessSize
                                      and less than NvRamSize. (See EFI_SIMPLE_NETWORK_MODE)
   <at> param BufferSize              The number of bytes to read or write from the NVRAM device.
                                      This must also be a multiple of NvramAccessSize.
   <at> param Buffer                  A pointer to the data buffer.

   <at> retval EFI_SUCCESS            The NVRAM access was performed.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_INVALID_PARAMETER  One or more of the following conditions is TRUE:
                                      * The This parameter is NULL
                                      * The This parameter does not point to a valid
                                        EFI_SIMPLE_NETWORK_PROTOCOL  structure
                                      * The Offset parameter is not a multiple of
                                        EFI_SIMPLE_NETWORK_MODE.NvRamAccessSize
                                      * The Offset parameter is not less than
                                        EFI_SIMPLE_NETWORK_MODE.NvRamSize
                                      * The BufferSize parameter is not a multiple of
                                        EFI_SIMPLE_NETWORK_MODE.NvRamAccessSize
                                      * The Buffer parameter is NULL
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network
                                      interface.
   <at> retval EFI_UNSUPPORTED        This function is not supported by the network
                                      interface.

**/
EFI_STATUS
EFIAPI
SnpNvData (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN BOOLEAN                     ReadWrite,
    IN UINTN                       Offset,
    IN UINTN                       BufferSize,
    IN OUT VOID                    *Buffer
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpNvData()\n"));
  //
  // Get pointer to SNP driver instance for *this.
  //
  if (This == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  //
  // Return error if the SNP is not initialized.
  //
  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_EXIT;

    case EfiSimpleNetworkStarted:
    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_EXIT;
  }
  //
  // Return error if non-volatile memory variables are not valid.
  //
  if (YukonDriver->SnpMode.NvRamSize == 0 || YukonDriver->SnpMode.NvRamAccessSize == 0) {
    Status = EFI_UNSUPPORTED;
    goto ON_EXIT;
  }
  //
  // Check for invalid parameter combinations.
  //
  if ((BufferSize == 0) ||
      (Buffer == NULL) ||
      (Offset >= YukonDriver->SnpMode.NvRamSize) ||
      (Offset + BufferSize > YukonDriver->SnpMode.NvRamSize) ||
      (BufferSize % YukonDriver->SnpMode.NvRamAccessSize != 0) ||
      (Offset % YukonDriver->SnpMode.NvRamAccessSize != 0)
      ) {
    Status = EFI_INVALID_PARAMETER;
    goto ON_EXIT;
  }

  Status = EFI_UNSUPPORTED;

ON_EXIT:
  gBS->RestoreTPL (OldTpl);

  return Status;
}

/**
  Receives a packet from a network interface.

  This function retrieves one packet from the receive queue of a network interface.
  If there are no packets on the receive queue, then EFI_NOT_READY will be
  returned. If there is a packet on the receive queue, and the size of the packet
  is smaller than BufferSize, then the contents of the packet will be placed in
  Buffer, and BufferSize will be updated with the actual size of the packet.
  In addition, if SrcAddr, DestAddr, and Protocol are not NULL, then these values
  will be extracted from the media header and returned. EFI_SUCCESS will be
  returned if a packet was successfully received.
  If BufferSize is smaller than the received packet, then the size of the receive
  packet will be placed in BufferSize and EFI_BUFFER_TOO_SMALL will be returned.
  If the driver has not been initialized, EFI_DEVICE_ERROR will be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param HeaderSize              The size, in bytes, of the media header received on the network
                                      interface. If this parameter is NULL, then the media header size
                                      will not be returned.
   <at> param BufferSize              On entry, the size, in bytes, of Buffer. On exit, the size, in
                                      bytes, of the packet that was received on the network interface.
   <at> param Buffer                  A pointer to the data buffer to receive both the media
                                      header and the data.
   <at> param SrcAddr                 The source HW MAC address. If this parameter is NULL, the HW
                                      MAC source address will not be extracted from the media header.
   <at> param DestAddr                The destination HW MAC address. If this parameter is NULL,
                                      the HW MAC destination address will not be extracted from
                                      the media header.
   <at> param Protocol                The media header type. If this parameter is NULL, then the
                                      protocol will not be extracted from the media header. See
                                      RFC 1700 section "Ether Types" for examples.

   <at> retval EFI_SUCCESS            The received data was stored in Buffer, and
                                      BufferSize has been updated to the number of
                                      bytes received.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_NOT_READY          No packets have been received on the network interface.
   <at> retval EFI_BUFFER_TOO_SMALL   BufferSize is too small for the received packets.
                                      BufferSize has been updated to the required size.
   <at> retval EFI_INVALID_PARAMETER  One or more of the following conditions is TRUE:
                                      * The This parameter is NULL
                                      * The This parameter does not point to a valid
                                        EFI_SIMPLE_NETWORK_PROTOCOL structure.
                                      * The BufferSize parameter is NULL
                                      * The Buffer parameter is NULL
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network interface.

**/
EFI_STATUS
EFIAPI
SnpReceive (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    OUT UINTN                      *HeaderSize OPTIONAL,
    IN OUT UINTN                   *BufferSize,
    OUT VOID                       *Buffer,
    OUT EFI_MAC_ADDRESS            *SrcAddr OPTIONAL,
    OUT EFI_MAC_ADDRESS            *DestAddr OPTIONAL,
    OUT UINT16                     *Protocol OPTIONAL
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *Snp;
  EFI_TPL       OldTpl;
  struct msk_softc *ScData;

  if (This == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  Snp = YUKON_DEV_FROM_THIS_SNP (This);

  Status = MarvellYukonGetControllerData (Snp->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (Snp->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_EXIT;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_EXIT;
  }

  if ((BufferSize == NULL) || (Buffer == NULL)) {
    Status = EFI_INVALID_PARAMETER;
    goto ON_EXIT;
  }

  Status = mskc_receive (ScData->msk_if[Snp->Port], BufferSize, Buffer);
  if (EFI_ERROR (Status)) {
    if (Status == EFI_NOT_READY) {
      goto ON_EXIT_NO_DEBUG;
    }
  } else {
    // Extract header info
    if (HeaderSize != NULL) {
      *HeaderSize = sizeof (ETHER_HEAD);
    }

    if (SrcAddr != NULL) {
      gBS->SetMem (SrcAddr, NET_ETHER_ADDR_LEN, 0);
      gBS->CopyMem (SrcAddr, ((UINT8 *) Buffer) + NET_ETHER_ADDR_LEN, NET_ETHER_ADDR_LEN);
    }

    if (DestAddr != NULL) {
      gBS->SetMem (DestAddr, NET_ETHER_ADDR_LEN, 0);
      gBS->CopyMem (DestAddr, ((UINT8 *) Buffer), NET_ETHER_ADDR_LEN);
    }

    if (Protocol != NULL) {
      *Protocol = NTOHS (*((UINT16 *) (((UINT8 *) Buffer) + (2 * NET_ETHER_ADDR_LEN))));
    }
  }

ON_EXIT:
  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpReceive() Status = %r\n", Status));

ON_EXIT_NO_DEBUG:
  gBS->RestoreTPL (OldTpl);
  return Status;
}

/**
  Manages the multicast receive filters of a network interface.

  This function is used enable and disable the hardware and software receive
  filters for the underlying network device.
  The receive filter change is broken down into three steps:
  * The filter mask bits that are set (ON) in the Enable parameter are added to
    the current receive filter settings.
  * The filter mask bits that are set (ON) in the Disable parameter are subtracted
    from the updated receive filter settings.
  * If the resulting receive filter setting is not supported by the hardware a
    more liberal setting is selected.
  If the same bits are set in the Enable and Disable parameters, then the bits
  in the Disable parameter takes precedence.
  If the ResetMCastFilter parameter is TRUE, then the multicast address list
  filter is disabled (irregardless of what other multicast bits are set in the
  Enable and Disable parameters). The SNP->Mode->MCastFilterCount field is set
  to zero. The Snp->Mode->MCastFilter contents are undefined.
  After enabling or disabling receive filter settings, software should verify
  the new settings by checking the Snp->Mode->ReceiveFilterSettings,
  Snp->Mode->MCastFilterCount and Snp->Mode->MCastFilter fields.
  Note: Some network drivers and/or devices will automatically promote receive
    filter settings if the requested setting can not be honored. For example, if
    a request for four multicast addresses is made and the underlying hardware
    only supports two multicast addresses the driver might set the promiscuous
    or promiscuous multicast receive filters instead. The receiving software is
    responsible for discarding any extra packets that get through the hardware
    receive filters.
    Note: Note: To disable all receive filter hardware, the network driver must
      be Shutdown() and Stopped(). Calling ReceiveFilters() with Disable set to
      Snp->Mode->ReceiveFilterSettings will make it so no more packets are
      returned by the Receive() function, but the receive hardware may still be
      moving packets into system memory before inspecting and discarding them.
      Unexpected system errors, reboots and hangs can occur if an OS is loaded
      and the network devices are not Shutdown() and Stopped().
  If ResetMCastFilter is TRUE, then the multicast receive filter list on the
  network interface will be reset to the default multicast receive filter list.
  If ResetMCastFilter is FALSE, and this network interface allows the multicast
  receive filter list to be modified, then the MCastFilterCnt and MCastFilter
  are used to update the current multicast receive filter list. The modified
  receive filter list settings can be found in the MCastFilter field of
  EFI_SIMPLE_NETWORK_MODE. If the network interface does not allow the multicast
  receive filter list to be modified, then EFI_INVALID_PARAMETER will be returned.
  If the driver has not been initialized, EFI_DEVICE_ERROR will be returned.
  If the receive filter mask and multicast receive filter list have been
  successfully updated on the network interface, EFI_SUCCESS will be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param Enable                  A bit mask of receive filters to enable on the network
                                      interface.
   <at> param Disable                 A bit mask of receive filters to disable on the network
                                      interface. For backward compatibility with EFI 1.1
                                      platforms, the EFI_SIMPLE_NETWORK_RECEIVE_MULTICAST bit
                                      must be set when the ResetMCastFilter parameter is TRUE.
   <at> param ResetMCastFilter        Set to TRUE to reset the contents of the multicast
                                      receive filters on the network interface to their
                                      default values.
   <at> param MCastFilterCnt          Number of multicast HW MAC addresses in the new MCastFilter
                                      list. This value must be less than or equal to the
                                      MCastFilterCnt field of EFI_SIMPLE_NETWORK_MODE.
                                      This field is optional if ResetMCastFilter is TRUE.
   <at> param MCastFilter             A pointer to a list of new multicast receive filter HW
                                      MAC addresses. This list will replace any existing
                                      multicast HW MAC address list. This field is optional
                                      if ResetMCastFilter is TRUE.

   <at> retval EFI_SUCCESS            The multicast receive filter list was updated.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_INVALID_PARAMETER  One or more of the following conditions is TRUE:
                                      * This is NULL
                                      * There are bits set in Enable that are not set
                                        in Snp->Mode->ReceiveFilterMask
                                      * There are bits set in Disable that are not set
                                        in Snp->Mode->ReceiveFilterMask
                                      * Multicast is being enabled (the
                                        EFI_SIMPLE_NETWORK_RECEIVE_MULTICAST bit is
                                        set in Enable, it is not set in Disable, and
                                        ResetMCastFilter is FALSE) and MCastFilterCount
                                        is zero
                                      * Multicast is being enabled and MCastFilterCount
                                        is greater than Snp->Mode->MaxMCastFilterCount
                                      * Multicast is being enabled and MCastFilter is NULL
                                      * Multicast is being enabled and one or more of
                                        the addresses in the MCastFilter list are not
                                        valid multicast MAC addresses
   <at> retval EFI_DEVICE_ERROR       One or more of the following conditions is TRUE:
                                      * The network interface has been started but has
                                        not been initialized
                                      * An unexpected error was returned by the
                                        underlying network driver or device
   <at> retval EFI_UNSUPPORTED        This function is not supported by the network
                                      interface.

**/
EFI_STATUS
EFIAPI
SnpReceiveFilters (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN UINT32                      Enable,
    IN UINT32                      Disable,
    IN BOOLEAN                     ResetMCastFilter,
    IN UINTN                       MCastFilterCnt,  OPTIONAL
    IN EFI_MAC_ADDRESS             *MCastFilter     OPTIONAL
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;
  UINT32        newReceiveFilter;
  struct msk_softc *ScData;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpReceiveFilters()\n"));
  if (This == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpReceiveFilters() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  Status = MarvellYukonGetControllerData (YukonDriver->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_ERROR_RESTORE_TPL;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_ERROR_RESTORE_TPL;
  }
  //
  // check if we are asked to enable or disable something that the NIC
  // does not even support!
  //
  newReceiveFilter = (YukonDriver->SnpMode.ReceiveFilterSetting | Enable) & ~Disable;
  if ((newReceiveFilter & ~YukonDriver->SnpMode.ReceiveFilterMask) != 0) {
    DEBUG ((DEBUG_NET,
           "Marvell Yukon: SnpReceiveFilters() NIC does not support Enable = 0x%x, Disable = 0x%x\n", Enable, Disable));
    Status = EFI_INVALID_PARAMETER;
    goto ON_ERROR_RESTORE_TPL;
  }

  if (ResetMCastFilter) {
    newReceiveFilter &= ~(EFI_SIMPLE_NETWORK_RECEIVE_MULTICAST & YukonDriver->SnpMode.ReceiveFilterMask);
    MCastFilterCnt = 0;
    MCastFilter    = NULL;
  } else {
    if (MCastFilterCnt != 0) {
      if ((MCastFilterCnt > YukonDriver->SnpMode.MaxMCastFilterCount) ||
          (MCastFilter == NULL)) {

        DEBUG ((DEBUG_NET,
               "Marvell Yukon: SnpReceiveFilters() NIC does not support MCastFilterCnt = %d (Max = %d)\n",
               MCastFilterCnt, YukonDriver->SnpMode.MaxMCastFilterCount));
        Status = EFI_INVALID_PARAMETER;
        goto ON_ERROR_RESTORE_TPL;
      }
    }
  }

  if (newReceiveFilter == YukonDriver->SnpMode.ReceiveFilterSetting && !ResetMCastFilter && MCastFilterCnt == 0) {
    Status = EFI_SUCCESS;
    goto ON_EXIT;
  }

  if ((Enable & EFI_SIMPLE_NETWORK_RECEIVE_MULTICAST) != 0 && MCastFilterCnt == 0) {
    Status = EFI_INVALID_PARAMETER;
    goto ON_ERROR_RESTORE_TPL;
  }

  YukonDriver->SnpMode.ReceiveFilterSetting = newReceiveFilter;
  mskc_rxfilter (ScData->msk_if[YukonDriver->Port], YukonDriver->SnpMode.ReceiveFilterSetting,
                 MCastFilterCnt, MCastFilter);

  Status = EFI_SUCCESS;
  goto ON_EXIT;

ON_ERROR_RESTORE_TPL:
  DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpReceiveFilters() failed with Status = %r\n", Status));

ON_EXIT:
  gBS->RestoreTPL (OldTpl);
  return Status;
}

/**
  Resets a network adapter and reinitializes it with the parameters that were
  provided in the previous call to Initialize().

  This function resets a network adapter and reinitializes it with the parameters
  that were provided in the previous call to Initialize(). The transmit and
  receive queues are emptied and all pending interrupts are cleared.
  Receive filters, the station address, the statistics, and the multicast-IP-to-HW
  MAC addresses are not reset by this call. If the network interface was
  successfully reset, then EFI_SUCCESS will be returned. If the driver has not
  been initialized, EFI_DEVICE_ERROR will be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param ExtendedVerification    Indicates that the driver may perform a more
                                      exhaustive verification operation of the device
                                      during reset.

   <at> retval EFI_SUCCESS            The network interface was reset.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_INVALID_PARAMETER  One or more of the parameters has an unsupported value.
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network interface.
   <at> retval EFI_UNSUPPORTED        This function is not supported by the network interface.

**/
EFI_STATUS
EFIAPI
SnpReset (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN BOOLEAN                     ExtendedVerification
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpReset()\n"));
  //
  // Resolve Warning 4 unreferenced parameter problem
  //
  ExtendedVerification = 0;
  DEBUG ((EFI_D_WARN, "Marvell Yukon: ExtendedVerification = %d is not implemented!\n", ExtendedVerification));

  if (This == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpReset() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_ERROR_RESTORE_TPL;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_ERROR_RESTORE_TPL;
  }

  // Always succeeds
  Status = EFI_SUCCESS;
  goto ON_EXIT;

ON_ERROR_RESTORE_TPL:
  DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpReset() failed with Status = %r\n", Status));

ON_EXIT:
  gBS->RestoreTPL (OldTpl);
  return Status;
}

/**
  Resets a network adapter and leaves it in a state that is safe for another
  driver to initialize.

  This function releases the memory buffers assigned in the Initialize() call.
  Pending transmits and receives are lost, and interrupts are cleared and disabled.
  After this call, only the Initialize() and Stop() calls may be used. If the
  network interface was successfully shutdown, then EFI_SUCCESS will be returned.
  If the driver has not been initialized, EFI_DEVICE_ERROR will be returned.

   <at> param  This                   A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.

   <at> retval EFI_SUCCESS            The network interface was shutdown.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_INVALID_PARAMETER  This parameter was NULL or did not point to a valid
                                      EFI_SIMPLE_NETWORK_PROTOCOL structure.
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network interface.

**/
EFI_STATUS
EFIAPI
SnpShutdown (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;
  struct msk_softc *ScData;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpShutdown()\n"));
  //
  // Get pointer to SNP driver instance for *This.
  //
  if (This == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpShutdown() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  Status = MarvellYukonGetControllerData (YukonDriver->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  //
  // Return error if the SNP is not initialized.
  //
  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_ERROR_RESTORE_TPL;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_ERROR_RESTORE_TPL;
  }

  mskc_stop_if (ScData->msk_if[YukonDriver->Port]);
  YukonDriver->SnpMode.State = EfiSimpleNetworkStarted;
  Status = EFI_SUCCESS;

  YukonDriver->SnpMode.State                 = EfiSimpleNetworkStarted;
  YukonDriver->SnpMode.ReceiveFilterSetting  = 0;

  YukonDriver->SnpMode.MCastFilterCount      = 0;
  YukonDriver->SnpMode.ReceiveFilterSetting  = 0;
  gBS->SetMem (YukonDriver->SnpMode.MCastFilter, sizeof YukonDriver->SnpMode.MCastFilter, 0);
  gBS->CopyMem (
        &YukonDriver->SnpMode.CurrentAddress,
        &YukonDriver->SnpMode.PermanentAddress,
        sizeof (EFI_MAC_ADDRESS)
        );

  goto ON_EXIT;

ON_ERROR_RESTORE_TPL:
  DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpShutdown() failed with Status = %r\n", Status));

ON_EXIT:
  gBS->RestoreTPL (OldTpl);
  return Status;
}

/**
  Change the state of a network interface from "stopped" to "started."

  This function starts a network interface. If the network interface successfully
  starts, then EFI_SUCCESS will be returned.

   <at> param  This                   A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.

   <at> retval EFI_SUCCESS            The network interface was started.
   <at> retval EFI_ALREADY_STARTED    The network interface is already in the started state.
   <at> retval EFI_INVALID_PARAMETER  This parameter was NULL or did not point to a valid
                                      EFI_SIMPLE_NETWORK_PROTOCOL structure.
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network interface.
   <at> retval EFI_UNSUPPORTED        This function is not supported by the network interface.

**/
EFI_STATUS
EFIAPI
SnpStart (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This
    )
{
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;
  EFI_STATUS    Status;
  struct msk_softc  *ScData;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpStart()\n"));
  if (This == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpStart() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  Status = MarvellYukonGetControllerData (YukonDriver->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkStopped:
      break;

    case EfiSimpleNetworkStarted:
    case EfiSimpleNetworkInitialized:
      Status = EFI_ALREADY_STARTED;
      goto ON_EXIT;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_ERROR_RESTORE_TPL;
  }

  Status = mskc_attach_if (ScData->msk_if[YukonDriver->Port], YukonDriver->Port);

  if (EFI_ERROR (Status)) {
    goto ON_ERROR_RESTORE_TPL;
  }

  YukonDriver->SnpMode.State = EfiSimpleNetworkStarted;
  gBS->CopyMem (&YukonDriver->SnpMode.CurrentAddress, &YukonDriver->SnpMode.PermanentAddress, sizeof (EFI_MAC_ADDRESS));
  YukonDriver->SnpMode.MCastFilterCount = 0;
  goto ON_EXIT;

ON_ERROR_RESTORE_TPL:
  DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpStart() failed with Status = %r\n", Status));

ON_EXIT:
  gBS->RestoreTPL (OldTpl);
  return Status;
}

/**
  Modifies or resets the current station address, if supported.

  This function modifies or resets the current station address of a network
  interface, if supported. If Reset is TRUE, then the current station address is
  set to the network interface's permanent address. If Reset is FALSE, and the
  network interface allows its station address to be modified, then the current
  station address is changed to the address specified by New. If the network
  interface does not allow its station address to be modified, then
  EFI_INVALID_PARAMETER will be returned. If the station address is successfully
  updated on the network interface, EFI_SUCCESS will be returned. If the driver
  has not been initialized, EFI_DEVICE_ERROR will be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param Reset                   Flag used to reset the station address to the network interface's
                                      permanent address.
   <at> param New                     New station address to be used for the network interface.


   <at> retval EFI_SUCCESS            The network interface's station address was updated.
   <at> retval EFI_NOT_STARTED        The Simple Network Protocol interface has not been
                                                                 started by calling Start().
   <at> retval EFI_INVALID_PARAMETER  The New station address was not accepted by the NIC.
   <at> retval EFI_INVALID_PARAMETER  Reset is FALSE and New is NULL.
   <at> retval EFI_DEVICE_ERROR       The Simple Network Protocol interface has not
                                      been initialized by calling Initialize().
   <at> retval EFI_DEVICE_ERROR       An error occurred attempting to set the new
                                      station address.
   <at> retval EFI_UNSUPPORTED        The NIC does not support changing the network
                                      interface's station address.

**/
EFI_STATUS
EFIAPI
SnpStationAddress (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN BOOLEAN                     Reset,
    IN EFI_MAC_ADDRESS             *New OPTIONAL
    )
{
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;
  EFI_STATUS    Status;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpStationAddress()\n"));
  //
  // Check for invalid parameter combinations.
  //
  if ((This == NULL) || (!Reset && (New == NULL))) {
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  //
  // Return error if the SNP is not initialized.
  //
  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_EXIT;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_EXIT;
  }

  Status = EFI_UNSUPPORTED;

ON_EXIT:
  gBS->RestoreTPL (OldTpl);

  return Status;
}

/**
  Resets or collects the statistics on a network interface.

  This function resets or collects the statistics on a network interface. If the
  size of the statistics table specified by StatisticsSize is not big enough for
  all the statistics that are collected by the network interface, then a partial
  buffer of statistics is returned in StatisticsTable, StatisticsSize is set to
  the size required to collect all the available statistics, and
  EFI_BUFFER_TOO_SMALL is returned.
  If StatisticsSize is big enough for all the statistics, then StatisticsTable
  will be filled, StatisticsSize will be set to the size of the returned
  StatisticsTable structure, and EFI_SUCCESS is returned.
  If the driver has not been initialized, EFI_DEVICE_ERROR will be returned.
  If Reset is FALSE, and both StatisticsSize and StatisticsTable are NULL, then
  no operations will be performed, and EFI_SUCCESS will be returned.
  If Reset is TRUE, then all of the supported statistics counters on this network
  interface will be reset to zero.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param Reset                   Set to TRUE to reset the statistics for the network interface.
   <at> param StatisticsSize          On input the size, in bytes, of StatisticsTable. On output
                                      the size, in bytes, of the resulting table of statistics.
   <at> param StatisticsTable         A pointer to the EFI_NETWORK_STATISTICS structure that
                                      contains the statistics. Type EFI_NETWORK_STATISTICS is
                                      defined in "Related Definitions" below.

   <at> retval EFI_SUCCESS            The requested operation succeeded.
   <at> retval EFI_NOT_STARTED        The Simple Network Protocol interface has not been
                                      started by calling Start().
   <at> retval EFI_BUFFER_TOO_SMALL   StatisticsSize is not NULL and StatisticsTable is
                                      NULL. The current buffer size that is needed to
                                      hold all the statistics is returned in StatisticsSize.
   <at> retval EFI_BUFFER_TOO_SMALL   StatisticsSize is not NULL and StatisticsTable is
                                      not NULL. The current buffer size that is needed
                                      to hold all the statistics is returned in
                                      StatisticsSize. A partial set of statistics is
                                      returned in StatisticsTable.
   <at> retval EFI_INVALID_PARAMETER  StatisticsSize is NULL and StatisticsTable is not
                                      NULL.
   <at> retval EFI_DEVICE_ERROR       The Simple Network Protocol interface has not
                                      been initialized by calling Initialize().
   <at> retval EFI_DEVICE_ERROR       An error was encountered collecting statistics
                                      from the NIC.
   <at> retval EFI_UNSUPPORTED        The NIC does not support collecting statistics
                                      from the network interface.

**/
EFI_STATUS
EFIAPI
SnpStatistics (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN BOOLEAN                     Reset,
    IN OUT UINTN                   *StatisticsSize, OPTIONAL
    IN OUT EFI_NETWORK_STATISTICS  *StatisticsTable OPTIONAL
    )
{
  EFI_STATUS        Status;
  YUKON_DRIVER        *YukonDriver;
  EFI_TPL           OldTpl;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpStatistics()\n"));
  //
  // Get pointer to SNP driver instance for *This.
  //
  if (This == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  Status = EFI_SUCCESS;
  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  //
  // Return error if the SNP is not initialized.
  //
  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_EXIT;

    case EfiSimpleNetworkStarted:
    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_EXIT;
  }

  //
  // Error Checking
  //

  if (!Reset && (StatisticsSize == NULL) && (StatisticsTable == NULL)) {
    Status = EFI_SUCCESS;
    goto ON_EXIT;
  }

  if ((StatisticsSize == NULL) && (StatisticsTable != NULL)) {
    Status = EFI_INVALID_PARAMETER;
    goto ON_EXIT;
  }

  if ((StatisticsSize != NULL) && (StatisticsTable == NULL)) {
    *StatisticsSize = sizeof (EFI_NETWORK_STATISTICS);
    Status = EFI_BUFFER_TOO_SMALL;
    goto ON_EXIT;
  }

  if ((StatisticsSize != NULL) && (StatisticsTable != NULL)) {
    if (*StatisticsSize < sizeof (EFI_NETWORK_STATISTICS)) {
      if (*StatisticsSize == 0) {
        Status = EFI_BUFFER_TOO_SMALL;
        // Note: From here on, the Status value must be preserved.
      } else {
        // FixMe: Return partial statistics for the available size and also set
        //        Status = EFI_BUFFER_TOO_SMALL;
        //        but for now it is unsupported.
        Status = EFI_UNSUPPORTED;
        // Note: From here on, the Status value must be preserved.
      }
      *StatisticsSize = sizeof (EFI_NETWORK_STATISTICS);
    } else {
      // FixMe: Return full statistics and also set
      //        Status = EFI_SUCCESS;
      //        but for now it is unsupported.
      Status = EFI_UNSUPPORTED;
    }
  }

  if (Reset == TRUE) {
    // FixMe: Reset all statistics;

    // Preserve any previous errors else return success.
    if (!EFI_ERROR (Status)) {
      // FixMe: Should return success
      //        Status = EFI_SUCCESS;
      //        but for now it is unsupported.
      Status = EFI_UNSUPPORTED;
    }
  }

ON_EXIT:
  gBS->RestoreTPL (OldTpl);

  return Status;
}

/**
  Changes the state of a network interface from "started" to "stopped."

  This function stops a network interface. This call is only valid if the network
  interface is in the started state. If the network interface was successfully
  stopped, then EFI_SUCCESS will be returned.

   <at> param  This                   A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL
                                                                  instance.


   <at> retval EFI_SUCCESS            The network interface was stopped.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_INVALID_PARAMETER  This parameter was NULL or did not point to a
                                      valid EFI_SIMPLE_NETWORK_PROTOCOL structure.
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network
                                      interface.
   <at> retval EFI_UNSUPPORTED        This function is not supported by the network
                                      interface.

**/
EFI_STATUS
EFIAPI
SnpStop (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;
  struct msk_softc *ScData;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpStop()\n"));
  if (This == NULL) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpStop() failed with Status = %r\n", EFI_INVALID_PARAMETER));
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  Status = MarvellYukonGetControllerData (YukonDriver->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkStarted:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_ERROR_RESTORE_TPL;

    case EfiSimpleNetworkInitialized:
    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_ERROR_RESTORE_TPL;
  }

  mskc_detach_if (ScData->msk_if[YukonDriver->Port]);
  YukonDriver->SnpMode.State = EfiSimpleNetworkStopped;
  gBS->SetMem (&YukonDriver->SnpMode.CurrentAddress, sizeof (EFI_MAC_ADDRESS), 0);
  Status = EFI_SUCCESS;
  goto ON_EXIT;

ON_ERROR_RESTORE_TPL:
  DEBUG ((EFI_D_ERROR, "Marvell Yukon: SnpStop() failed with Status = %r\n", Status));

ON_EXIT:
  gBS->RestoreTPL (OldTpl);
  return Status;
}

/**
  Places a packet in the transmit queue of a network interface.

  This function places the packet specified by Header and Buffer on the transmit
  queue. If HeaderSize is nonzero and HeaderSize is not equal to
  This->SnpMode->MediaHeaderSize, then EFI_INVALID_PARAMETER will be returned. If
  BufferSize is less than This->SnpMode->MediaHeaderSize, then EFI_BUFFER_TOO_SMALL
  will be returned. If Buffer is NULL, then EFI_INVALID_PARAMETER will be
  returned. If HeaderSize is nonzero and DestAddr or Protocol is NULL, then
  EFI_INVALID_PARAMETER will be returned. If the transmit engine of the network
  interface is busy, then EFI_NOT_READY will be returned. If this packet can be
  accepted by the transmit engine of the network interface, the packet contents
  specified by Buffer will be placed on the transmit queue of the network
  interface, and EFI_SUCCESS will be returned. GetStatus() can be used to
  determine when the packet has actually been transmitted. The contents of the
  Buffer must not be modified until the packet has actually been transmitted.
  The Transmit() function performs nonblocking I/O. A caller who wants to perform
  blocking I/O, should call Transmit(), and then GetStatus() until the
  transmitted buffer shows up in the recycled transmit buffer.
  If the driver has not been initialized, EFI_DEVICE_ERROR will be returned.

   <at> param This                    A pointer to the EFI_SIMPLE_NETWORK_PROTOCOL instance.
   <at> param HeaderSize              The size, in bytes, of the media header to be filled in by the
                                      Transmit() function. If HeaderSize is nonzero, then it must
                                      be equal to This->SnpMode->MediaHeaderSize and the DestAddr and
                                      Protocol parameters must not be NULL.
   <at> param BufferSize              The size, in bytes, of the entire packet (media header and
                                      data) to be transmitted through the network interface.
   <at> param Buffer                  A pointer to the packet (media header followed by data) to be
                                      transmitted. This parameter cannot be NULL. If HeaderSize is
                                      zero, then the media header in Buffer must already be filled
                                      in by the caller. If HeaderSize is nonzero, then the media
                                      header will be filled in by the Transmit() function.
   <at> param SrcAddr                 The source HW MAC address. If HeaderSize is zero, then this
                                      parameter is ignored. If HeaderSize is nonzero and SrcAddr
                                      is NULL, then This->SnpMode->CurrentAddress is used for the
                                      source HW MAC address.
   <at> param DestAddr                The destination HW MAC address. If HeaderSize is zero, then
                                      this parameter is ignored.
   <at> param Protocol                The type of header to build. If HeaderSize is zero, then this
                                      parameter is ignored. See RFC 1700, section "Ether Types,"
                                      for examples.

   <at> retval EFI_SUCCESS            The packet was placed on the transmit queue.
   <at> retval EFI_NOT_STARTED        The network interface has not been started.
   <at> retval EFI_NOT_READY          The network interface is too busy to accept this
                                      transmit request.
   <at> retval EFI_BUFFER_TOO_SMALL   The BufferSize parameter is too small.
   <at> retval EFI_INVALID_PARAMETER  One or more of the parameters has an unsupported
                                      value.
   <at> retval EFI_DEVICE_ERROR       The command could not be sent to the network interface.
   <at> retval EFI_UNSUPPORTED        This function is not supported by the network interface.

**/
EFI_STATUS
EFIAPI
SnpTransmit (
    IN EFI_SIMPLE_NETWORK_PROTOCOL *This,
    IN UINTN                       HeaderSize,
    IN UINTN                       BufferSize,
    IN VOID                        *Buffer,
    IN EFI_MAC_ADDRESS             *SrcAddr,  OPTIONAL
    IN EFI_MAC_ADDRESS             *DestAddr, OPTIONAL
    IN UINT16                      *Protocol  OPTIONAL
    )
{
  EFI_STATUS    Status;
  YUKON_DRIVER    *YukonDriver;
  EFI_TPL       OldTpl;
  ETHER_HEAD    *Frame;
  UINT16        ProtocolNet;
  struct msk_softc  *ScData;

  DEBUG ((EFI_D_NET, "Marvell Yukon: SnpTransmit()\n"));
  if (This == NULL) {
    return EFI_INVALID_PARAMETER;
  }

  YukonDriver = YUKON_DEV_FROM_THIS_SNP (This);

  if (YukonDriver == NULL) {
    return EFI_DEVICE_ERROR;
  }

  Status = MarvellYukonGetControllerData (YukonDriver->Controller, &ScData);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  OldTpl = gBS->RaiseTPL (TPL_CALLBACK);

  switch (YukonDriver->SnpMode.State) {
    case EfiSimpleNetworkInitialized:
      break;

    case EfiSimpleNetworkStopped:
      Status = EFI_NOT_STARTED;
      goto ON_EXIT;

    default:
      Status = EFI_DEVICE_ERROR;
      goto ON_EXIT;
  }

  if (Buffer == NULL) {
    Status = EFI_INVALID_PARAMETER;
    goto ON_EXIT;
  }

  if (BufferSize < YukonDriver->SnpMode.MediaHeaderSize) {
    Status = EFI_BUFFER_TOO_SMALL;
    goto ON_EXIT;
  }

  //
  // Construct the frame header if not already presented
  //
  if (HeaderSize != 0) {
    if (HeaderSize != YukonDriver->SnpMode.MediaHeaderSize || DestAddr == 0 || Protocol == 0) {
      Status = EFI_INVALID_PARAMETER;
      goto ON_EXIT;
    }
    Frame       = (ETHER_HEAD*)Buffer;
    ProtocolNet = NTOHS (*Protocol);

    gBS->CopyMem (Frame->SrcMac,     SrcAddr,      NET_ETHER_ADDR_LEN);
    gBS->CopyMem (Frame->DstMac,     DestAddr,     NET_ETHER_ADDR_LEN);
    gBS->CopyMem (&Frame->EtherType, &ProtocolNet, sizeof (UINT16));
  }

  Status = mskc_transmit (ScData->msk_if[YukonDriver->Port], BufferSize, Buffer);

ON_EXIT:
  gBS->RestoreTPL (OldTpl);

  return Status;
}

