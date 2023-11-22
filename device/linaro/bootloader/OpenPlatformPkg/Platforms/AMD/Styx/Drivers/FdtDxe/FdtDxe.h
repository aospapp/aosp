/** @file

  Copyright (c) 2014 - 2016, AMD Inc. All rights reserved.<BR>

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#ifndef __FDT_DXE__H_
#define __FDT_DXE__H_

#include <Uefi.h>
#include <Library/UefiLib.h>
#include <Library/UefiDriverEntryPoint.h>

#include <Library/BaseLib.h>
#include <Library/BdsLib.h>
#include <Library/PcdLib.h>
#include <Library/DebugLib.h>
#include <Library/DevicePathLib.h>
#include <Guid/DxeServices.h>
#include <Library/DxeServicesTableLib.h>

#include <Protocol/FirmwareVolume2.h>
#include <Protocol/SimpleFileSystem.h>
#include <Protocol/LoadFile.h>
#include <Protocol/DevicePath.h>
#include <Protocol/DevicePathFromText.h>

#define LINUX_FDT_MAX_OFFSET      (PcdGet64 (PcdSystemMemoryBase) + PcdGet32(PcdArmLinuxFdtMaxOffset))

VOID
EFIAPI
AmdStyxParkSecondaryCores(
  VOID
  );

EFI_STATUS
AmdStyxPrepareFdt (
  IN     CONST CHAR8*         CommandLineArguments,
  IN     EFI_PHYSICAL_ADDRESS InitrdImage,
  IN     UINTN                InitrdImageSize,
  IN OUT EFI_PHYSICAL_ADDRESS *FdtBlobBase,
  IN OUT UINTN                *FdtBlobSize
  );


#endif // __FDT_DXE__H_
