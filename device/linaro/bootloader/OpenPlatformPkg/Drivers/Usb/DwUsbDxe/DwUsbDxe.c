/** @file

  Copyright (c) 2015-2017, Linaro. All rights reserved.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include <IndustryStandard/Usb.h>
#include <Library/ArmLib.h>
#include <Library/TimerLib.h>
#include <Library/DebugLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiDriverEntryPoint.h>
#include <Library/UefiRuntimeServicesTableLib.h>
#include <Library/IoLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/UncachedMemoryAllocationLib.h>
#include <Library/CacheMaintenanceLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/BaseLib.h>
#include <Protocol/DwUsb.h>
#include <Protocol/UsbDevice.h>

#include "DwUsbDxe.h"

#define USB_TYPE_LENGTH               16
#define USB_BLOCK_HIGH_SPEED_SIZE     512
#define DATA_SIZE                     32768
#define CMD_SIZE                      512
#define MATCH_CMD_LITERAL(Cmd, Buf)   !AsciiStrnCmp (Cmd, Buf, sizeof (Cmd) - 1)

// The time between interrupt polls, in units of 100 nanoseconds
// 10 Microseconds
#define DW_INTERRUPT_POLL_PERIOD      10000

EFI_GUID  gDwUsbProtocolGuid = DW_USB_PROTOCOL_GUID;

STATIC dwc_otg_dev_dma_desc_t *gDmaDesc,*gDmaDescEp0,*gDmaDescIn;
STATIC USB_DEVICE_REQUEST *gCtrlReq;
STATIC VOID *RxBuf;
STATIC UINTN RxDescBytes = 0;
STATIC UINTN mNumDataBytes;

STATIC DW_USB_PROTOCOL          *DwUsb;

STATIC USB_DEVICE_DESCRIPTOR    *mDeviceDescriptor;

// The config descriptor, interface descriptor, and endpoint descriptors in a
// buffer (in that order)
STATIC VOID                     *mDescriptors;
// Convenience pointers to those descriptors inside the buffer:
STATIC USB_INTERFACE_DESCRIPTOR *mInterfaceDescriptor;
STATIC USB_CONFIG_DESCRIPTOR    *mConfigDescriptor;
STATIC USB_ENDPOINT_DESCRIPTOR  *mEndpointDescriptors;

STATIC USB_DEVICE_RX_CALLBACK   mDataReceivedCallback;
STATIC USB_DEVICE_TX_CALLBACK   mDataSentCallback;


/* To detect which mode was run, high speed or full speed */
STATIC
UINTN
UsbDrvPortSpeed (
  VOID
  )
{
  /*
  * 2'b00: High speed (PHY clock is running at 30 or 60 MHz)
  */
  UINT32          Val = READ_REG32 (DSTS) & 2;
  return (!Val);
}

STATIC
VOID
ResetEndpoints (
  VOID
  )
{
  /* EP0 IN ACTIVE NEXT=1 */
  WRITE_REG32 (DIEPCTL0, DXEPCTL_USBACTEP | BIT11);

  /* EP0 OUT ACTIVE */
  WRITE_REG32 (DOEPCTL0, DXEPCTL_USBACTEP);

  /* Clear any pending OTG Interrupts */
  WRITE_REG32 (GOTGINT, ~0);

  /* Clear any pending interrupts */
  WRITE_REG32 (GINTSTS, ~0);
  WRITE_REG32 (DIEPINT0, ~0);
  WRITE_REG32 (DOEPINT0, ~0);
  WRITE_REG32 (DIEPINT1, ~0);
  WRITE_REG32 (DOEPINT1, ~0);

  /* IN EP interrupt mask */
  WRITE_REG32 (DIEPMSK, DXEPMSK_TIMEOUTMSK | DXEPMSK_AHBERMSK | DXEPMSK_XFERCOMPLMSK);
  /* OUT EP interrupt mask */
  WRITE_REG32 (DOEPMSK, DXEPMSK_TIMEOUTMSK | DXEPMSK_AHBERMSK | DXEPMSK_XFERCOMPLMSK);
  /* Enable interrupts on Ep0 */
  WRITE_REG32 (DAINTMSK, (1 << DAINTMSK_OUTEPMSK_SHIFT) | (1 << DAINTMSK_INEPMSK_SHIFT));

  /* EP0 OUT Transfer Size:64 Bytes, 1 Packet, 3 Setup Packet, Read to receive setup packet*/
  WRITE_REG32 (DOEPTSIZ0, DXEPTSIZ_SUPCNT(3) | DXEPTSIZ_PKTCNT(1) | DXEPTSIZ_XFERSIZE(64));

  //notes that:the compulsive conversion is expectable.
  gDmaDescEp0->status.b.bs = 0x3;
  gDmaDescEp0->status.b.mtrf = 0;
  gDmaDescEp0->status.b.sr = 0;
  gDmaDescEp0->status.b.l = 1;
  gDmaDescEp0->status.b.ioc = 1;
  gDmaDescEp0->status.b.sp = 0;
  gDmaDescEp0->status.b.bytes = 64;
  gDmaDescEp0->buf = (UINT32)(UINTN)(gCtrlReq);
  gDmaDescEp0->status.b.sts = 0;
  gDmaDescEp0->status.b.bs = 0x0;
  WRITE_REG32 (DOEPDMA0, (UINT32)(UINTN)(gDmaDescEp0));
  /* EP0 OUT ENABLE CLEARNAK */
  WRITE_REG32 (DOEPCTL0, (READ_REG32 (DOEPCTL0) | DXEPCTL_EPENA | DXEPCTL_CNAK));
}

