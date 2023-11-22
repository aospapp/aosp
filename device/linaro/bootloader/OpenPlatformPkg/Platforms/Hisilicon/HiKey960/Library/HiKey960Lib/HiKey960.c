/** @file
*
*  Copyright (c) 2016-2017, Linaro Limited. All rights reserved.
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

#include <Drivers/PL011Uart.h>

#include <Library/IoLib.h>
#include <Library/ArmPlatformLib.h>
#include <Library/DebugLib.h>
#include <Library/PcdLib.h>

#include <Ppi/ArmMpCoreInfo.h>

ARM_CORE_INFO mHiKey960InfoTable[] = {
  {
    // Cluster 0, Core 0
    0x0, 0x0,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  },
  {
    // Cluster 0, Core 1
    0x0, 0x1,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  },
  {
    // Cluster 0, Core 2
    0x0, 0x2,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  },
  {
    // Cluster 0, Core 3
    0x0, 0x3,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  },
  {
    // Cluster 1, Core 0
    0x1, 0x0,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  },
  {
    // Cluster 1, Core 1
    0x1, 0x1,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  },
  {
    // Cluster 1, Core 2
    0x1, 0x2,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  },
  {
    // Cluster 1, Core 3
    0x1, 0x3,

    // MP Core MailBox Set/Get/Clear Addresses and Clear Value
    (UINT64)0xFFFFFFFF
  }
};

/**
  Return the current Boot Mode

  This function returns the boot reason on the platform

  @return   Return the current Boot Mode of the platform

**/
EFI_BOOT_MODE
ArmPlatformGetBootMode (
  VOID
  )
{
  return BOOT_WITH_FULL_CONFIGURATION;
}

/**
  Initialize controllers that must setup in the normal world

  This function is called by the ArmPlatformPkg/Pei or ArmPlatformPkg/Pei/PlatformPeim
  in the PEI phase.

**/
RETURN_STATUS
ArmPlatformInitialize (
  IN  UINTN                     MpId
  )
{
  RETURN_STATUS       Status;
  UINT64              BaudRate;
  UINT32              ReceiveFifoDepth;
  EFI_PARITY_TYPE     Parity;
  UINT8               DataBits;
  EFI_STOP_BITS_TYPE  StopBits;

  Status = RETURN_SUCCESS;

  //
  // Initialize the Serial Debug UART
  //
  if (FixedPcdGet64 (PcdSerialDbgRegisterBase)) {
    ReceiveFifoDepth = 0; // Use the default value for FIFO depth
    Parity = (EFI_PARITY_TYPE)FixedPcdGet8 (PcdUartDefaultParity);
    DataBits = FixedPcdGet8 (PcdUartDefaultDataBits);
    StopBits = (EFI_STOP_BITS_TYPE)FixedPcdGet8 (PcdUartDefaultStopBits);

    BaudRate = (UINTN)FixedPcdGet64 (PcdSerialDbgUartBaudRate);
    Status = PL011UartInitializePort (
               (UINTN)FixedPcdGet64 (PcdSerialDbgRegisterBase),
               FixedPcdGet32 (PcdSerialDbgUartClkInHz),
               &BaudRate,
               &ReceiveFifoDepth,
               &Parity,
               &DataBits,
               &StopBits
               );
  }

  return Status;
}

/**
  Initialize the system (or sometimes called permanent) memory

  This memory is generally represented by the DRAM.

**/
VOID
ArmPlatformInitializeSystemMemory (
  VOID
  )
{
}

EFI_STATUS
PrePeiCoreGetMpCoreInfo (
  OUT UINTN                   *CoreCount,
  OUT ARM_CORE_INFO           **ArmCoreTable
  )
{
  // Only support one cluster
  *CoreCount    = sizeof(mHiKey960InfoTable) / sizeof(ARM_CORE_INFO);
  *ArmCoreTable = mHiKey960InfoTable;
  return EFI_SUCCESS;
}

// Needs to be declared in the file. Otherwise gArmMpCoreInfoPpiGuid is undefined in the contect of PrePeiCore
EFI_GUID mArmMpCoreInfoPpiGuid = ARM_MP_CORE_INFO_PPI_GUID;
ARM_MP_CORE_INFO_PPI mMpCoreInfoPpi = { PrePeiCoreGetMpCoreInfo };

EFI_PEI_PPI_DESCRIPTOR      gPlatformPpiTable[] = {
  {
    EFI_PEI_PPI_DESCRIPTOR_PPI,
    &mArmMpCoreInfoPpiGuid,
    &mMpCoreInfoPpi
  }
};

VOID
ArmPlatformGetPlatformPpiList (
  OUT UINTN                   *PpiListSize,
  OUT EFI_PEI_PPI_DESCRIPTOR  **PpiList
  )
{
  *PpiListSize = sizeof(gPlatformPpiTable);
  *PpiList = gPlatformPpiTable;
}
