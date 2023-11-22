/** @file

  Copyright (c) 2017, Linaro. All rights reserved.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include <Uefi.h>

#include <Library/ArmGenericTimerCounterLib.h>
#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/DebugLib.h>
#include <Library/DevicePathLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/PrintLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UsbSerialNumberLib.h>

#include <Protocol/BlockIo.h>
#include <Protocol/DevicePath.h>


#define SERIAL_NUMBER_SIZE               17

#define RANDOM_MAX                       0x7FFFFFFFFFFFFFFF
#define RANDOM_MAGIC                     0x9A4DBEAF

STATIC
EFI_STATUS
GenerateRandomData (
  IN  UINT32              Seed,
  OUT UINT64             *RandomData
  )
{
  INT64                   Quotient, Remainder, Tmp;

  if (RandomData == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  Quotient = (INT64) Seed / 127773;
  Remainder = (INT64) Seed % 127773;
  Tmp = (16807 * Remainder) - (2836 * Quotient);
  if (Tmp < 0) {
    Tmp += RANDOM_MAX;
  }
  Tmp = Tmp % ((UINT64)RANDOM_MAX + 1);
  *RandomData = (UINT64)Tmp;
  return EFI_SUCCESS;
}

EFI_STATUS
GenerateUsbSNBySeed (
  IN  UINT32                  Seed,
  OUT RANDOM_SERIAL_NUMBER   *RandomSN
  )
{
  EFI_STATUS               Status;
  UINT64                   Tmp;

  if (RandomSN == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  ZeroMem (RandomSN, sizeof (RANDOM_SERIAL_NUMBER));
  Status = GenerateRandomData (Seed, &Tmp);
  if (EFI_ERROR (Status)) {
    return Status;
  }
  RandomSN->Data = (Tmp << 32) | Seed;
  UnicodeSPrint (RandomSN->UnicodeSN, SERIAL_NUMBER_SIZE * sizeof (CHAR16), L"%lx", RandomSN->Data);
  RandomSN->Magic = RANDOM_MAGIC;
  return EFI_SUCCESS;
}

EFI_STATUS
GenerateUsbSN (
  OUT CHAR16                  *UnicodeSN
  )
{
  EFI_STATUS               Status;
  UINT64                   Tmp;
  UINT32                   Seed;
  RANDOM_SERIAL_NUMBER     RandomSN;

  if (UnicodeSN == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  ZeroMem (&RandomSN, sizeof (RANDOM_SERIAL_NUMBER));
  Seed = ArmGenericTimerGetSystemCount ();
  Status = GenerateRandomData (Seed, &Tmp);
  if (EFI_ERROR (Status)) {
    return Status;
  }
  RandomSN.Data = (Tmp << 32) | Seed;
  UnicodeSPrint (RandomSN.UnicodeSN, SERIAL_NUMBER_SIZE * sizeof (CHAR16), L"%lx", RandomSN.Data);
  StrCpyS (UnicodeSN, SERIAL_NUMBER_SIZE * sizeof (CHAR16), RandomSN.UnicodeSN);
  return EFI_SUCCESS;
}

EFI_STATUS
LoadSNFromBlock (
  IN  EFI_HANDLE              FlashHandle,
  IN  EFI_LBA                 Lba,
  OUT CHAR16                 *UnicodeSN
  )
{
  EFI_STATUS                  Status;
  EFI_BLOCK_IO_PROTOCOL      *BlockIoProtocol;
  VOID                       *DataPtr;
  BOOLEAN                     Found = FALSE;
  UINT32                      Seed;
  RANDOM_SERIAL_NUMBER       *RandomSN;
  UINTN                       NumPages;
  CHAR16                      UnicodeStr[SERIAL_NUMBER_SIZE];

  if (UnicodeSN == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  Status = gBS->OpenProtocol (
                  FlashHandle,
                  &gEfiBlockIoProtocolGuid,
                  (VOID **) &BlockIoProtocol,
                  gImageHandle,
                  NULL,
                  EFI_OPEN_PROTOCOL_GET_PROTOCOL
                  );
  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_WARN, "Warning: Couldn't open block device (status: %r)\n", Status));
    return EFI_DEVICE_ERROR;
  }

  NumPages = EFI_SIZE_TO_PAGES (BlockIoProtocol->Media->BlockSize);
  DataPtr = AllocatePages (NumPages);
  if (DataPtr == NULL) {
    return EFI_BUFFER_TOO_SMALL;
  }
  Status = BlockIoProtocol->ReadBlocks (
                              BlockIoProtocol,
                              BlockIoProtocol->Media->MediaId,
                              Lba,
                              BlockIoProtocol->Media->BlockSize,
                              DataPtr
                              );
  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_WARN, "Warning: Failed on reading blocks\n"));
    goto Exit;
  }

  Seed = ArmGenericTimerGetSystemCount ();
  RandomSN = (RANDOM_SERIAL_NUMBER *)DataPtr;
  if (RandomSN->Magic == RANDOM_MAGIC) {
    Found = TRUE;
    // Verify the unicode string.
    ZeroMem (UnicodeStr, SERIAL_NUMBER_SIZE * sizeof (CHAR16));
    UnicodeSPrint (UnicodeStr, SERIAL_NUMBER_SIZE * sizeof (CHAR16), L"%lx", RandomSN->Data);
    if (StrLen (RandomSN->UnicodeSN) != StrLen (UnicodeStr)) {
      Found = FALSE;
    }
    if (StrnCmp (RandomSN->UnicodeSN, UnicodeStr, StrLen (UnicodeStr)) != 0) {
      Found = FALSE;
    }
  }
  if (Found == FALSE) {
    Status = GenerateUsbSNBySeed (Seed, RandomSN);
    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_WARN, "Warning: Failed to generate serial number\n"));
      goto Exit;
    }
    // Update SN to block device
    Status = BlockIoProtocol->WriteBlocks (
                                BlockIoProtocol,
                                BlockIoProtocol->Media->MediaId,
                                Lba,
                                BlockIoProtocol->Media->BlockSize,
                                DataPtr
                                );
    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_WARN, "Warning: Failed on writing blocks\n"));
      goto Exit;
    }
  }
  CopyMem (UnicodeSN, RandomSN->UnicodeSN, SERIAL_NUMBER_SIZE * sizeof (CHAR16));
Exit:
  FreePages (DataPtr, NumPages);
  return Status;
}

EFI_STATUS
StoreSNToBlock (
  IN EFI_HANDLE               FlashHandle,
  IN EFI_LBA                  Lba,
  IN CHAR16                  *UnicodeSN
  )
{
  EFI_STATUS                  Status;
  EFI_BLOCK_IO_PROTOCOL      *BlockIoProtocol;
  VOID                       *DataPtr;
  UINTN                       NumPages;
  RANDOM_SERIAL_NUMBER       *RandomSN;
  CHAR16                      UnicodeStr[SERIAL_NUMBER_SIZE];

  if (UnicodeSN == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  Status = gBS->OpenProtocol (
                  FlashHandle,
                  &gEfiBlockIoProtocolGuid,
                  (VOID **) &BlockIoProtocol,
                  gImageHandle,
                  NULL,
                  EFI_OPEN_PROTOCOL_GET_PROTOCOL
                  );
  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_WARN, "Warning: Couldn't open block device (status: %r)\n", Status));
    return EFI_DEVICE_ERROR;
  }
  NumPages = EFI_SIZE_TO_PAGES (BlockIoProtocol->Media->BlockSize);
  DataPtr = AllocatePages (NumPages);
  if (DataPtr == NULL) {
    return EFI_BUFFER_TOO_SMALL;
  }
  ZeroMem (DataPtr, BlockIoProtocol->Media->BlockSize);
  RandomSN = (RANDOM_SERIAL_NUMBER *)DataPtr;
  RandomSN->Magic = RANDOM_MAGIC;
  StrnCpyS (RandomSN->UnicodeSN, SERIAL_NUMBER_SIZE * sizeof (CHAR16), UnicodeSN, StrSize (UnicodeSN));
  RandomSN->Data = StrHexToUint64 (RandomSN->UnicodeSN);

  // Verify the unicode string.
  ZeroMem (UnicodeStr, SERIAL_NUMBER_SIZE * sizeof (CHAR16));
  UnicodeSPrint (UnicodeStr, SERIAL_NUMBER_SIZE * sizeof (CHAR16), L"%lx", RandomSN->Data);
  if (StrLen (RandomSN->UnicodeSN) != StrLen (UnicodeStr)) {
    Status = EFI_INVALID_PARAMETER;
    goto Exit;
  }
  if (StrnCmp (RandomSN->UnicodeSN, UnicodeStr, StrLen (UnicodeStr)) != 0) {
    Status = EFI_INVALID_PARAMETER;
    goto Exit;
  }

  Status = BlockIoProtocol->WriteBlocks (
                              BlockIoProtocol,
                              BlockIoProtocol->Media->MediaId,
                              Lba,
                              BlockIoProtocol->Media->BlockSize,
                              DataPtr
                              );
  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_WARN, "Warning: Failed on writing blocks\n"));
    goto Exit;
  }
Exit:
  FreePages (DataPtr, NumPages);
  return Status;
}
