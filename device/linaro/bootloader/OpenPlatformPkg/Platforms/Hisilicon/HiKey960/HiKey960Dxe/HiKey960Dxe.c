/** @file
*
*  Copyright (c) 2016-2017, Linaro Ltd. All rights reserved.
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

#include <Guid/EventGroup.h>
#include <Guid/HiKey960Variable.h>

#include <Hi3660.h>
#include <Hkadc.h>
#include <libfdt.h>

#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/CacheMaintenanceLib.h>
#include <Library/DebugLib.h>
#include <Library/DevicePathLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/NonDiscoverableDeviceRegistrationLib.h>
#include <Library/IoLib.h>
#include <Library/PcdLib.h>
#include <Library/PrintLib.h>
#include <Library/TimerLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiRuntimeServicesTableLib.h>

#include <Protocol/Abootimg.h>
#include <Protocol/BlockIo.h>
#include <Protocol/DevicePathToText.h>
#include <Protocol/EmbeddedGpio.h>
#include <Protocol/NonDiscoverableDevice.h>
#include <Protocol/PlatformVirtualKeyboard.h>

#define ADC_ADCIN0                       0
#define ADC_ADCIN1                       1
#define ADC_ADCIN2                       2

#define HKADC_DATA_GRADE0                0
#define HKADC_DATA_GRADE1                100
#define HKADC_DATA_GRADE2                300
#define HKADC_DATA_GRADE3                500
#define HKADC_DATA_GRADE4                700
#define HKADC_DATA_GRADE5                900
#define HKADC_DATA_GRADE6                1100
#define HKADC_DATA_GRADE7                1300
#define HKADC_DATA_GRADE8                1500
#define HKADC_DATA_GRADE9                1700
#define HKADC_DATA_GRADE10               1800

#define BOARDID_VALUE0                   0
#define BOARDID_VALUE1                   1
#define BOARDID_VALUE2                   2
#define BOARDID_VALUE3                   3
#define BOARDID_VALUE4                   4
#define BOARDID_VALUE5                   5
#define BOARDID_VALUE6                   6
#define BOARDID_VALUE7                   7
#define BOARDID_VALUE8                   8
#define BOARDID_VALUE9                   9
#define BOARDID_UNKNOW                   0xF

#define BOARDID3_BASE                    5

#define HIKEY960_BOARDID_V1              5300
#define HIKEY960_BOARDID_V2              5301

#define HIKEY960_COMPATIBLE_LEDS_V1      "gpio-leds_v1"
#define HIKEY960_COMPATIBLE_LEDS_V2      "gpio-leds_v2"
#define HIKEY960_COMPATIBLE_HUB_V1       "hisilicon,gpio_hubv1"
#define HIKEY960_COMPATIBLE_HUB_V2       "hisilicon,gpio_hubv2"

#define SERIAL_NUMBER_SIZE               17
#define SERIAL_NUMBER_BLOCK_SIZE         EFI_PAGE_SIZE
#define SERIAL_NUMBER_LBA                20
#define RANDOM_MAX                       0x7FFFFFFFFFFFFFFF
#define RANDOM_MAGIC                     0x9A4DBEAF

#define ADB_REBOOT_ADDRESS               0x32100000
#define ADB_REBOOT_BOOTLOADER            0x77665500
#define ADB_REBOOT_NONE                  0x77665501

#define DETECT_SW_FASTBOOT               68        // GPIO8_4

typedef struct {
  UINT64        Magic;
  UINT64        Data;
  CHAR16        UnicodeSN[SERIAL_NUMBER_SIZE];
} RANDOM_SERIAL_NUMBER;

STATIC UINTN    mBoardId;

STATIC EMBEDDED_GPIO   *mGpio;

STATIC
VOID
InitAdc (
  VOID
  )
{
  // reset hkadc
  MmioWrite32 (CRG_PERRSTEN2, PERRSTEN2_HKADCSSI);
  // wait a few clock cycles
  MicroSecondDelay (2);
  MmioWrite32 (CRG_PERRSTDIS2, PERRSTEN2_HKADCSSI);
  MicroSecondDelay (2);
  // enable hkadc clock
  MmioWrite32 (CRG_PERDIS2, PEREN2_HKADCSSI);
  MicroSecondDelay (2);
  MmioWrite32 (CRG_PEREN2, PEREN2_HKADCSSI);
  MicroSecondDelay (2);
}

STATIC
EFI_STATUS
AdcGetAdc (
  IN  UINTN         Channel,
  OUT UINTN         *Value
  )
{
  UINT32            Data;
  UINT16            Value1, Value0;

  if (Channel > HKADC_CHANNEL_MAX) {
    DEBUG ((DEBUG_ERROR, "invalid channel:%d\n", Channel));
    return EFI_OUT_OF_RESOURCES;
  }
  // configure the read/write operation for external HKADC
  MmioWrite32 (HKADC_WR01_DATA, HKADC_WR01_VALUE | Channel);
  MmioWrite32 (HKADC_WR23_DATA, HKADC_WR23_VALUE);
  MmioWrite32 (HKADC_WR45_DATA, HKADC_WR45_VALUE);
  // configure the number of accessing registers
  MmioWrite32 (HKADC_WR_NUM, HKADC_WR_NUM_VALUE);
  // configure delay of accessing registers
  MmioWrite32 (HKADC_DELAY01, HKADC_CHANNEL0_DELAY01_VALUE);
  MmioWrite32 (HKADC_DELAY23, HKADC_DELAY23_VALUE);

  // start HKADC
  MmioWrite32 (HKADC_DSP_START, 1);
  do {
    Data = MmioRead32 (HKADC_DSP_START);
  } while (Data & 1);

  // convert AD result
  Value1 = (UINT16)MmioRead32 (HKADC_DSP_RD2_DATA);
  Value0 = (UINT16)MmioRead32 (HKADC_DSP_RD3_DATA);

  Data = ((Value1 << 4) & HKADC_VALUE_HIGH) | ((Value0 >> 4) & HKADC_VALUE_LOW);
  *Value = Data;
  return EFI_SUCCESS;
}

STATIC
EFI_STATUS
AdcGetValue (
  IN UINTN         Channel,
  IN OUT UINTN     *Value
  )
{
  EFI_STATUS       Status;
  UINTN            Result;

  Status = AdcGetAdc (Channel, Value);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  // convert ADC value to micro-volt
  Result = ((*Value & HKADC_VALID_VALUE) * HKADC_VREF_1V8) / HKADC_ACCURACY;
  *Value = Result;
  return EFI_SUCCESS;
}

STATIC
UINTN
AdcinDataRemap (
  IN UINTN           AdcinValue
  )
{
  UINTN              Result;

  if (AdcinValue < HKADC_DATA_GRADE0) {
    Result = BOARDID_UNKNOW;
  } else if (AdcinValue < HKADC_DATA_GRADE1) {
    Result = BOARDID_VALUE0;
  } else if (AdcinValue < HKADC_DATA_GRADE2) {
    Result = BOARDID_VALUE1;
  } else if (AdcinValue < HKADC_DATA_GRADE3) {
    Result = BOARDID_VALUE2;
  } else if (AdcinValue < HKADC_DATA_GRADE4) {
    Result = BOARDID_VALUE3;
  } else if (AdcinValue < HKADC_DATA_GRADE5) {
    Result = BOARDID_VALUE4;
  } else if (AdcinValue < HKADC_DATA_GRADE6) {
    Result = BOARDID_VALUE5;
  } else if (AdcinValue < HKADC_DATA_GRADE7) {
    Result = BOARDID_VALUE6;
  } else if (AdcinValue < HKADC_DATA_GRADE8) {
    Result = BOARDID_VALUE7;
  } else if (AdcinValue < HKADC_DATA_GRADE9) {
    Result = BOARDID_VALUE8;
  } else if (AdcinValue < HKADC_DATA_GRADE10) {
    Result = BOARDID_VALUE9;
  } else {
    Result = BOARDID_UNKNOW;
  }
  return Result;
}

STATIC
EFI_STATUS
InitBoardId (
  OUT UINTN          *Id
  )
{
  UINTN              Adcin0, Adcin1, Adcin2;
  UINTN              Adcin0Remap, Adcin1Remap, Adcin2Remap;

  InitAdc ();

  // read ADC channel0 data
  AdcGetValue (ADC_ADCIN0, &Adcin0);
  DEBUG ((DEBUG_ERROR, "[BDID]Adcin0:%d\n", Adcin0));
  Adcin0Remap = AdcinDataRemap (Adcin0);
  DEBUG ((DEBUG_ERROR, "[BDID]Adcin0Remap:%d\n", Adcin0Remap));
  if (Adcin0Remap == BOARDID_UNKNOW) {
    return EFI_INVALID_PARAMETER;
  }
  // read ADC channel1 data
  AdcGetValue (ADC_ADCIN1, &Adcin1);
  DEBUG ((DEBUG_ERROR, "[BDID]Adcin1:%d\n", Adcin1));
  Adcin1Remap = AdcinDataRemap (Adcin1);
  DEBUG ((DEBUG_ERROR, "[BDID]Adcin1Remap:%d\n", Adcin1Remap));
  if (Adcin1Remap == BOARDID_UNKNOW) {
    return EFI_INVALID_PARAMETER;
  }
  // read ADC channel2 data
  AdcGetValue (ADC_ADCIN2, &Adcin2);
  DEBUG ((DEBUG_ERROR, "[BDID]Adcin2:%d\n", Adcin2));
  Adcin2Remap = AdcinDataRemap (Adcin2);
  DEBUG ((DEBUG_ERROR, "[BDID]Adcin2Remap:%d\n", Adcin2Remap));
  if (Adcin2Remap == BOARDID_UNKNOW) {
    return EFI_INVALID_PARAMETER;
  }
  *Id = BOARDID3_BASE * 1000 + (Adcin2Remap * 100) + (Adcin1Remap * 10) + Adcin0Remap;
  DEBUG ((DEBUG_ERROR, "[BDID]boardid: %d\n", *Id));
  return EFI_SUCCESS;
}

STATIC
VOID
InitSdCard (
  IN VOID
  )
{
  UINT32        Data;

  // LDO16
  Data = MmioRead32 (PMU_REG_BASE + (0x79 << 2)) & 7;
  Data |= 6;
  MmioWrite32 (PMU_REG_BASE + (0x79 << 2), Data);
  MmioOr32 (PMU_REG_BASE + (0x78 << 2), 2);
  MicroSecondDelay (100);

  // LDO9
  Data = MmioRead32 (PMU_REG_BASE + (0x6b << 2)) & 7;
  Data |= 5;
  MmioWrite32 (PMU_REG_BASE + (0x6b << 2), Data);
  MmioOr32 (PMU_REG_BASE + (0x6a << 2), 2);
  MicroSecondDelay (100);

  // GPIO203
  MmioWrite32 (0xfff11000 + (24 << 2), 0); // GPIO function

  // SD pinmux
  MmioWrite32 (0xff37e000 + 0x0, 1); // SD_CLK
  MmioWrite32 (0xff37e000 + 0x4, 1); // SD_CMD
  MmioWrite32 (0xff37e000 + 0x8, 1); // SD_DATA0
  MmioWrite32 (0xff37e000 + 0xc, 1); // SD_DATA1
  MmioWrite32 (0xff37e000 + 0x10, 1); // SD_DATA2
  MmioWrite32 (0xff37e000 + 0x14, 1); // SD_DATA3
  MmioWrite32 (0xff37e800 + 0x0, 15 << 4); // SD_CLK float with 32mA
  MmioWrite32 (0xff37e800 + 0x4, (1 << 0) | (8 << 4)); // SD_CMD
  MmioWrite32 (0xff37e800 + 0x8, (1 << 0) | (8 << 4)); // SD_DATA0
  MmioWrite32 (0xff37e800 + 0xc, (1 << 0) | (8 << 4)); // SD_DATA1
  MmioWrite32 (0xff37e800 + 0x10, (1 << 0) | (8 << 4)); // SD_DATA2
  MmioWrite32 (0xff37e800 + 0x14, (1 << 0) | (8 << 4)); // SD_DATA3

  do {
    MmioOr32 (CRG_REG_BASE + 0xb8, (1 << 6) | (1 << 6 << 16) | (0 << 4) | (3 << 4 << 16));
    Data = MmioRead32 (CRG_REG_BASE + 0xb8);
  } while ((Data & ((1 << 6) | (3 << 4))) != ((1 << 6) | (0 << 4)));

  // Unreset SD controller
  MmioWrite32 (CRG_PERRSTDIS4, 1 << 18);
  do {
    Data = MmioRead32 (CRG_PERRSTSTAT4);
  } while ((Data & (1 << 18)) == (1 << 18));
  // Enable SD controller clock
  MmioOr32 (CRG_REG_BASE + 0, 1 << 30);
  MmioOr32 (CRG_REG_BASE + 0x40, 1 << 17);
  do {
    Data = MmioRead32 (CRG_REG_BASE + 0x48);
  } while ((Data & (1 << 17)) != (1 << 17));
}

VOID
InitPeripherals (
  IN VOID
  )
{
  // Enable FPLL0
  MmioOr32 (SCTRL_SCFPLLCTRL0, SCTRL_SCFPLLCTRL0_FPLL0_EN);

  InitSdCard ();

  // Enable wifi clock
  MmioOr32 (PMIC_HARDWARE_CTRL0, PMIC_HARDWARE_CTRL0_WIFI_CLK);
  MmioOr32 (PMIC_OSC32K_ONOFF_CTRL, PMIC_OSC32K_ONOFF_CTRL_EN_32K);
}

/**
  Notification function of the event defined as belonging to the
  EFI_END_OF_DXE_EVENT_GROUP_GUID event group that was created in
  the entry point of the driver.

  This function is called when an event belonging to the
  EFI_END_OF_DXE_EVENT_GROUP_GUID event group is signalled. Such an
  event is signalled once at the end of the dispatching of all
  drivers (end of the so called DXE phase).

  @param[in]  Event    Event declared in the entry point of the driver whose
                       notification function is being invoked.
  @param[in]  Context  NULL
**/
STATIC
VOID
OnEndOfDxe (
  IN EFI_EVENT  Event,
  IN VOID       *Context
  )
{
}

