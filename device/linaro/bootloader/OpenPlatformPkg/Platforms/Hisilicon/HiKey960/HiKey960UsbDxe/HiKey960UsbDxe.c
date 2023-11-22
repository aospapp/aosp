/** @file
*
*  Copyright (c) 2017, Linaro. All rights reserved.
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

#include <Library/BaseMemoryLib.h>
#include <Library/DebugLib.h>
#include <Library/DevicePathLib.h>
#include <Library/IoLib.h>
#include <Library/TimerLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiLib.h>
#include <Library/UefiRuntimeServicesTableLib.h>
#include <Library/UsbSerialNumberLib.h>

#include <Protocol/BlockIo.h>
#include <Protocol/DwUsb.h>

#include <Hi3660.h>

#define USB_EYE_PARAM                  0x01c466e3
#define USB_PHY_TX_VBOOST_LVL          5

#define DWC3_PHY_RX_OVRD_IN_HI         0x1006
#define DWC3_PHY_RX_SCOPE_VDCC         0x1026

#define RX_SCOPE_LFPS_EN               (1 << 0)

#define TX_VBOOST_LVL_MASK             7
#define TX_VBOOST_LVL(x)               ((x) & TX_VBOOST_LVL_MASK)

#define SERIAL_NUMBER_LBA              20

STATIC EFI_HANDLE mFlashHandle;

UINTN
HiKey960GetUsbMode (
  IN VOID
  )
{
  return USB_DEVICE_MODE;
}

VOID
HiKey960UsbPhyCrWaitAck (
  VOID
  )
{
  INT32                Timeout = 1000;
  UINT32               Data;

  while (TRUE) {
    Data = MmioRead32 (USB3OTG_PHY_CR_STS);
    if ((Data & USB3OTG_PHY_CR_ACK) == USB3OTG_PHY_CR_ACK) {
      return;
    }
    MicroSecondDelay (50);
    if (Timeout-- <= 0) {
      DEBUG ((DEBUG_ERROR, "Wait PHY_CR_ACK timeout!\n"));
      return;
    }
  }
}

VOID
HiKey960UsbPhyCrSetAddr (
  IN UINT32            Addr
  )
{
  // set addr
  MmioWrite32 (
    USB3OTG_PHY_CR_CTRL,
    USB3OTG_PHY_CR_DATA_IN (Addr)
    );
  MicroSecondDelay (100);
  // cap addr
  MmioOr32 (USB3OTG_PHY_CR_CTRL, USB3OTG_PHY_CR_CAP_ADDR);
  HiKey960UsbPhyCrWaitAck ();
  MmioWrite32 (USB3OTG_PHY_CR_CTRL, 0);
}

UINT16
HiKey960UsbPhyCrRead (
  IN UINT32            Addr
  )
{
  INT32                Timeout = 1000;
  UINT32               Data;

  HiKey960UsbPhyCrSetAddr (Addr);
  MmioWrite32 (USB3OTG_PHY_CR_CTRL, USB3OTG_PHY_CR_READ);
  MicroSecondDelay (100);

  while (TRUE) {
    Data = MmioRead32 (USB3OTG_PHY_CR_STS);
    if ((Data & USB3OTG_PHY_CR_ACK) == USB3OTG_PHY_CR_ACK) {
      DEBUG ((
        DEBUG_INFO,
        "Addr 0x%x, Data Out:0x%x\n",
        Addr,
        USB3OTG_PHY_CR_DATA_OUT(Data)
        ));
      break;
    }
    MicroSecondDelay (50);
    if (Timeout-- <= 0) {
      DEBUG ((DEBUG_ERROR, "Wait PHY_CR_ACK timeout!\n"));
      break;
    }
  }
  MmioWrite32 (USB3OTG_PHY_CR_CTRL, 0);
  return (UINT16)USB3OTG_PHY_CR_DATA_OUT(Data);
}

VOID
HiKey960UsbPhyCrWrite (
  IN UINT32            Addr,
  IN UINT32            Value
  )
{
  UINT32               Data;

  HiKey960UsbPhyCrSetAddr (Addr);
  Data = USB3OTG_PHY_CR_DATA_IN(Value);
  MmioWrite32 (USB3OTG_PHY_CR_CTRL, Data);

  Data = MmioRead32 (USB3OTG_PHY_CR_CTRL);
  Data |= USB3OTG_PHY_CR_CAP_DATA;
  MmioWrite32 (USB3OTG_PHY_CR_CTRL, Data);
  HiKey960UsbPhyCrWaitAck ();

  MmioWrite32 (USB3OTG_PHY_CR_CTRL, 0);
  MmioWrite32 (USB3OTG_PHY_CR_CTRL, USB3OTG_PHY_CR_WRITE);
  HiKey960UsbPhyCrWaitAck ();
}

VOID
HiKey960UsbSetEyeDiagramParam (
  VOID
  )
{
  UINT32               Data;

  /* set eye diagram for usb 2.0 */
  MmioWrite32 (USB3OTG_CTRL4, USB_EYE_PARAM);
  /* set eye diagram for usb 3.0 */
  HiKey960UsbPhyCrRead (DWC3_PHY_RX_OVRD_IN_HI);
  HiKey960UsbPhyCrWrite (DWC3_PHY_RX_OVRD_IN_HI, BIT11 | BIT9 | BIT8 |BIT7);
  HiKey960UsbPhyCrRead (DWC3_PHY_RX_OVRD_IN_HI);

  /* enable RX_SCOPE_LFPS_EN for usb 3.0 */
  Data = HiKey960UsbPhyCrRead (DWC3_PHY_RX_SCOPE_VDCC);
  Data |= RX_SCOPE_LFPS_EN;
  HiKey960UsbPhyCrWrite (DWC3_PHY_RX_SCOPE_VDCC, Data);
  HiKey960UsbPhyCrRead (DWC3_PHY_RX_SCOPE_VDCC);

  Data = MmioRead32 (USB3OTG_CTRL6);
  Data &= ~TX_VBOOST_LVL_MASK;
  Data = TX_VBOOST_LVL (USB_PHY_TX_VBOOST_LVL);
  MmioWrite32 (USB3OTG_CTRL6, Data);
  DEBUG ((
    DEBUG_INFO,
    "set ss phy tx vboost lvl 0x%x\n",
    MmioRead32 (USB3OTG_CTRL6)
    ));
}