STATIC
VOID
EpTx (
  IN UINT8          Ep,
  IN CONST VOID    *Ptr,
  IN UINTN          Len
  )
{
    UINT32          BlockSize;
    UINT32          Packets;

    /* EPx OUT ACTIVE */
    WRITE_REG32 (DIEPCTL (Ep), (READ_REG32 (DIEPCTL (Ep))) | DXEPCTL_USBACTEP);
    if (!Ep) {
        BlockSize = 64;
    } else {
        BlockSize = UsbDrvPortSpeed () ? USB_BLOCK_HIGH_SPEED_SIZE : 64;
    }
    Packets = (Len + BlockSize - 1) / BlockSize;

    if (!Len) {
      /* send one empty packet */
      gDmaDescIn->status.b.bs = 0x3;
      gDmaDescIn->status.b.l = 1;
      gDmaDescIn->status.b.ioc = 1;
      gDmaDescIn->status.b.sp = 1;
      gDmaDescIn->status.b.bytes = 0;
      gDmaDescIn->buf = 0;
      gDmaDescIn->status.b.sts = 0;
      gDmaDescIn->status.b.bs = 0x0;

      WRITE_REG32 (DIEPDMA (Ep), (UINT32)(UINTN)(gDmaDescIn));             // DMA Address (DMAAddr) is zero
    } else {
      WRITE_REG32 (DIEPTSIZ (Ep), Len | (Packets << 19));

      //flush cache
      WriteBackDataCacheRange ((VOID *)Ptr, Len);

      gDmaDescIn->status.b.bs = 0x3;
      gDmaDescIn->status.b.l = 1;
      gDmaDescIn->status.b.ioc = 1;
      gDmaDescIn->status.b.sp = 1;
      gDmaDescIn->status.b.bytes = Len;
      gDmaDescIn->buf = (UINT32)((UINTN)Ptr);
      gDmaDescIn->status.b.sts = 0;
      gDmaDescIn->status.b.bs = 0x0;
      WRITE_REG32 (DIEPDMA (Ep), (UINT32)(UINTN)(gDmaDescIn));         // Ptr is DMA address
    }
    ArmDataSynchronizationBarrier ();
    /* epena & cnak */
    WRITE_REG32 (DIEPCTL (Ep), READ_REG32 (DIEPCTL (Ep)) | DXEPCTL_EPENA | DXEPCTL_CNAK | BIT11);
}

