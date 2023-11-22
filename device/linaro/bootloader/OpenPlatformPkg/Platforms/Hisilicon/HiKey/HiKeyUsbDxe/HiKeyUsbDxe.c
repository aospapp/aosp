/** @file
*
*  Copyright (c) 2015-2017, Linaro. All rights reserved.
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

#include <Protocol/EmbeddedGpio.h>
#include <Protocol/DwUsb.h>

#include <Hi6220.h>


#define USB_SEL_GPIO0_3          3     // GPIO 0_3
#define USB_5V_HUB_EN            7     // GPIO 0_7
#define USB_ID_DET_GPIO2_5       21    // GPIO 2_5
#define USB_VBUS_DET_GPIO2_6     22    // GPIO 2_6

// Jumper on pin5-6 of J15 determines whether boot to fastboot
#define DETECT_J15_FASTBOOT      24    // GPIO 3_0

#define SERIAL_NUMBER_LBA        1024

STATIC EMBEDDED_GPIO *mGpio;

STATIC
VOID
HiKeyDetectUsbModeInit (
  IN VOID
  )
{
  EFI_STATUS     Status;

  /* set pullup on both GPIO2_5 & GPIO2_6. It's required for inupt. */
  MmioWrite32 (0xf8001864, 1);
  MmioWrite32 (0xf8001868, 1);

  Status = gBS->LocateProtocol (&gEmbeddedGpioProtocolGuid, NULL, (VOID **)&mGpio);
  ASSERT_EFI_ERROR (Status);
  Status = mGpio->Set (mGpio, USB_SEL_GPIO0_3, GPIO_MODE_OUTPUT_0);
  ASSERT_EFI_ERROR (Status);
  Status = mGpio->Set (mGpio, USB_5V_HUB_EN, GPIO_MODE_OUTPUT_0);
  ASSERT_EFI_ERROR (Status);
  MicroSecondDelay (1000);

  Status = mGpio->Set (mGpio, USB_ID_DET_GPIO2_5, GPIO_MODE_INPUT);
  ASSERT_EFI_ERROR (Status);
  Status = mGpio->Set (mGpio, USB_VBUS_DET_GPIO2_6, GPIO_MODE_INPUT);
  ASSERT_EFI_ERROR (Status);
}

UINTN
HiKeyGetUsbMode (
  IN VOID
  )
{
#if 0
  EFI_STATUS     Status;
  UINTN          GpioId, GpioVbus;
  UINTN          Value;

  Status = mGpio->Get (mGpio, USB_ID_DET_GPIO2_5, &Value);
  ASSERT_EFI_ERROR (Status);
  GpioId = Value;
  Status = mGpio->Get (mGpio, USB_VBUS_DET_GPIO2_6, &Value);
  ASSERT_EFI_ERROR (Status);
  GpioVbus = Value;

DEBUG ((DEBUG_ERROR, "#%a, %d, GpioId:%d, GpioVbus:%d\n", __func__, __LINE__, GpioId, GpioVbus));
  if ((GpioId == 1) && (GpioVbus == 0)) {
    return USB_DEVICE_MODE;
  } else if ((GpioId == 0) && (GpioVbus == 1)) {
    return USB_CABLE_NOT_ATTACHED;
  }
  return USB_HOST_MODE;
#else
  return USB_DEVICE_MODE;
#endif
}