STATIC
VOID
HiKey960UsbReset (
  VOID
  )
{
  MmioWrite32 (CRG_PERRSTEN4, PERRSTEN4_USB3OTG);
  MmioWrite32 (CRG_PERRSTEN4, PERRSTEN4_USB3OTGPHY_POR);
  MmioWrite32 (
    CRG_PERRSTEN4,
    PERRSTEN4_USB3OTG_MUX | PERRSTEN4_USB3OTG_AHBIF | PERRSTEN4_USB3OTG_32K
    );
  MmioWrite32 (
    CRG_PERDIS4,
    PEREN4_GT_ACLK_USB3OTG | PEREN4_GT_CLK_USB3OTG_REF
    );

  MmioAnd32 (PCTRL_CTRL24, ~PCTRL_CTRL24_USB3PHY_3MUX1_SEL);
  // disable
  MmioWrite32 (PCTRL_CTRL3, (PCTRL_CTRL3_USB_TXCO_EN << 16) | 0);
  MicroSecondDelay (10000);
}

STATIC
VOID
HiKey960UsbRelease (
  VOID
  )
{
  /* enable USB REFCLK ISO */
  MmioWrite32 (CRG_ISODIS, PERISOEN_USB_REFCLK_ISO_EN);
  /* enable USB_TXCO_EN */
  MmioWrite32 (PCTRL_CTRL3, (PCTRL_CTRL3_USB_TXCO_EN << 16) | PCTRL_CTRL3_USB_TXCO_EN);

  MmioAnd32 (PCTRL_CTRL24, ~PCTRL_CTRL24_USB3PHY_3MUX1_SEL);

  MmioWrite32 (
    CRG_PEREN4,
    PEREN4_GT_ACLK_USB3OTG | PEREN4_GT_CLK_USB3OTG_REF
    );
  MmioWrite32 (
    CRG_PERRSTDIS4,
    PERRSTEN4_USB3OTG_MUX | PERRSTEN4_USB3OTG_AHBIF | PERRSTEN4_USB3OTG_32K
    );

  MmioWrite32 (CRG_PERRSTEN4, PERRSTEN4_USB3OTG | PERRSTEN4_USB3OTGPHY_POR);

  /* enable PHY REF CLK */
  MmioOr32 (USB3OTG_CTRL0, USB3OTG_CTRL0_SC_USB3PHY_ABB_GT_EN);

  MmioOr32 (USB3OTG_CTRL7, USB3OTG_CTRL7_REF_SSP_EN);

  /* exit from IDDQ mode */
  MmioAnd32 (
    USB3OTG_CTRL2,
    ~(USB3OTG_CTRL2_TEST_POWERDOWN_SSP | USB3OTG_CTRL2_TEST_POWERDOWN_HSP)
    );
  MicroSecondDelay (100000);

  MmioWrite32 (CRG_PERRSTDIS4, PERRSTEN4_USB3OTGPHY_POR);
  MmioWrite32 (CRG_PERRSTDIS4, PERRSTEN4_USB3OTG);
  MicroSecondDelay (10000);
  MmioOr32 (USB3OTG_CTRL3, USB3OTG_CTRL3_VBUSVLDEXT | USB3OTG_CTRL3_VBUSVLDEXTSEL);
  MicroSecondDelay (100000);

  HiKey960UsbSetEyeDiagramParam ();
}

