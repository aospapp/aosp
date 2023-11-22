/** @file

  Copyright (c) 2017, Linaro. All rights reserved.<BR>

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#ifndef __ABOOTIMG_PROTOCOL_H__
#define __ABOOTIMG_PROTOCOL_H__

//
// Protocol interface structure
//
typedef struct _ABOOTIMG_PROTOCOL    ABOOTIMG_PROTOCOL;

//
// Function Prototypes
//
typedef
EFI_STATUS
(EFIAPI *ABOOTIMG_APPEND_KERNEL_ARGS) (
  IN CHAR16            *Args,
  IN UINTN              Size
  );

typedef
EFI_STATUS
(EFIAPI *ABOOTIMG_UPDATE_DTB) (
  IN  EFI_PHYSICAL_ADDRESS    OrigDtbBase;
  OUT EFI_PHYSICAL_ADDRESS   *NewDtbBase;
  );

struct _ABOOTIMG_PROTOCOL {
  ABOOTIMG_APPEND_KERNEL_ARGS        AppendArgs;
  ABOOTIMG_UPDATE_DTB                UpdateDtb;
};

extern EFI_GUID gAbootimgProtocolGuid;

#endif /* __ABOOTIMG_PROTOCOL_H__ */