STATIC
VOID
EpRx (
  IN UINTN            Ep,
  IN UINTN            Len
  )
{
  /* EPx UNSTALL */
  WRITE_REG32 (DOEPCTL (Ep), ((READ_REG32 (DOEPCTL (Ep))) & (~DXEPCTL_STALL)));
  /* EPx OUT ACTIVE */
  WRITE_REG32 (DOEPCTL (Ep), (READ_REG32 (DOEPCTL (Ep)) | DXEPCTL_USBACTEP));

  if (Len >= DATA_SIZE) {
    RxDescBytes = DATA_SIZE;
  } else {
    RxDescBytes = Len;
  }

  RxBuf = AllocatePool (DATA_SIZE);
  ASSERT (RxBuf != NULL);

  InvalidateDataCacheRange (RxBuf, Len);

  gDmaDesc->status.b.bs = 0x3;
  gDmaDesc->status.b.mtrf = 0;
  gDmaDesc->status.b.sr = 0;
  gDmaDesc->status.b.l = 1;
  gDmaDesc->status.b.ioc = 1;
  gDmaDesc->status.b.sp = 0;
  gDmaDesc->status.b.bytes = (UINT32)RxDescBytes;
  gDmaDesc->buf = (UINT32)((UINTN)RxBuf);
  gDmaDesc->status.b.sts = 0;
  gDmaDesc->status.b.bs = 0x0;

  ArmDataSynchronizationBarrier ();
  WRITE_REG32 (DOEPDMA (Ep), (UINT32)((UINTN)gDmaDesc));
  /* EPx OUT ENABLE CLEARNAK */
  WRITE_REG32 (DOEPCTL (Ep), (READ_REG32 (DOEPCTL (Ep)) | DXEPCTL_EPENA | DXEPCTL_CNAK));
}

STATIC
EFI_STATUS
HandleGetDescriptor (
  IN USB_DEVICE_REQUEST  *Request
  )
{
  UINT8       DescriptorType;
  UINTN       ResponseSize;
  VOID       *ResponseData;
  EFI_USB_STRING_DESCRIPTOR        *Descriptor = NULL;
  UINTN                             DescriptorSize;

  ResponseSize = 0;
  ResponseData = NULL;

  // Pretty confused if bmRequestType is anything but this:
  ASSERT (Request->RequestType == USB_DEV_GET_DESCRIPTOR_REQ_TYPE);

  // Choose the response
  DescriptorType = Request->Value >> 8;
  switch (DescriptorType) {
  case USB_DESC_TYPE_DEVICE:
    DEBUG ((DEBUG_INFO, "USB: Got a request for device descriptor\n"));
    ResponseSize = sizeof (USB_DEVICE_DESCRIPTOR);
    ResponseData = mDeviceDescriptor;
    break;
  case USB_DESC_TYPE_CONFIG:
    DEBUG ((DEBUG_INFO, "USB: Got a request for config descriptor\n"));
    ResponseSize = mConfigDescriptor->TotalLength;
    ResponseData = mDescriptors;
    break;
  case USB_DESC_TYPE_STRING:
    DEBUG ((DEBUG_INFO, "USB: Got a request for String descriptor %d\n", Request->Value & 0xFF));
    switch (Request->Value & 0xff) {
    case 0:
      DescriptorSize = sizeof (EFI_USB_STRING_DESCRIPTOR) +
                       LANG_LENGTH * sizeof (CHAR16) + 1;
      Descriptor = (EFI_USB_STRING_DESCRIPTOR *)AllocateZeroPool (DescriptorSize);
      ASSERT (Descriptor != NULL);
      Descriptor->Length = LANG_LENGTH * sizeof (CHAR16);
      Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
      DwUsb->GetLang (Descriptor->String, &Descriptor->Length);
      ResponseSize = Descriptor->Length;
      ResponseData = Descriptor;
      break;
    case 1:
      DescriptorSize = sizeof (EFI_USB_STRING_DESCRIPTOR) +
                       MANU_FACTURER_STRING_LENGTH * sizeof (CHAR16) + 1;
      Descriptor = (EFI_USB_STRING_DESCRIPTOR *)AllocateZeroPool (DescriptorSize);
      ASSERT (Descriptor != NULL);
      Descriptor->Length = MANU_FACTURER_STRING_LENGTH * sizeof (CHAR16);
      Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
      DwUsb->GetManuFacturer (Descriptor->String, &Descriptor->Length);
      ResponseSize = Descriptor->Length;
      ResponseData = Descriptor;
      break;
    case 2:
      DescriptorSize = sizeof (EFI_USB_STRING_DESCRIPTOR) +
                       PRODUCT_STRING_LENGTH * sizeof (CHAR16) + 1;
      Descriptor = (EFI_USB_STRING_DESCRIPTOR *)AllocateZeroPool (DescriptorSize);
      ASSERT (Descriptor != NULL);
      Descriptor->Length = PRODUCT_STRING_LENGTH * sizeof (CHAR16);
      Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
      DwUsb->GetProduct (Descriptor->String, &Descriptor->Length);
      ResponseSize = Descriptor->Length;
      ResponseData = Descriptor;
      break;
    case 3:
      DescriptorSize = sizeof (EFI_USB_STRING_DESCRIPTOR) +
                       SERIAL_STRING_LENGTH * sizeof (CHAR16) + 1;
      Descriptor = (EFI_USB_STRING_DESCRIPTOR *)AllocateZeroPool (DescriptorSize);
      ASSERT (Descriptor != NULL);
      Descriptor->Length = SERIAL_STRING_LENGTH * sizeof (CHAR16);
      Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
      DwUsb->GetSerialNo (Descriptor->String, &Descriptor->Length);
      ResponseSize = Descriptor->Length;
      ResponseData = Descriptor;
      break;
    }
    break;
  default:
    DEBUG ((DEBUG_INFO, "USB: Didn't understand request for descriptor 0x%04x\n", Request->Value));
    break;
  }

  // Send the response
  if (ResponseData) {
    ASSERT (ResponseSize != 0);

    if (Request->Length < ResponseSize) {
      // Truncate response
      ResponseSize = Request->Length;
    } else if (Request->Length > ResponseSize) {
      DEBUG ((DEBUG_INFO, "USB: Info: ResponseSize < wLength\n"));
    }

    EpTx (0, ResponseData, ResponseSize);
  }
  if (Descriptor) {
    FreePool (Descriptor);
  }

  return EFI_SUCCESS;
}