EFI_STATUS
HiKey960UsbPhyInit (
  IN UINT8        Mode
  )
{
  HiKey960UsbReset ();
  MicroSecondDelay (10000);
  HiKey960UsbRelease ();

  return EFI_SUCCESS;
}

EFI_STATUS
EFIAPI
HiKey960UsbGetLang (
  OUT CHAR16            *Lang,
  OUT UINT8             *Length
  )
{
  if ((Lang == NULL) || (Length == NULL)) {
    return EFI_INVALID_PARAMETER;
  }
  Lang[0] = 0x409;
  *Length = sizeof (CHAR16);
  return EFI_SUCCESS;
}

EFI_STATUS
EFIAPI
HiKey960UsbGetManuFacturer (
  OUT CHAR16            *ManuFacturer,
  OUT UINT8             *Length
  )
{
  UINTN                  VariableSize;
  CHAR16                 DataUnicode[MANU_FACTURER_STRING_LENGTH];

  if ((ManuFacturer == NULL) || (Length == NULL)) {
    return EFI_INVALID_PARAMETER;
  }
  VariableSize = MANU_FACTURER_STRING_LENGTH * sizeof (CHAR16);
  ZeroMem (DataUnicode, MANU_FACTURER_STRING_LENGTH * sizeof(CHAR16));
  StrCpy (DataUnicode, L"96Boards");
  CopyMem (ManuFacturer, DataUnicode, VariableSize);
  *Length = VariableSize;
  return EFI_SUCCESS;
}

EFI_STATUS
EFIAPI
HiKey960UsbGetProduct (
  OUT CHAR16            *Product,
  OUT UINT8             *Length
  )
{
  UINTN                  VariableSize;
  CHAR16                 DataUnicode[PRODUCT_STRING_LENGTH];

  if ((Product == NULL) || (Length == NULL)) {
    return EFI_INVALID_PARAMETER;
  }
  VariableSize = PRODUCT_STRING_LENGTH * sizeof (CHAR16);
  ZeroMem (DataUnicode, PRODUCT_STRING_LENGTH * sizeof(CHAR16));
  StrCpy (DataUnicode, L"HiKey960");
  CopyMem (Product, DataUnicode, VariableSize);
  *Length = VariableSize;
  return EFI_SUCCESS;
}

EFI_STATUS
EFIAPI
HiKey960UsbGetSerialNo (
  OUT CHAR16            *SerialNo,
  OUT UINT8             *Length
  )
{
  EFI_STATUS                          Status;
  EFI_DEVICE_PATH_PROTOCOL           *FlashDevicePath;

  if (mFlashHandle == 0) {
    FlashDevicePath = ConvertTextToDevicePath ((CHAR16*)FixedPcdGetPtr (PcdAndroidFastbootNvmDevicePath));
    Status = gBS->LocateDevicePath (&gEfiBlockIoProtocolGuid, &FlashDevicePath, &mFlashHandle);
    if (EFI_ERROR (Status)) {
      DEBUG ((DEBUG_ERROR, "Warning: Couldn't locate Android NVM device (status: %r)\n", Status));
      // Failing to locate partitions should not prevent to do other Android FastBoot actions
      return EFI_SUCCESS;
    }
  }

  if ((SerialNo == NULL) || (Length == NULL)) {
    return EFI_INVALID_PARAMETER;
  }
  Status = LoadSNFromBlock (mFlashHandle, SERIAL_NUMBER_LBA, SerialNo);
  *Length = StrSize (SerialNo);
  return Status;
}

DW_USB_PROTOCOL mDwUsbDevice = {
  HiKey960UsbGetLang,
  HiKey960UsbGetManuFacturer,
  HiKey960UsbGetProduct,
  HiKey960UsbGetSerialNo,
  HiKey960UsbPhyInit
};

EFI_STATUS
EFIAPI
HiKey960UsbEntryPoint (
  IN EFI_HANDLE                            ImageHandle,
  IN EFI_SYSTEM_TABLE                      *SystemTable
  )
{
  return gBS->InstallProtocolInterface (
                &ImageHandle,
                &gDwUsbProtocolGuid,
                EFI_NATIVE_INTERFACE,
                &mDwUsbDevice
                );
}