EFI_STATUS
EFIAPI
AbootimgAppendKernelArgs (
  IN CHAR16            *Args,
  IN UINTN              Size
  )
{
  EFI_STATUS                  Status;
  EFI_BLOCK_IO_PROTOCOL      *BlockIoProtocol;
  VOID                       *DataPtr;
  RANDOM_SERIAL_NUMBER       *RandomSN;
  EFI_DEVICE_PATH_PROTOCOL   *FlashDevicePath;
  EFI_HANDLE                  FlashHandle;

  if (Args == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  FlashDevicePath = ConvertTextToDevicePath ((CHAR16*)FixedPcdGetPtr (PcdAndroidFastbootNvmDevicePath));
  Status = gBS->LocateDevicePath (&gEfiBlockIoProtocolGuid, &FlashDevicePath, &FlashHandle);
  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_ERROR, "Warning: Couldn't locate Android NVM device (status: %r)\n", Status));
    // Failing to locate partitions should not prevent to do other Android FastBoot actions
    return EFI_SUCCESS;
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

  DataPtr = AllocatePages (1);
  if (DataPtr == NULL) {
    return EFI_BUFFER_TOO_SMALL;
  }
  Status = BlockIoProtocol->ReadBlocks (
                              BlockIoProtocol,
                              BlockIoProtocol->Media->MediaId,
                              SERIAL_NUMBER_LBA,
                              SERIAL_NUMBER_BLOCK_SIZE,
                              DataPtr
                              );
  if (EFI_ERROR (Status)) {
    DEBUG ((DEBUG_WARN, "Warning: Failed on reading blocks\n"));
    goto Exit;
  }
  RandomSN = (RANDOM_SERIAL_NUMBER *)DataPtr;
  if (RandomSN->Magic != RANDOM_MAGIC) {
    UnicodeSPrint(
      RandomSN->UnicodeSN, SERIAL_NUMBER_SIZE * sizeof (CHAR16),
      L"0123456789abcdef"
      );
  }
  if (mBoardId == HIKEY960_BOARDID_V1) {
    UnicodeSPrint (
      Args + StrLen (Args), Size - StrLen (Args),
      L" console=ttyAMA5 androidboot.serialno=%s",
      RandomSN->UnicodeSN
      );
  } else {
    UnicodeSPrint (
      Args + StrLen (Args), Size - StrLen (Args),
      L" console=ttyAMA6 androidboot.serialno=%s",
      RandomSN->UnicodeSN
      );
  }
  FreePages (DataPtr, 1);
  return EFI_SUCCESS;
