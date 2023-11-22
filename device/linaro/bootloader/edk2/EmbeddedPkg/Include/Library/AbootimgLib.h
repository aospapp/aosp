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

#ifndef __ABOOTIMG_H__
#define __ABOOTIMG_H__

#include <Library/BaseLib.h>
#include <Library/DebugLib.h>
#include <Library/MemoryAllocationLib.h>

#include <Uefi/UefiBaseType.h>
#include <Uefi/UefiSpec.h>

#define BOOTIMG_KERNEL_ARGS_SIZE          512

#define BOOT_MAGIC                        "ANDROID!"
#define BOOT_MAGIC_LENGTH                 (sizeof (BOOT_MAGIC) - 1)

/* It's the value of arm64 efi stub kernel */
#define KERNEL_IMAGE_STEXT_OFFSET         0x12C
#define KERNEL_IMAGE_RAW_SIZE_OFFSET      0x130

#define FDT_SIZE_OFFSET                   0x4

typedef struct {
  CHAR8   BootMagic[BOOT_MAGIC_LENGTH];
  UINT32  KernelSize;
  UINT32  KernelAddress;
  UINT32  RamdiskSize;
  UINT32  RamdiskAddress;
  UINT32  SecondStageBootloaderSize;
  UINT32  SecondStageBootloaderAddress;
  UINT32  KernelTaggsAddress;
  UINT32  PageSize;
  UINT32  Reserved[2];
  CHAR8   ProductName[16];
  CHAR8   KernelArgs[BOOTIMG_KERNEL_ARGS_SIZE];
  UINT32  Id[32];
} ANDROID_BOOTIMG_HEADER;

EFI_STATUS
AbootimgGetImgSize (
  IN  VOID    *BootImg,
  OUT UINTN   *ImgSize
  );

EFI_STATUS
AbootimgBootRam (
  IN VOID                   *Buffer,
  IN UINTN                   BufferSize,
  IN CHAR16                 *BootPathStr,
  IN CHAR16                 *FdtPathStr
  );

EFI_STATUS
AbootimgBootPartition (
  IN CHAR16                 *BootPathStr,
  IN CHAR16                 *FdtPathStr
  );

#endif /* __ABOOTIMG_H__ */
