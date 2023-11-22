/** @file
*
*  Copyright (c) 2016 Hisilicon Limited. All rights reserved.
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

//
// LPC
//

Device (LPC0)
{
  Name(_HID, "HISI0191")  // HiSi LPC
  Name (_CRS, ResourceTemplate () {
    Memory32Fixed (ReadWrite, 0xa01b0000, 0x1000)
  })
}