Exit:
  FreePages (DataPtr, 1);
  return Status;
}

EFI_STATUS
EFIAPI
AbootimgUpdateDtb (
  IN  EFI_PHYSICAL_ADDRESS        OrigFdtBase,
  OUT EFI_PHYSICAL_ADDRESS       *NewFdtBase
  )
{
  //UINT8            *FdtPtr;
  UINTN             FdtSize, NumPages;
  INTN              err, offset;
  EFI_STATUS        Status;

  //
  // Sanity checks on the original FDT blob.
  //
  err = fdt_check_header ((VOID*)(UINTN)OrigFdtBase);
  if (err != 0) {
    DEBUG ((DEBUG_ERROR, "ERROR: Device Tree header not valid (err:%d)\n", err));
    return EFI_INVALID_PARAMETER;
  }

  //
  // Store the FDT as Runtime Service Data to prevent the Kernel from
  // overwritting its data.
  //
  FdtSize = fdt_totalsize ((VOID *)(UINTN)OrigFdtBase);
  NumPages = EFI_SIZE_TO_PAGES (FdtSize) + 20;
  Status = gBS->AllocatePages (
                  AllocateAnyPages, EfiRuntimeServicesData,
                  NumPages, NewFdtBase);
  if (EFI_ERROR (Status)) {
    return EFI_BUFFER_TOO_SMALL;
  }

  CopyMem (
    (VOID*)(UINTN)*NewFdtBase,
    (VOID*)(UINTN)OrigFdtBase,
    FdtSize
    );

  if (mBoardId == HIKEY960_BOARDID_V1) {
    offset = fdt_node_offset_by_compatible (
               (VOID*)(UINTN)*NewFdtBase, -1, HIKEY960_COMPATIBLE_LEDS_V1
               );
  } else {
    offset = fdt_node_offset_by_compatible (
               (VOID*)(UINTN)*NewFdtBase, -1, HIKEY960_COMPATIBLE_LEDS_V2
               );
  }
  // Ignore it if can't find LED compatible
  if (offset < 0) {
    DEBUG ((DEBUG_WARN, "WARN: Failed to find node with compatible (err:%d)\n", err));
    goto Exit;
  }
  err = fdt_setprop_string ((VOID*)(UINTN)*NewFdtBase, offset, "status", "ok");
  if (err) {
    DEBUG ((DEBUG_ERROR, "ERROR: Failed to update status property\n"));
    return EFI_INVALID_PARAMETER;
  }
  err = fdt_set_name ((VOID*)(UINTN)*NewFdtBase, offset, "gpio-leds");
  if (err) {
    DEBUG ((DEBUG_ERROR, "ERROR: Failed to update compatible name\n"));
    return EFI_INVALID_PARAMETER;
  }

  if (mBoardId == HIKEY960_BOARDID_V1) {
    offset = fdt_node_offset_by_compatible (
               (VOID*)(UINTN)*NewFdtBase, -1, HIKEY960_COMPATIBLE_HUB_V1
               );
  } else {
    offset = fdt_node_offset_by_compatible (
               (VOID*)(UINTN)*NewFdtBase, -1, HIKEY960_COMPATIBLE_HUB_V2
               );
  }
  // Ignore it if can't find LED compatible
  if (offset < 0) {
    DEBUG ((DEBUG_WARN, "WARN: Failed to find node with compatible (err:%d)\n", err));
    goto Exit;
  }
  err = fdt_setprop_string ((VOID*)(UINTN)*NewFdtBase, offset, "status", "ok");
  if (err) {
    DEBUG ((DEBUG_ERROR, "ERROR: Failed to update status property\n"));
    return EFI_INVALID_PARAMETER;
  }

Exit:
  fdt_pack ((VOID*)(UINTN)*NewFdtBase);
  err = fdt_check_header ((VOID*)(UINTN)*NewFdtBase);
  if (err != 0) {
    DEBUG ((DEBUG_ERROR, "ERROR: Device Tree header not valid (err:%d)\n", err));
    gBS->FreePages (*NewFdtBase, NumPages);
    return EFI_INVALID_PARAMETER;
  }

  return EFI_SUCCESS;
}