STATIC
EFI_STATUS
HandleSetAddress (
  IN USB_DEVICE_REQUEST  *Request
  )
{
  // Pretty confused if bmRequestType is anything but this:
  ASSERT (Request->RequestType == USB_DEV_SET_ADDRESS_REQ_TYPE);
  DEBUG ((DEBUG_INFO, "USB: Setting address to %d\n", Request->Value));
  ResetEndpoints ();

  WRITE_REG32 (DCFG, (READ_REG32 (DCFG) & ~DCFG_DEVADDR_MASK) | DCFG_DEVADDR(Request->Value));
  EpTx (0, 0, 0);

  return EFI_SUCCESS;
}

STATIC
UINTN
UsbDrvRequestEndpoint (
  IN UINTN           Type,
  IN UINTN           Dir
  )
{
  UINTN              Ep = 1;
  UINTN              Ret, NewBits;

  Ret = Ep | Dir;
  NewBits = (Type << 18) | 0x10000000;

  /*
   * (Type << 18):Endpoint Type (EPType)
   * 0x10000000:Endpoint Enable (EPEna)
   * 0x000C000:Endpoint Type (EPType);Hardcoded to 00 for control.
   * (ep<<22):TxFIFO Number (TxFNum)
   * 0x20000:NAK Status (NAKSts);The core is transmitting NAK handshakes on this endpoint.
   */
  if (Dir) {  // IN: to host
    WRITE_REG32 (DIEPCTL (Ep), ((READ_REG32 (DIEPCTL (Ep))) & ~DXEPCTL_EPTYPE_MASK) | NewBits | (Ep << 22) | DXEPCTL_NAKSTS);
  } else {    // OUT: to device
    WRITE_REG32 (DOEPCTL (Ep), ((READ_REG32 (DOEPCTL (Ep))) & ~DXEPCTL_EPTYPE_MASK) | NewBits);
  }

  return Ret;
}

STATIC
EFI_STATUS
HandleSetConfiguration (
  IN USB_DEVICE_REQUEST  *Request
  )
{
  ASSERT (Request->RequestType == USB_DEV_SET_CONFIGURATION_REQ_TYPE);

  // Cancel all transfers
  ResetEndpoints ();

  UsbDrvRequestEndpoint (2, 0);
  UsbDrvRequestEndpoint (2, 0x80);

  WRITE_REG32 (DIEPCTL1, (READ_REG32 (DIEPCTL1)) | BIT28 | BIT19 | DXEPCTL_USBACTEP | BIT11);

  /* Enable interrupts on all endpoints */
  WRITE_REG32 (DAINTMSK, ~0);

  EpRx (1, CMD_SIZE);
  EpTx (0, 0, 0);
  return EFI_SUCCESS;
}


