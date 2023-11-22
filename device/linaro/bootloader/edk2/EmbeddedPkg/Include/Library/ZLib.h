/** @file

  Copyright (c) 2013-2014, ARM Ltd. All rights reserved.<BR>
  Copyright (c) 2017, Linaro.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#ifndef __ZLIB_H__
#define __ZLIB_H__

#include <Uefi/UefiBaseType.h>

EFI_STATUS
GzipDecompress (
  IN     VOID              *CompressData,
  IN     UINTN             *CompressDataLen,
  IN     VOID              *UncompressData,
  IN OUT UINTN             *UncompressDataLen
  );

#endif /* __ZLIB_H__ */
