/** @file

  Copyright (c) 2013-2014, ARM Ltd. All rights reserved.<BR>
  Copyright (c) 2017, Linaro. All rights reserved.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include <Library/AbootimgLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/BdsLib.h>
#include <Library/DebugLib.h>
#include <Library/DevicePathLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/UefiBootServicesTableLib.h>

#include <Protocol/BlockIo.h>
#include <Protocol/DevicePathFromText.h>

#define IS_DEVICE_PATH_NODE(node,type,subtype) (((node)->Type == (type)) && ((node)->SubType == (subtype)))

#define ALIGN(x, a)        (((x) + ((a) - 1)) & ~((a) - 1))

EFI_STATUS
EFIAPI
AndroidBootAppEntryPoint (
  IN EFI_HANDLE                            ImageHandle,
  IN EFI_SYSTEM_TABLE                      *SystemTable
  )
{
  EFI_STATUS                          Status;
  CHAR16                              *BootPathStr;

  BootPathStr = (CHAR16 *)PcdGetPtr (PcdAndroidBootDevicePath);
  Status = AbootimgBootPartition (BootPathStr, NULL);

  return Status;
}