STATIC
EFI_STATUS
HandleDeviceRequest (
  IN USB_DEVICE_REQUEST  *Request
  )
{
  EFI_STATUS  Status;

  switch (Request->Request) {
  case USB_DEV_GET_DESCRIPTOR:
    Status = HandleGetDescriptor (Request);
    break;
  case USB_DEV_SET_ADDRESS:
    Status = HandleSetAddress (Request);
    break;
  case USB_DEV_SET_CONFIGURATION:
    Status = HandleSetConfiguration (Request);
    break;
  default:
    DEBUG ((DEBUG_ERROR,
      "Didn't understand RequestType 0x%x Request 0x%x\n",
      Request->RequestType, Request->Request));
      Status = EFI_INVALID_PARAMETER;
    break;
  }

  return Status;
}


// Instead of actually registering interrupt handlers, we poll the controller's
//  interrupt source register in this function.
STATIC
VOID
CheckInterrupts (
  IN EFI_EVENT        Event,
  IN VOID            *Context
  )
{
  UINT32              Ints, EpInts;


  // interrupt register
  Ints = READ_REG32 (GINTSTS);

  /*
   * bus reset
   * The core sets this bit to indicate that a reset is detected on the USB.
   */
  if (Ints & GINTSTS_USBRST) {
    WRITE_REG32 (DCFG, DCFG_DESCDMA | DCFG_NZ_STS_OUT_HSHK);
    ResetEndpoints ();
  }

  /*
   * enumeration done, we now know the speed
   * The core sets this bit to indicate that speed enumeration is complete. The
   * application must read the Device Status (DSTS) register to obtain the
   * enumerated speed.
   */
  if (Ints & GINTSTS_ENUMDONE) {
    /* Set up the maximum packet sizes accordingly */
    UINTN MaxPacket = UsbDrvPortSpeed () ? USB_BLOCK_HIGH_SPEED_SIZE : 64;
    //Set Maximum In Packet Size (MPS)
    WRITE_REG32 (DIEPCTL1, ((READ_REG32 (DIEPCTL1)) & ~DXEPCTL_MPS_MASK) | MaxPacket);
    //Set Maximum Out Packet Size (MPS)
    WRITE_REG32 (DOEPCTL1, ((READ_REG32 (DOEPCTL1)) & ~DXEPCTL_MPS_MASK) | MaxPacket);
  }

  /*
   * IN EP event
   * The core sets this bit to indicate that an interrupt is pending on one of the IN
   * endpoInts of the core (in Device mode). The application must read the
   * Device All EndpoInts Interrupt (DAINT) register to determine the exact
   * number of the IN endpoint on which the interrupt occurred, and then read
   * the corresponding Device IN Endpoint-n Interrupt (DIEPINTn) register to
   * determine the exact cause of the interrupt. The application must clear the
   * appropriate status bit in the corresponding DIEPINTn register to clear this bit.
   */
  if (Ints & GINTSTS_IEPINT) {
    EpInts = READ_REG32 (DIEPINT0);
    WRITE_REG32 (DIEPINT0, EpInts);
    if (EpInts & DXEPINT_XFERCOMPL) {
      DEBUG ((DEBUG_INFO, "INT: IN TX completed.DIEPTSIZ (0) = 0x%x.\n", READ_REG32 (DIEPTSIZ0)));
    }

    EpInts = READ_REG32 (DIEPINT1);
    WRITE_REG32 (DIEPINT1, EpInts);
    if (EpInts & DXEPINT_XFERCOMPL) {
      DEBUG ((DEBUG_INFO, "ep1: IN TX completed\n"));
    }
  }

  /*
   * OUT EP event
   * The core sets this bit to indicate that an interrupt is pending on one of the
   * OUT endpoints of the core (in Device mode). The application must read the
   * Device All EndpoInts Interrupt (DAINT) register to determine the exact
   * number of the OUT endpoint on which the interrupt occurred, and then read
   * the corresponding Device OUT Endpoint-n Interrupt (DOEPINTn) register
   * to determine the exact cause of the interrupt. The application must clear the
   * appropriate status bit in the corresponding DOEPINTn register to clear this bit.
   */
  if (Ints & GINTSTS_OEPINT) {
    /* indicates the status of an endpoint
     * with respect to USB- and AHB-related events. */
    EpInts = READ_REG32 (DOEPINT0);
    if (EpInts) {
      WRITE_REG32 (DOEPINT0, EpInts);
      if (EpInts & DXEPINT_XFERCOMPL) {
        DEBUG ((DEBUG_INFO, "INT: EP0 RX completed. DOEPTSIZ(0) = 0x%x.\n", READ_REG32 (DOEPTSIZ0)));
      }
      /*
       *
       IN Token Received When TxFIFO is Empty (INTknTXFEmp)
       * Indicates that an IN token was received when the associated TxFIFO (periodic/nonperiodic)
       * was empty. This interrupt is asserted on the endpoint for which the IN token
       * was received.
       */
      if (EpInts & BIT3) { /* SETUP phase done */
        WRITE_REG32 (DIEPCTL0, READ_REG32 (DIEPCTL0) | DXEPCTL_SNAK);
        WRITE_REG32 (DOEPCTL0, READ_REG32 (DOEPCTL0) | DXEPCTL_SNAK);
        /*clear IN EP intr*/
        WRITE_REG32 (DIEPINT0, ~0);
        HandleDeviceRequest((USB_DEVICE_REQUEST *)gCtrlReq);
      }

      /* Make sure EP0 OUT is set up to accept the next request */
      WRITE_REG32 (DOEPTSIZ0, DXEPTSIZ_SUPCNT(3) | DXEPTSIZ_PKTCNT(1) | DXEPTSIZ_XFERSIZE(64));
      /*
       * IN Token Received When TxFIFO is Empty (INTknTXFEmp)
       * Indicates that an IN token was received when the associated TxFIFO (periodic/nonperiodic)
       * was empty. This interrupt is asserted on the endpoint for which the IN token
       * was received.
       */
      gDmaDescEp0->status.b.bs = 0x3;
      gDmaDescEp0->status.b.mtrf = 0;
      gDmaDescEp0->status.b.sr = 0;
      gDmaDescEp0->status.b.l = 1;
      gDmaDescEp0->status.b.ioc = 1;
      gDmaDescEp0->status.b.sp = 0;
      gDmaDescEp0->status.b.bytes = 64;
      gDmaDescEp0->buf = (UINT32)(UINTN)(gCtrlReq);
      gDmaDescEp0->status.b.sts = 0;
      gDmaDescEp0->status.b.bs = 0x0;
      WRITE_REG32 (DOEPDMA0, (UINT32)(UINTN)(gDmaDescEp0));
      // endpoint enable; clear NAK
      WRITE_REG32 (DOEPCTL0, DXEPCTL_EPENA | DXEPCTL_CNAK);
    }

    EpInts = (READ_REG32 (DOEPINT1));
    if (EpInts) {
      WRITE_REG32 (DOEPINT1, EpInts);
      /* Transfer Completed Interrupt (XferCompl);Transfer completed */
      if (EpInts & DXEPINT_XFERCOMPL) {

        UINTN Bytes = RxDescBytes - gDmaDesc->status.b.bytes;
        UINTN Len = 0;

        ArmDataSynchronizationBarrier ();
        if (MATCH_CMD_LITERAL ("download:", RxBuf)) {
          mNumDataBytes = AsciiStrHexToUint64 (RxBuf + sizeof ("download:"));
        } else {
          if (mNumDataBytes != 0) {
            mNumDataBytes -= Bytes;
          }
        }

        mDataReceivedCallback (Bytes, RxBuf);

        if (mNumDataBytes == 0) {
          Len = CMD_SIZE;
        } else if (mNumDataBytes > DATA_SIZE) {
          Len = DATA_SIZE;
        } else {
          Len = mNumDataBytes;
        }

        EpRx (1, Len);
      }
    }
  }

  //WRITE_REG32 clear ints
  WRITE_REG32 (GINTSTS, Ints);
}