EFI_STATUS
HiKeyUsbPhyInit (
  IN UINT8        Mode
  )
{
  UINTN         Value;
  UINT32        Data;

  HiKeyDetectUsbModeInit ();

  //setup clock
  MmioWrite32 (PERI_CTRL_BASE + SC_PERIPH_CLKEN0, BIT4);
  do {
       Value = MmioRead32 (PERI_CTRL_BASE + SC_PERIPH_CLKSTAT0);
  } while ((Value & BIT4) == 0);

  //setup phy
  Data = RST0_USBOTG_BUS | RST0_POR_PICOPHY |
           RST0_USBOTG | RST0_USBOTG_32K;
  MmioWrite32 (PERI_CTRL_BASE + SC_PERIPH_RSTDIS0, Data);
  do {
    Value = MmioRead32 (PERI_CTRL_BASE + SC_PERIPH_RSTSTAT0);
    Value &= Data;
  } while (Value);

  Value = MmioRead32 (PERI_CTRL_BASE + SC_PERIPH_CTRL4);
  Value &= ~(CTRL4_PICO_SIDDQ | CTRL4_FPGA_EXT_PHY_SEL |
             CTRL4_OTG_PHY_SEL);
  Value |=  CTRL4_PICO_VBUSVLDEXT | CTRL4_PICO_VBUSVLDEXTSEL;
  MmioWrite32 (PERI_CTRL_BASE + SC_PERIPH_CTRL4, Value);
  MicroSecondDelay (1000);

  //If Mode = 1, USB in Device Mode
  //If Mode = 0, USB in Host Mode
  if (Mode == USB_DEVICE_MODE) {
    if (HiKeyGetUsbMode () == USB_DEVICE_MODE) {
      DEBUG ((DEBUG_ERROR, "usb work as device mode.\n"));
    } else {
      return EFI_INVALID_PARAMETER;
    }

     Value = MmioRead32 (PERI_CTRL_BASE + SC_PERIPH_CTRL5);
     Value &= ~CTRL5_PICOPHY_BC_MODE;
     MmioWrite32 (PERI_CTRL_BASE + SC_PERIPH_CTRL5, Value);
     MicroSecondDelay (20000);
  } else {
    if (HiKeyGetUsbMode () == USB_HOST_MODE) {
      DEBUG ((DEBUG_ERROR, "usb work as host mode.\n"));
    } else {
      return EFI_INVALID_PARAMETER;
    }

    /*CTRL5*/
    Data = MmioRead32 (PERI_CTRL_BASE + SC_PERIPH_CTRL5);
    Data &= ~CTRL5_PICOPHY_BC_MODE;
    Data |= CTRL5_USBOTG_RES_SEL | CTRL5_PICOPHY_ACAENB |
            CTRL5_PICOPHY_VDATDETENB | CTRL5_PICOPHY_DCDENB;
    MmioWrite32 (PERI_CTRL_BASE + SC_PERIPH_CTRL5, Data);
    MicroSecondDelay (20000);
    MmioWrite32 (PERI_CTRL_BASE + 0x018, 0x70533483); //EYE_PATTERN

    MicroSecondDelay (5000);
  }

  return EFI_SUCCESS;
}

EFI_STATUS
EFIAPI
HiKeyUsbGetLang (
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
HiKeyUsbGetManuFacturer (
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
HiKeyUsbGetProduct (
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
  StrCpy (DataUnicode, L"HiKey");
  CopyMem (Product, DataUnicode, VariableSize);
  *Length = VariableSize;
  return EFI_SUCCESS;
}

EFI_STATUS
EFIAPI
HiKeyUsbGetSerialNo (
  OUT CHAR16            *SerialNo,
  OUT UINT8             *Length
  )
{
  EFI_STATUS                          Status;
  EFI_DEVICE_PATH_PROTOCOL           *FlashDevicePath;
  EFI_HANDLE                          FlashHandle;

  FlashDevicePath = ConvertTextToDevicePath ((CHAR16*)FixedPcdGetPtr (PcdAndroidFastbootNvmDevicePath));
  Status = gBS->LocateDevicePath (&gEfiBlockIoProtocolGuid, &FlashDevicePath, &FlashHandle);
  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_ERROR, "Warning: Couldn't locate Android NVM device (status: %r)\n", Status));
    // Failing to locate partitions should not prevent to do other Android FastBoot actions
    return EFI_SUCCESS;
  }

  if ((SerialNo == NULL) || (Length == NULL)) {
    return EFI_INVALID_PARAMETER;
  }
  Status = LoadSNFromBlock (FlashHandle, SERIAL_NUMBER_LBA, SerialNo);
  *Length = StrSize (SerialNo);
  return Status;
}

DW_USB_PROTOCOL mDwUsbDevice = {
  HiKeyUsbGetLang,
  HiKeyUsbGetManuFacturer,
  HiKeyUsbGetProduct,
  HiKeyUsbGetSerialNo,
  HiKeyUsbPhyInit
};

EFI_STATUS
EFIAPI
HiKeyUsbEntryPoint (
  IN EFI_HANDLE                            ImageHandle,
  IN EFI_SYSTEM_TABLE                      *SystemTable
  )
{
  EFI_STATUS        Status;

  Status = gBS->InstallProtocolInterface (
                  &ImageHandle,
                  &gDwUsbProtocolGuid,
                  EFI_NATIVE_INTERFACE,
                  &mDwUsbDevice
                  );
  if (EFI_ERROR (Status)) {
    return Status;
  }
  return Status;
}