ABOOTIMG_PROTOCOL mAbootimg = {
  AbootimgAppendKernelArgs,
  AbootimgUpdateDtb
};

EFI_STATUS
EFIAPI
VirtualKeyboardRegister (
  IN VOID
  )
{
  EFI_STATUS           Status;

  Status = gBS->LocateProtocol (
                  &gEmbeddedGpioProtocolGuid,
                  NULL,
                  (VOID **) &mGpio
                  );
  if (EFI_ERROR (Status)) {
    return Status;
  }
  return EFI_SUCCESS;
}

EFI_STATUS
EFIAPI
VirtualKeyboardReset (
  IN VOID
  )
{
  EFI_STATUS           Status;

  if (mGpio == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  // Configure GPIO68 as GPIO function
  MmioWrite32 (0xe896c108, 0);
  Status = mGpio->Set (mGpio, DETECT_SW_FASTBOOT, GPIO_MODE_INPUT);
  return Status;
}

BOOLEAN
EFIAPI
VirtualKeyboardQuery (
  IN VIRTUAL_KBD_KEY             *VirtualKey
  )
{
  EFI_STATUS           Status;
  UINTN                Value = 0;

  if ((VirtualKey == NULL) || (mGpio == NULL)) {
    return FALSE;
  }
  if (MmioRead32 (ADB_REBOOT_ADDRESS) == ADB_REBOOT_BOOTLOADER) {
    goto Done;
  } else {
    Status = mGpio->Get (mGpio, DETECT_SW_FASTBOOT, &Value);
    if (EFI_ERROR (Status) || (Value != 0)) {
      return FALSE;
    }
  }
Done:
  VirtualKey->Signature = VIRTUAL_KEYBOARD_KEY_SIGNATURE;
  VirtualKey->Key.ScanCode = SCAN_NULL;
  VirtualKey->Key.UnicodeChar = L'f';
  return TRUE;
}

EFI_STATUS
EFIAPI
VirtualKeyboardClear (
  IN VIRTUAL_KBD_KEY            *VirtualKey
  )
{
  if (VirtualKey == NULL) {
    return EFI_INVALID_PARAMETER;
  }
  if (MmioRead32 (ADB_REBOOT_ADDRESS) == ADB_REBOOT_BOOTLOADER) {
    MmioWrite32 (ADB_REBOOT_ADDRESS, ADB_REBOOT_NONE);
    WriteBackInvalidateDataCacheRange ((VOID *)ADB_REBOOT_ADDRESS, 4);
  }
  return EFI_SUCCESS;
}

PLATFORM_VIRTUAL_KBD_PROTOCOL mVirtualKeyboard = {
  VirtualKeyboardRegister,
  VirtualKeyboardReset,
  VirtualKeyboardQuery,
  VirtualKeyboardClear
};

EFI_STATUS
EFIAPI
HiKey960EntryPoint (
  IN EFI_HANDLE         ImageHandle,
  IN EFI_SYSTEM_TABLE   *SystemTable
  )
{
  EFI_STATUS            Status;
  EFI_EVENT             EndOfDxeEvent;

  Status = InitBoardId (&mBoardId);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  InitPeripherals ();

  //
  // Create an event belonging to the "gEfiEndOfDxeEventGroupGuid" group.
  // The "OnEndOfDxe()" function is declared as the call back function.
  // It will be called at the end of the DXE phase when an event of the
  // same group is signalled to inform about the end of the DXE phase.
  // Install the INSTALL_FDT_PROTOCOL protocol.
  //
  Status = gBS->CreateEventEx (
                  EVT_NOTIFY_SIGNAL,
                  TPL_CALLBACK,
                  OnEndOfDxe,
                  NULL,
                  &gEfiEndOfDxeEventGroupGuid,
                  &EndOfDxeEvent
                  );
  if (EFI_ERROR (Status)) {
    return Status;
  }

  // RegisterNonDicoverableMmioDevice
  Status = RegisterNonDiscoverableMmioDevice (
             NonDiscoverableDeviceTypeUfs,
             NonDiscoverableDeviceDmaTypeNonCoherent,
             NULL,
             NULL,
             1,
             FixedPcdGet32 (PcdDwUfsHcDxeBaseAddress),
             SIZE_4KB
             );
  if (EFI_ERROR (Status)) {
    return Status;
  }
  Status = RegisterNonDiscoverableMmioDevice (
             NonDiscoverableDeviceTypeSdhci,
             NonDiscoverableDeviceDmaTypeNonCoherent,
             NULL,
             NULL,
             1,
             0xFF37F000, // SD
             SIZE_4KB
             );
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Status = gBS->InstallProtocolInterface (
                  &ImageHandle,
                  &gAbootimgProtocolGuid,
                  EFI_NATIVE_INTERFACE,
                  &mAbootimg
                  );
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Status = gBS->InstallProtocolInterface (
                  &ImageHandle,
                  &gPlatformVirtualKeyboardProtocolGuid,
                  EFI_NATIVE_INTERFACE,
                  &mVirtualKeyboard
                  );
  return Status;
}