EFI_STATUS
DwUsbSend (
  IN        UINT8  EndpointIndex,
  IN        UINTN  Size,
  IN  CONST VOID  *Buffer
  )
{
    EpTx (EndpointIndex, Buffer, Size);
    return EFI_SUCCESS;
}

STATIC
VOID
DwUsbInit (
  VOID
  )
{
  VOID     *Buf;
  UINT32   Data;

  Buf = UncachedAllocatePages (16);
  gDmaDesc = Buf;
  gDmaDescEp0 = gDmaDesc + sizeof (dwc_otg_dev_dma_desc_t);
  gDmaDescIn = gDmaDescEp0 + sizeof (dwc_otg_dev_dma_desc_t);
  gCtrlReq = (USB_DEVICE_REQUEST *)gDmaDescIn + sizeof (dwc_otg_dev_dma_desc_t);

  ZeroMem (gDmaDesc, sizeof (dwc_otg_dev_dma_desc_t));
  ZeroMem (gDmaDescEp0, sizeof (dwc_otg_dev_dma_desc_t));
  ZeroMem (gDmaDescIn, sizeof (dwc_otg_dev_dma_desc_t));

  /*Reset usb controller.*/
  /* Wait for OTG AHB master idle */
  do {
    Data = READ_REG32 (GRSTCTL) & GRSTCTL_AHBIDLE;
  } while (Data == 0);

  /* OTG: Assert Software Reset */
  WRITE_REG32 (GRSTCTL, GRSTCTL_CSFTRST);

  /* Wait for OTG to ack reset */
  while (READ_REG32 (GRSTCTL) & GRSTCTL_CSFTRST);

  /* Wait for OTG AHB master idle */
  while ((READ_REG32 (GRSTCTL) & GRSTCTL_AHBIDLE) == 0);

  WRITE_REG32 (GDFIFOCFG, DATA_FIFO_CONFIG);
  WRITE_REG32 (GRXFSIZ, RX_SIZE);
  WRITE_REG32 (GNPTXFSIZ, ENDPOINT_TX_SIZE);
  WRITE_REG32 (DIEPTXF1, DATA_IN_ENDPOINT_TX_FIFO1);
  WRITE_REG32 (DIEPTXF2, DATA_IN_ENDPOINT_TX_FIFO2);
  WRITE_REG32 (DIEPTXF3, DATA_IN_ENDPOINT_TX_FIFO3);
  WRITE_REG32 (DIEPTXF4, DATA_IN_ENDPOINT_TX_FIFO4);
  WRITE_REG32 (DIEPTXF5, DATA_IN_ENDPOINT_TX_FIFO5);
  WRITE_REG32 (DIEPTXF6, DATA_IN_ENDPOINT_TX_FIFO6);
  WRITE_REG32 (DIEPTXF7, DATA_IN_ENDPOINT_TX_FIFO7);
  WRITE_REG32 (DIEPTXF8, DATA_IN_ENDPOINT_TX_FIFO8);
  WRITE_REG32 (DIEPTXF9, DATA_IN_ENDPOINT_TX_FIFO9);
  WRITE_REG32 (DIEPTXF10, DATA_IN_ENDPOINT_TX_FIFO10);
  WRITE_REG32 (DIEPTXF11, DATA_IN_ENDPOINT_TX_FIFO11);
  WRITE_REG32 (DIEPTXF12, DATA_IN_ENDPOINT_TX_FIFO12);
  WRITE_REG32 (DIEPTXF13, DATA_IN_ENDPOINT_TX_FIFO13);
  WRITE_REG32 (DIEPTXF14, DATA_IN_ENDPOINT_TX_FIFO14);
  WRITE_REG32 (DIEPTXF15, DATA_IN_ENDPOINT_TX_FIFO15);

  /*
   * set Periodic TxFIFO Empty Level,
   * Non-Periodic TxFIFO Empty Level,
   * Enable DMA, Unmask Global Intr
   */
  WRITE_REG32 (GAHBCFG, GAHBCFG_CTRL_MASK);

  /*select 8bit UTMI+, ULPI Inerface*/
  WRITE_REG32 (GUSBCFG, 0x2400);

  /* Detect usb work mode,host or device? */
  do {
    Data = READ_REG32 (GINTSTS);
  } while (Data & GINTSTS_CURMODE_HOST);
  MicroSecondDelay (3);

  /*Init global and device mode csr register.*/
  /*set Non-Zero-Length status out handshake */
  Data = (0x20 << DCFG_EPMISCNT_SHIFT) | DCFG_NZ_STS_OUT_HSHK;
  WRITE_REG32 (DCFG, Data);

  /* Interrupt unmask: IN event, OUT event, bus reset */
  Data = GINTSTS_OEPINT | GINTSTS_IEPINT | GINTSTS_ENUMDONE | GINTSTS_USBRST;
  WRITE_REG32 (GINTMSK, Data);

  do {
    Data = READ_REG32 (GINTSTS) & GINTSTS_ENUMDONE;
  } while (Data);

  /* Clear any pending OTG Interrupts */
  WRITE_REG32 (GOTGINT, ~0);
  /* Clear any pending interrupts */
  WRITE_REG32 (GINTSTS, ~0);
  WRITE_REG32 (GINTMSK, ~0);
  Data = READ_REG32 (GOTGINT);
  Data &= ~0x3000;
  WRITE_REG32 (GOTGINT, Data);

  /* endpoint settings cfg */
  ResetEndpoints ();
  MicroSecondDelay (1);

  /* init finish. and ready to transfer data */

  /* Soft Disconnect */
  WRITE_REG32 (DCTL, DCTL_PWRONPRGDONE | DCTL_SFTDISCON);
  MicroSecondDelay (10000);

  /* Soft Reconnect */
  WRITE_REG32 (DCTL, DCTL_PWRONPRGDONE);
}

