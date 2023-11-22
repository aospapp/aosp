/** @file

  Copyright (c) 2017, Linaro. All rights reserved.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#ifndef __USB_SERIAL_NUMBER_LIB_H__
#define __USB_SERIAL_NUMBER_LIB_H__

#include <Uefi.h>

#define SERIAL_NUMBER_SIZE                      17

typedef struct {
  UINT64        Magic;
  UINT64        Data;
  CHAR16        UnicodeSN[SERIAL_NUMBER_SIZE];
} RANDOM_SERIAL_NUMBER;

EFI_STATUS
GenerateUsbSNBySeed (
  IN  UINT32                  Seed,
  OUT RANDOM_SERIAL_NUMBER   *RandomSN
  );

EFI_STATUS
GenerateUsbSN (
  OUT CHAR16                 *UnicodeSN
  );

EFI_STATUS
LoadSNFromBlock (
  IN  EFI_HANDLE              FlashHandle,
  IN  EFI_LBA                 Lba,
  OUT CHAR16                 *UnicodeSN
  );

EFI_STATUS
StoreSNToBlock (
  IN EFI_HANDLE               FlashHandle,
  IN EFI_LBA                  Lba,
  IN CHAR16                  *UnicodeSN
  );

#endif /* __USB_SERIAL_NUMBER_LIB_H__ */