EFI_STATUS
EFIAPI
DwUsbStart (
  IN USB_DEVICE_DESCRIPTOR   *DeviceDescriptor,
  IN VOID                   **Descriptors,
  IN USB_DEVICE_RX_CALLBACK   RxCallback,
  IN USB_DEVICE_TX_CALLBACK   TxCallback
  )
{
  UINT8                    *Ptr;
  EFI_STATUS                Status;
  EFI_EVENT                 TimerEvent;

  ASSERT (DeviceDescriptor != NULL);
  ASSERT (Descriptors[0] != NULL);
  ASSERT (RxCallback != NULL);
  ASSERT (TxCallback != NULL);

  DwUsbInit();

  mDeviceDescriptor = DeviceDescriptor;
  mDescriptors = Descriptors[0];

  // Right now we just support one configuration
  ASSERT (mDeviceDescriptor->NumConfigurations == 1);
  mDeviceDescriptor->StrManufacturer = 1;
  mDeviceDescriptor->StrProduct = 2;
  mDeviceDescriptor->StrSerialNumber = 3;
  // ... and one interface
  mConfigDescriptor = (USB_CONFIG_DESCRIPTOR *)mDescriptors;
  ASSERT (mConfigDescriptor->NumInterfaces == 1);

  Ptr = ((UINT8 *) mDescriptors) + sizeof (USB_CONFIG_DESCRIPTOR);
  mInterfaceDescriptor = (USB_INTERFACE_DESCRIPTOR *) Ptr;
  Ptr += sizeof (USB_INTERFACE_DESCRIPTOR);

  mEndpointDescriptors = (USB_ENDPOINT_DESCRIPTOR *) Ptr;

  mDataReceivedCallback = RxCallback;
  mDataSentCallback = TxCallback;

  // Register a timer event so CheckInterupts gets called periodically
  Status = gBS->CreateEvent (
                  EVT_TIMER | EVT_NOTIFY_SIGNAL,
                  TPL_CALLBACK,
                  CheckInterrupts,
                  NULL,
                  &TimerEvent
                  );
  ASSERT_EFI_ERROR (Status);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Status = gBS->SetTimer (
                  TimerEvent,
                  TimerPeriodic,
                  DW_INTERRUPT_POLL_PERIOD
                  );
  ASSERT_EFI_ERROR (Status);

  return Status;
}

USB_DEVICE_PROTOCOL mUsbDevice = {
  DwUsbStart,
  DwUsbSend
};


EFI_STATUS
EFIAPI
DwUsbEntryPoint (
  IN EFI_HANDLE                            ImageHandle,
  IN EFI_SYSTEM_TABLE                      *SystemTable
  )
{
  EFI_STATUS      Status;

  Status = gBS->LocateProtocol (&gDwUsbProtocolGuid, NULL, (VOID **) &DwUsb);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Status = DwUsb->PhyInit(USB_DEVICE_MODE);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  return gBS->InstallProtocolInterface (
                &ImageHandle,
                &gUsbDeviceProtocolGuid,
                EFI_NATIVE_INTERFACE,
                &mUsbDevice
                );
}
