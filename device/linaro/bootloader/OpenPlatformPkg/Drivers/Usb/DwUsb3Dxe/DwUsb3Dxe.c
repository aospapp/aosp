/** @file

  Copyright (c) 2017, Linaro Limited. All rights reserved.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include <IndustryStandard/Usb.h>
#include <Library/ArmLib.h>
#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/CacheMaintenanceLib.h>
#include <Library/DebugLib.h>
#include <Library/IoLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/TimerLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiDriverEntryPoint.h>
#include <Library/UefiRuntimeServicesTableLib.h>
#include <Library/UncachedMemoryAllocationLib.h>
#include <Protocol/DwUsb.h>
#include <Protocol/UsbDevice.h>

#include "DwUsb3Dxe.h"

#define FIFO_DIR_TX                  0
#define FIFO_DIR_RX                  1

#define TX_FIFO_ADDR                 0
#define RX_FIFO_ADDR                 0

#define RAM_WIDTH                    8
#define RAM_TX0_DEPTH                2048
#define RAM_TX1_DEPTH                4096
#define RAM_RX_DEPTH                 8192

#define USB_TYPE_LENGTH              16
#define USB_BLOCK_HIGH_SPEED_SIZE    512
#define DATA_SIZE                    131072
#define CMD_SIZE                     512
#define MATCH_CMD_LITERAL(Cmd, Buf) !AsciiStrnCmp (Cmd, Buf, sizeof (Cmd) - 1)

// The time between interrupt polls, in units of 100 nanoseconds
// 10 Microseconds
#define DW_INTERRUPT_POLL_PERIOD     100

#define DWUSB3_EVENT_BUF_SIZE        256

// Maxpacket size for EP0, defined by USB3 spec
#define USB3_MAX_EP0_SIZE            512

// Maxpacket size for any EP, defined by USB3 spec
#define USB3_MAX_PACKET_SIZE         1024
#define USB2_HS_MAX_PACKET_SIZE      512
#define USB2_FS_MAX_PACKET_SIZE      64

#define USB3_STATE_UNCONNECTED       0
#define USB3_STATE_DEFAULT           1
#define USB3_STATE_ADDRESSED         2
#define USB3_STATE_CONFIGURED        3

#define GET_EVENTBUF_COUNT()       (GEVNTCOUNT_EVNTCOUNT (MmioRead32 (GEVNTCOUNT (0))))
#define UPDATE_EVENTBUF_COUNT(x)   (MmioWrite32 (GEVNTCOUNT (0), GEVNTCOUNT_EVNTCOUNT (x)))
#define CLEAR_EVENTBUF()           do {                                                \
                                     MmioOr32 (GEVNTSIZ (0), GEVNTSIZ_EVNTINTMASK);    \
                                     MmioOr32 (GEVNTCOUNT (0), 0);                     \
                                   } while (0)

#define SET_DEVADDR(x)             (MmioAndThenOr32 (DCFG, ~DCFG_DEVADDR_MASK, DCFG_DEVADDR (x)))

EFI_GUID  gDwUsbProtocolGuid = DW_USB_PROTOCOL_GUID;

STATIC DW_USB_PROTOCOL          *DwUsb;

STATIC usb3_pcd_t               gPcd;
STATIC UINT32                   *gEventBuf, *gEventPtr;
STATIC struct usb_device_descriptor   gDwUsb3DevDesc;
STATIC VOID                     *gRxBuf;

STATIC usb_setup_pkt_t                *gEndPoint0SetupPacket;
#define USB3_STATUS_BUF_SIZE    512
STATIC UINT8                          *gEndPoint0StatusBuf;
STATIC USB_DEVICE_RX_CALLBACK         mDataReceivedCallback;
STATIC UINTN                          mDataBufferSize;
/*
	UINT8 ep0_status_buf[USB3_STATUS_BUF_SIZE];
*/

struct usb_interface_descriptor intf = {
  sizeof (struct usb_interface_descriptor),
  UDESC_INTERFACE,
  0,
  0,
  2,
  USB_CLASS_VENDOR_SPEC,
  0x42,
  0x03,
  0
};

const struct usb_ss_ep_comp_descriptor ep_comp = {
  sizeof (struct usb_ss_ep_comp_descriptor),
  UDESC_SS_USB_COMPANION,
  0,
  0,
  0
};

const struct usb_endpoint_descriptor hs_bulk_in = {
  sizeof (struct usb_endpoint_descriptor),
  UDESC_ENDPOINT,
  UE_DIR_IN | USB3_BULK_IN_EP,
  USB_ENDPOINT_XFER_BULK,
  0x200,
  0
};

const struct usb_endpoint_descriptor
hs_bulk_out = {
	sizeof(struct usb_endpoint_descriptor), /* bLength */
	UDESC_ENDPOINT, /* bDescriptorType */

	UE_DIR_OUT | USB3_BULK_OUT_EP, /* bEndpointAddress */
	USB_ENDPOINT_XFER_BULK, /* bmAttributes */
	0x200, /* wMaxPacketSize: 512 of high-speed */
	1, /* bInterval */
};

const struct usb_endpoint_descriptor ss_bulk_in = {
	sizeof(struct usb_endpoint_descriptor), /* bLength */
	UDESC_ENDPOINT, /* bDescriptorType */

	UE_DIR_IN | USB3_BULK_IN_EP, /* bEndpointAddress */
	USB_ENDPOINT_XFER_BULK, /* bmAttributes */
	0x400, /* wMaxPacketSize: 1024 of super-speed */
	0, /* bInterval */
};

const struct usb_endpoint_descriptor ss_bulk_out = {
	sizeof(struct usb_endpoint_descriptor), /* bLength */
	UDESC_ENDPOINT, /* bDescriptorType */

	UE_DIR_OUT | USB3_BULK_OUT_EP, /* bEndpointAddress */
	USB_ENDPOINT_XFER_BULK, /* bmAttributes */
	0x400, /* wMaxPacketSize: 1024 of super-speed */
	0, /* bInterval */
};

/** The BOS Descriptor */

const struct usb_dev_cap_20_ext_desc cap1 = {
	sizeof(struct usb_dev_cap_20_ext_desc),	/* bLength */
	UDESC_DEVICE_CAPABILITY,		/* bDescriptorType */
	USB_DEVICE_CAPABILITY_20_EXTENSION,	/* bDevCapabilityType */
	0x2,				/* bmAttributes */
};

const struct usb_dev_cap_ss_usb
cap2 = {
	sizeof(struct usb_dev_cap_ss_usb),	/* bLength */
	UDESC_DEVICE_CAPABILITY,		/* bDescriptorType */
	USB_DEVICE_CAPABILITY_SS_USB,		/* bDevCapabilityType */
	0x0,					/* bmAttributes */
	(USB_DC_SS_USB_SPEED_SUPPORT_SS |
	    USB_DC_SS_USB_SPEED_SUPPORT_HIGH),   /* wSpeedsSupported */
	0x2,					/* bFunctionalitySupport */
	/* @todo set these to correct value */
	0xa,					/* bU1DevExitLat */
	0x100,				/* wU2DevExitLat */
};

const struct usb_dev_cap_container_id
cap3 = {
	sizeof(struct usb_dev_cap_container_id),/* bLength */
	UDESC_DEVICE_CAPABILITY,		/* bDescriptorType */
	USB_DEVICE_CAPABILITY_CONTAINER_ID,	/* bDevCapabilityType */
	0,					/* bReserved */
	/* @todo Create UUID */
	{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, /* containerID */
};

const struct wusb_bos_desc
bos = {
	sizeof(struct wusb_bos_desc),		/* bLength */
	UDESC_BOS,				/* bDescriptorType */
	(sizeof(struct wusb_bos_desc)	/* wTotalLength */
	    + sizeof(cap1) + sizeof(cap2) + sizeof(cap3)),
	3,					/* bNumDeviceCaps */
};

STATIC struct usb_enum_port_param usb_port_activity_config = {
  .idVendor           = USB_ENUM_ADB_PORT_VID,
  .idProduct          = USB_ENUM_ADB_PORT_PID,
  .bInterfaceSubClass = USB_ENUM_INTERFACE_ADB_SUBCLASS,
  .bInterfaceProtocol = USB_ENUM_INTERFACE_ADB_PROTOCOL
};

STATIC
UINT32
DwUsb3GetEventBufEvent (
  IN UINTN               Size
  )
{
  UINT32                 Event;

  Event = *gEventPtr++;
  if ((UINT32)(UINTN)gEventPtr >= (UINT32)(UINTN)gEventBuf + Size) {
    gEventPtr = gEventBuf;
  }
  return Event;
}

STATIC
VOID
DwUsb3SetFifoSize (
  IN UINT32              Addr,
  IN UINT32              Depth,
  IN UINT32              Dir,
  IN UINT32              FifoNum
  )
{
  UINT32                 Reg;

  if (Dir == FIFO_DIR_TX) {
    Reg = GTXFIFOSIZ (FifoNum);
  } else if (Dir == FIFO_DIR_RX) {
    Reg = GRXFIFOSIZ (FifoNum);
  } else {
    ASSERT (0);
  }
  MmioWrite32 (Reg, FIFOSIZ_DEP (Depth) | FIFOSIZ_ADDR (Addr));
}

STATIC
UINT32
Handshake (
  IN UINT32              Reg,
  IN UINT32              Mask,
  IN UINT32              Done
  )
{
  UINT32                 Timeout = 100000;

  do {
    if ((MmioRead32 (Reg) & Mask) == Done) {
      return 1;
    }
    MicroSecondDelay (1);
  } while (Timeout-- > 0);
  return 0;
}

STATIC
VOID
DwUsb3FillDesc (
  IN usb3_dma_desc_t     *desc,
  IN UINT64              dma_addr,
  IN UINT32              dma_len,
  IN UINT32              stream,
  IN UINT32              type,
  IN UINT32              ctrlbits,
  IN UINT32              own
  )
{
  desc->bptl = (UINT32)(dma_addr & 0xFFFFFFFF);
  desc->bpth = (UINT32)(dma_addr >> 32);
  desc->status = DSCSTS_XFERCNT (dma_len);
  if (type) {
    desc->control = DSCCTL_TRBCTL (type);
  }
  desc->control |= DSCCTL_STRMID_SOFN (stream) | ctrlbits;
  ArmDataSynchronizationBarrier ();
  /* must execute this operation at last */
  if (own) {
    desc->control |= DSCCTL_HWO;
  }
  ArmDataSynchronizationBarrier ();
}

STATIC
VOID
DwUsb3DepStartNewCfg (
  IN UINT32              EpIdx,
  IN UINT32              RsrcIdx
  )
{
  /* start the command */
  MmioWrite32 (
    DEPCMD (EpIdx),
    DEPCMD_XFER_RSRC_IDX (RsrcIdx) | DEPCMD_CMDTYPE (CMDTYPE_START_NEW_CFG) | DEPCMD_CMDACT
    );
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
}

STATIC
VOID
DwUsb3DepCfg (
  IN UINT32              EpIdx,
  IN UINT32              DepCfg0,
  IN UINT32              DepCfg1,
  IN UINT32              DepCfg2
  )
{
  MmioWrite32 (DEPCMDPAR2 (EpIdx), DepCfg2);
  MmioWrite32 (DEPCMDPAR1 (EpIdx), DepCfg1);
  MmioWrite32 (DEPCMDPAR0 (EpIdx), DepCfg0);
  MmioWrite32 (DEPCMD (EpIdx), DEPCMD_CMDTYPE (CMDTYPE_SET_EP_CFG) | DEPCMD_CMDACT);
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
}

STATIC
VOID
DwUsb3DepXferCfg (
  IN UINT32              EpIdx,
  IN UINT32              DepStrmCfg
  )
{
  MmioWrite32 (DEPCMDPAR0 (EpIdx), DepStrmCfg);
  MmioWrite32 (DEPCMD (EpIdx), DEPCMD_CMDTYPE (CMDTYPE_SET_XFER_CFG) | DEPCMD_CMDACT);
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
}

STATIC
UINT8
DwUsb3DepStartXfer (
  IN UINT32              EpIdx,
  IN UINT64              DmaAddr,
  IN UINT32              StreamOrUf
  )
{
  UINT32                 Data;

  MmioWrite32 (DEPCMDPAR1 (EpIdx), (UINT32)DmaAddr);
  MmioWrite32 (DEPCMDPAR0 (EpIdx), (UINT32)(DmaAddr >> 32));
  MmioWrite32 (
    DEPCMD (EpIdx),
    DEPCMD_STR_NUM_OR_UF (StreamOrUf) | DEPCMD_CMDTYPE (CMDTYPE_START_XFER) | DEPCMD_CMDACT
    );
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
  Data = MmioRead32 (DEPCMD (EpIdx));
  return GET_DEPCMD_XFER_RSRC_IDX(Data);
}

STATIC
VOID
DwUsb3DepStopXfer (
  IN UINT32               EpIdx,
  IN UINT32               Tri
  )
{
  MmioWrite32 (DEPCMDPAR2 (EpIdx), 0);
  MmioWrite32 (DEPCMDPAR1 (EpIdx), 0);
  MmioWrite32 (DEPCMDPAR0 (EpIdx), 0);
  MmioWrite32 (
    DEPCMD (EpIdx),
    DEPCMD_XFER_RSRC_IDX (Tri) | DEPCMD_CMDTYPE (CMDTYPE_END_XFER) | DEPCMD_CMDACT
    );
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
}

VOID
DwUsb3DepUpdateXfer (
  IN UINT32               EpIdx,
  IN UINT32               Tri
  )
{
  MmioWrite32 (
    DEPCMD (EpIdx),
    DEPCMD_XFER_RSRC_IDX (Tri) | DEPCMD_CMDTYPE (CMDTYPE_UPDATE_XFER) | DEPCMD_CMDACT
    );
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
}

STATIC
VOID
DwUsb3DepClearStall (
  IN UINTN            EpIdx
  )
{
  MmioWrite32 (DEPCMD (EpIdx), DEPCMD_CMDTYPE (CMDTYPE_CLR_STALL) | DEPCMD_CMDACT);
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
}


STATIC
VOID
DwUsb3DepSetStall (
  IN UINTN            EpIdx
  )
{
  MmioWrite32 (DEPCMD (EpIdx), DEPCMD_CMDTYPE (CMDTYPE_SET_STALL) | DEPCMD_CMDACT);
  Handshake (DEPCMD (EpIdx), DEPCMD_CMDACT, 0);
}

STATIC
VOID
DwUsb3EnableEp (
  IN UINT32                EpIdx,
  IN usb3_pcd_ep_t         *ep
  )
{
  UINT32                   Dalepena;

  Dalepena = MmioRead32 (DALEPENA);
  /* If the EP is already enabled, skip to set it again. */
  if (Dalepena & (1 << EpIdx)) {
    return;
  }
  Dalepena |= 1 << EpIdx;
  MmioWrite32 (DALEPENA, Dalepena);
}

STATIC
VOID
DwUsb3Ep0Activate (
  IN OUT usb3_pcd_t         *pcd
  )
{
  /* issue DEPCFG command to EP0 OUT */
  DwUsb3DepStartNewCfg (EP_OUT_IDX (0), 0);
  DwUsb3DepCfg (
    EP_OUT_IDX (0),
    EPCFG0_EPTYPE (EPTYPE_CONTROL) | EPCFG0_MPS (512),
    EPCFG1_XFER_CMPL | EPCFG1_XFER_NRDY,
    0
    );
  /* issue DEPSTRMCFG command to EP0 OUT */
  DwUsb3DepXferCfg (EP_OUT_IDX (0), 1);  // one stream
  /* issue DEPCFG command to EP0 IN */
  DwUsb3DepCfg (
    EP_IN_IDX (0),
    EPCFG0_EPTYPE (EPTYPE_CONTROL)  | EPCFG0_MPS (512) | EPCFG0_TXFNUM (pcd->ep0.tx_fifo_num),
    EPCFG1_XFER_NRDY | EPCFG1_XFER_CMPL | EPCFG1_EP_DIR_IN,
    0
    );
  /* issue DEPSTRMCFG command to EP0 IN */
  DwUsb3DepXferCfg (EP_IN_IDX (0), 1);  // one stream
  pcd->ep0.active = 1;
}

STATIC
VOID
DwUsb3EpActivate (
  IN OUT usb3_pcd_t         *pcd,
  IN OUT usb3_pcd_ep_t      *ep
  )
{
  UINT32                    EpIdx, DepCfg0, DepCfg1;
  if (ep->is_in) {
    EpIdx = EP_IN_IDX (ep->num);
  } else {
    EpIdx = EP_OUT_IDX (ep->num);
  }

  /* Start a new configurate when enable the first EP. */
  if (!pcd->eps_enabled) {
    pcd->eps_enabled = 1;
    /* Issue DEPCFG command to physical EP1 (logical EP0 IN) first.
     * It resets the core's Tx FIFO mapping table.
     */
    DepCfg0 = EPCFG0_EPTYPE (EPTYPE_CONTROL);
    DepCfg0 |= EPCFG0_CFG_ACTION (CFG_ACTION_MODIFY);
    DepCfg1 = EPCFG1_XFER_CMPL | EPCFG1_XFER_NRDY | EPCFG1_EP_DIR_IN;

    switch (pcd->speed) {
    case USB_SPEED_SUPER:
      DepCfg0 |= EPCFG0_MPS (512);
      break;
    case USB_SPEED_HIGH:
    case USB_SPEED_FULL:
      DepCfg0 |= EPCFG0_MPS (64);
      break;
    case USB_SPEED_LOW:
      DepCfg0 |= EPCFG0_MPS (8);
      break;
    default:
      ASSERT (0);
      break;
    }
    DwUsb3DepCfg (EP_IN_IDX (0), DepCfg0, DepCfg1, 0);
    DwUsb3DepStartNewCfg (EP_OUT_IDX (0), 2);
  }
  /* issue DEPCFG command to EP */
  DepCfg0 = EPCFG0_EPTYPE (ep->type);
  DepCfg0 |= EPCFG0_MPS (ep->maxpacket);
  if (ep->is_in) {
    DepCfg0 |= EPCFG0_TXFNUM (ep->tx_fifo_num);
  }
  DepCfg0 |= EPCFG0_BRSTSIZ (ep->maxburst);
  DepCfg1 = EPCFG1_EP_NUM (ep->num);
  if (ep->is_in) {
    DepCfg1 |= EPCFG1_EP_DIR_IN;
  } else {
    DepCfg1 |= EPCFG1_XFER_CMPL;
  }
  DwUsb3DepCfg (EpIdx, DepCfg0, DepCfg1, 0);
  /* issue DEPSTRMCFG command to EP */
  DwUsb3DepXferCfg (EpIdx, 1);
  DwUsb3EnableEp (EpIdx, ep);
  ep->active = 1;
}

STATIC
VOID
DwUsb3Ep0OutStart (
  IN usb3_pcd_t          *pcd
  )
{
  usb3_dma_desc_t        *desc;

  /* Get the SETUP packet DMA Descriptor (TRB) */
  desc = pcd->ep0_setup_desc;

  /* DMA Descriptor setup */
  DwUsb3FillDesc (
    desc,
    (UINT64)gEndPoint0SetupPacket,
    pcd->ep0.maxpacket,
    0,
    TRBCTL_SETUP,
    DSCCTL_IOC | DSCCTL_ISP | DSCCTL_LST,
    1
    );

  /* issue DEPSTRTXFER command to EP0 OUT */
  pcd->ep0.tri_out = DwUsb3DepStartXfer (EP_OUT_IDX (0), (UINT64)desc, 0);
}

STATIC
VOID
DwUsb3Init (
  VOID
  )
{
  UINT32                 Data, Addr;
  usb3_pcd_t             *pcd = &gPcd;

  /* soft reset the usb core */
  do {
    MmioAndThenOr32 (DCTL, ~DCTL_RUN_STOP, DCTL_CSFTRST);

    do {
      MicroSecondDelay (1000);
      Data = MmioRead32 (DCTL);
    } while (Data & DCTL_CSFTRST);
    // wait for at least 3 PHY clocks
    MicroSecondDelay (1000);
  } while (0);

  pcd->link_state = 0;

  /* TI PHY: Set Turnaround Time = 9 (8-bit UTMI+ / ULPI) */
  MmioAndThenOr32 (GUSB2PHYCFG (0), ~GUSB2PHYCFG_USBTRDTIM_MASK, GUSB2PHYCFG_USBTRDTIM (9));

  /* set TX FIFO size */
  Addr = TX_FIFO_ADDR;
  DwUsb3SetFifoSize (Addr, RAM_TX0_DEPTH / RAM_WIDTH, FIFO_DIR_TX, 0);
  Addr += RAM_TX0_DEPTH / RAM_WIDTH;
  DwUsb3SetFifoSize (Addr, RAM_TX1_DEPTH / RAM_WIDTH, FIFO_DIR_TX, 1);
  /* set RX FIFO size */
  DwUsb3SetFifoSize (RX_FIFO_ADDR, RAM_RX_DEPTH / RAM_WIDTH, FIFO_DIR_RX, 0);

  /* set LFPS filter delay1trans */
  MmioAndThenOr32 (
    GUSB3PIPECTL (0),
    ~PIPECTL_DELAYP1TRANS,
    PIPECTL_LFPS_FILTER | PIPECTL_TX_DEMPH (1)
    );

  /* set GCTL */
  Data = GCTL_U2EXIT_LFPS | GCTL_PRTCAPDIR_DEVICE | GCTL_U2RSTECN |
         GCTL_PWRDNSCALE(2);
  MmioWrite32 (GCTL, Data);

  /* init event buf */
  MmioWrite32 (GEVNTADRL(0), (UINT32)(UINTN)gEventBuf);
  MmioWrite32 (GEVNTADRH(0), (UINTN)gEventBuf >> 32);
  MmioWrite32 (GEVNTSIZ(0), DWUSB3_EVENT_BUF_SIZE << 2);
  MmioWrite32 (GEVNTCOUNT(0), 0);

  /* set max speed to super speed */
  MmioAndThenOr32 (
    DCFG,
    ~DCFG_DEVSPD_MASK,
    DCFG_DEVSPD (DEVSPD_SS_PHY_125MHZ_OR_250MHZ)
    );

  /* set nump */
  MmioAndThenOr32 (DCFG, ~DCFG_NUMP_MASK, DCFG_NUMP (16));

  /* init address */
  SET_DEVADDR (0);

  /* disable phy suspend */
  MmioAnd32 (GUSB3PIPECTL (0), ~PIPECTL_SUSPEND_EN);
  MmioAnd32 (GUSB2PHYCFG (0), ~GUSB2PHYCFG_SUSPHY);

  /* clear any pending interrupts */
#if 0
  CLEAR_EVENTBUF ();
#else
  Data = MmioRead32 (GEVNTCOUNT (0));
  MmioWrite32 (GEVNTCOUNT (0), Data);
#endif
  /* enable device interrupts */
  MmioWrite32 (DEVTEN, DEVTEN_CONNECTDONEEN | DEVTEN_USBRSTEN);
  /* activate EP0 */
  DwUsb3Ep0Activate (pcd);
  /* start EP0 to receive SETUP packets */
  DwUsb3Ep0OutStart (pcd);

  /* enable EP0 OUT/IN in DALEPENA */
  MmioWrite32 (DALEPENA, (1 << EP_OUT_IDX (0)) | (1 << EP_IN_IDX (0)));

  /* set RUN/STOP bit */
  MmioOr32 (DCTL, DCTL_RUN_STOP);
}

#define ALIGN(x, a)     (((x) + ((a) - 1)) & ~((a) - 1))

STATIC
VOID
DriverInit (
  VOID
  )
{
  usb3_pcd_t          *pcd = &gPcd;
  usb3_pcd_ep_t       *ep;

  pcd->speed = USB_SPEED_UNKNOWN;

  // init EP0
  ep = &pcd->ep0;
  ep->pcd = pcd;
  ep->stopped = 1;
  ep->is_in = 0;
  ep->active = 0;
  ep->phys = 0;
  ep->num = 0;
  ep->tx_fifo_num = 0;
  ep->type = EPTYPE_CONTROL;
  ep->maxburst = 0;
  ep->maxpacket = USB3_MAX_EP0_SIZE;
  ep->send_zlp = 0;
  ep->req.length = 0;
  ep->req.actual = 0;
  pcd->ep0_req.length = 0;
  pcd->ep0_req.actual = 0;

  // init EP1 OUT
  ep = &pcd->out_ep;
  ep->pcd = pcd;
  ep->stopped = 1;
  ep->is_in = 0;
  ep->active = 0;
  ep->phys = USB3_BULK_OUT_EP << 1;
  ep->num = 1;
  ep->tx_fifo_num = 0;
  // bulk ep is activated
  ep->type = EPTYPE_BULK;
  ep->maxburst = 0;
  ep->maxpacket = USB3_MAX_PACKET_SIZE;
  ep->send_zlp = 0;
  ep->req.length = 0;
  ep->req.actual = 0;

  // init EP1 IN
  ep = &pcd->in_ep;
  ep->stopped = 1;
  ep->is_in = 1;
  ep->active = 0;
  ep->phys = (USB3_BULK_IN_EP << 1) | 1;
  ep->num = 1;
  ep->tx_fifo_num = USB3_BULK_IN_EP;
  // bulk ep is activated
  ep->type = EPTYPE_BULK;
  ep->maxburst = 0;
  ep->maxpacket = USB3_MAX_PACKET_SIZE;
  ep->send_zlp = 0;
  ep->req.length = 0;
  ep->req.actual = 0;

  pcd->ep0state = EP0_IDLE;
  pcd->ep0.maxpacket = USB3_MAX_EP0_SIZE;
  pcd->ep0.type = EPTYPE_CONTROL;

#if 0
  pcd->ep0_setup_desc = (usb3_dma_desc_t *)ALIGN ((UINTN)pcd->ep0_setup, 16);
  pcd->ep0_in_desc = (usb3_dma_desc_t *)ALIGN ((UINTN)pcd->ep0_in, 16);
  pcd->ep0_out_desc = (usb3_dma_desc_t *)ALIGN ((UINTN)pcd->ep0_out, 16);
  pcd->in_ep.ep_desc = (usb3_dma_desc_t *)ALIGN ((UINTN)pcd->in_ep.epx_desc, 16);
  pcd->out_ep.ep_desc = (usb3_dma_desc_t *)ALIGN ((UINTN)pcd->out_ep.epx_desc, 16);
#else
  pcd->ep0_setup_desc = (usb3_dma_desc_t *)UncachedAllocateAlignedZeroPool (64, 64);
  pcd->ep0_in_desc = (usb3_dma_desc_t *)UncachedAllocateAlignedZeroPool (64, 64);
  pcd->ep0_out_desc = (usb3_dma_desc_t *)UncachedAllocateAlignedZeroPool (64, 64);
  pcd->in_ep.ep_desc = (usb3_dma_desc_t *)UncachedAllocateAlignedZeroPool (64, 64);
  pcd->out_ep.ep_desc = (usb3_dma_desc_t *)UncachedAllocateAlignedZeroPool (64, 64);
#endif
}

STATIC
VOID
DwUsb3HandleUsbResetInterrupt (
  IN usb3_pcd_t       *pcd
  )
{
  usb3_pcd_ep_t        *ep;

  // clear stall on each EP
  ep = &pcd->in_ep;
  if (ep->xfer_started) {
    if (ep->is_in) {
      DwUsb3DepStopXfer (EP_IN_IDX (ep->num), ep->tri_in);
    } else {
      DwUsb3DepStopXfer (EP_OUT_IDX (ep->num), ep->tri_out);
    }
  }
  if (ep->stopped) {
    if (ep->is_in) {
      DwUsb3DepClearStall (EP_IN_IDX (ep->num));
    } else {
      DwUsb3DepClearStall (EP_OUT_IDX (ep->num));
    }
  }

  ep = &pcd->out_ep;
  if (ep->xfer_started) {
    if (ep->is_in) {
      DwUsb3DepStopXfer (EP_IN_IDX (ep->num), ep->tri_in);
    } else {
      DwUsb3DepStopXfer (EP_OUT_IDX (ep->num), ep->tri_out);
    }
  }
  if (ep->stopped) {
    if (ep->is_in) {
      DwUsb3DepClearStall (EP_IN_IDX (ep->num));
    } else {
      DwUsb3DepClearStall (EP_OUT_IDX (ep->num));
    }
  }

  // set device address to 0
  SET_DEVADDR (0);

  pcd->ltm_enable = 0;
  DEBUG ((DEBUG_INFO, "usb reset\n"));
}

STATIC
UINT32
DwUsb3GetDeviceSpeed (
  IN usb3_pcd_t         *pcd
  )
{
  UINT32                Data, Speed;

  Data = MmioRead32 (DSTS);
  switch (DSTS_GET_DEVSPD (Data)) {
  case DEVSPD_HS_PHY_30MHZ_OR_60MHZ:
    Speed = USB_SPEED_HIGH;
    break;
  case DEVSPD_FS_PHY_30MHZ_OR_60MHZ:
  case DEVSPD_FS_PHY_48MHZ:
    Speed = USB_SPEED_FULL;
    break;
  case DEVSPD_LS_PHY_6MHZ:
    Speed = USB_SPEED_LOW;
    break;
  case DEVSPD_SS_PHY_125MHZ_OR_250MHZ:
    Speed = USB_SPEED_SUPER;
    break;
  default:
    DEBUG ((DEBUG_ERROR, "DwUsb3GetDeviceSpeed: invalid DSTS:0x%x\n", Data));
    Speed = USB_SPEED_UNKNOWN;
    break;
  }
  return Speed;
}

STATIC
VOID
DwUsb3PcdSetSpeed (
  IN usb3_pcd_t         *pcd,
  IN UINTN              speed
  )
{
  // set the MPS of EP0 based on the connection speed
  switch (speed) {
  case USB_SPEED_SUPER:
    pcd->ep0.maxpacket = 512;
    pcd->in_ep.maxpacket = USB3_MAX_PACKET_SIZE;
    pcd->out_ep.maxpacket = USB3_MAX_PACKET_SIZE;
    break;
  case USB_SPEED_HIGH:
    pcd->ep0.maxpacket = 64;
    pcd->in_ep.maxpacket = USB2_HS_MAX_PACKET_SIZE;
    pcd->out_ep.maxpacket = USB2_HS_MAX_PACKET_SIZE;
    break;
  case USB_SPEED_FULL:
    pcd->ep0.maxpacket = 64;
    pcd->in_ep.maxpacket = USB2_FS_MAX_PACKET_SIZE;
    pcd->out_ep.maxpacket = USB2_FS_MAX_PACKET_SIZE;
    break;
  default:
    DEBUG ((DEBUG_ERROR, "invalid speed: %d\n", speed));
    break;
  }
}

STATIC
VOID
DwUsb3HandleConnectDoneInterrupt (
  IN usb3_pcd_t         *pcd
  )
{
  usb3_pcd_ep_t         *ep0 = &pcd->ep0;
  UINT32                DiepCfg0, DoepCfg0, DiepCfg1, DoepCfg1;
  UINT32                Speed;

  ep0->stopped = 0;
  Speed = (UINT32)DwUsb3GetDeviceSpeed (pcd);
  pcd->speed = (UINT8)Speed;

  DwUsb3PcdSetSpeed (pcd, Speed);
  // set the MPS of EP0 based on the connection speed
  DiepCfg0 = EPCFG0_EPTYPE (EPTYPE_CONTROL) | EPCFG0_CFG_ACTION (CFG_ACTION_MODIFY);
  DiepCfg1 = EPCFG1_XFER_CMPL | EPCFG1_XFER_NRDY | EPCFG1_EP_DIR_IN;
  DoepCfg0 = EPCFG0_EPTYPE (EPTYPE_CONTROL) | EPCFG0_CFG_ACTION (CFG_ACTION_MODIFY);
  DoepCfg1 = EPCFG1_XFER_CMPL | EPCFG1_XFER_NRDY;

  switch (Speed) {
  case USB_SPEED_SUPER:
    DiepCfg0 |= EPCFG0_MPS (512);
    DoepCfg0 |= EPCFG0_MPS (512);
    break;
  case USB_SPEED_HIGH:
  case USB_SPEED_FULL:
    DiepCfg0 |= EPCFG0_MPS (64);
    DoepCfg0 |= EPCFG0_MPS (64);
    break;
  case USB_SPEED_LOW:
    DiepCfg0 |= EPCFG0_MPS (8);
    DoepCfg0 |= EPCFG0_MPS (8);
    break;
  default:
    DEBUG ((DEBUG_ERROR, "DwUsb3HandleConnectDoneInterrupt: invalid speed %d\n", Speed));
    break;
  }
  DiepCfg0 |= EPCFG0_TXFNUM (ep0->tx_fifo_num);
  // issue DEPCFG command to EP0 OUT
  DwUsb3DepCfg (EP_OUT_IDX (0), DoepCfg0, DoepCfg1, 0);
  // issue DEPCFG command to EP0 IN
  DwUsb3DepCfg (EP_IN_IDX (0), DiepCfg0, DiepCfg1, 0);
  pcd->state = USB3_STATE_DEFAULT;
}

STATIC
VOID
DwUsb3HandleDeviceInterrupt (
  IN usb3_pcd_t       *pcd,
  IN UINT32           Event
  )
{
  switch (Event & GEVNT_DEVT_MASK) {
  case GEVNT_DEVT_USBRESET:
    DwUsb3HandleUsbResetInterrupt (pcd);
    break;
  case GEVNT_DEVT_CONNDONE:
    DwUsb3HandleConnectDoneInterrupt (pcd);
    break;
  default:
    DEBUG ((DEBUG_ERROR, "DwUsb3HandleDeviceInterrupt: invalid event\n"));
    break;
  }
}

STATIC
usb3_pcd_ep_t *
DwUsb3GetOutEndPoint (
  IN usb3_pcd_t       *pcd,
  IN UINT32           EndPointNum
  )
{
  if (EndPointNum == 0) {
    return &pcd->ep0;
  }
  return &pcd->out_ep;
}

STATIC
usb3_pcd_ep_t *
DwUsb3GetInEndPoint (
  IN usb3_pcd_t       *pcd,
  IN UINT32           EndPointNum
  )
{
  if (EndPointNum == 0) {
    return &pcd->ep0;
  }
  return &pcd->in_ep;
}

STATIC
VOID
EndPoint0DoStall (
  IN usb3_pcd_t       *pcd
  )
{
  usb3_pcd_ep_t       *ep0 = &pcd->ep0;

  // stall EP0 IN & OUT simultanelusly
  ep0->is_in = 1;
  DwUsb3DepSetStall (EP_IN_IDX (0));
  ep0->is_in = 0;
  DwUsb3DepSetStall (EP_OUT_IDX (0));
  // prepare for the next setup transfer
  ep0->stopped = 1;
  pcd->ep0state = EP0_IDLE;
  DwUsb3Ep0OutStart (pcd);
}

STATIC
VOID
EndPoint0ContinueTransfer (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_req_t   *req
  )
{
  usb3_pcd_ep_t       *ep0 = &pcd->ep0;
  usb3_dma_desc_t     *desc;
  UINT64              desc_dma;
  UINT8               tri;

  // send a 0-byte length packet after the end of transfer
  if (ep0->is_in) {
    desc = pcd->ep0_in_desc;
    desc_dma = (UINT64)pcd->ep0_in_desc;
    // DMA descriptor setup
    DwUsb3FillDesc (
      desc,
      (UINT64)req->bufdma,
      0,
      0,
      TRBCTL_NORMAL,
      DSCCTL_IOC | DSCCTL_ISP | DSCCTL_LST,
      1
      );
    tri = DwUsb3DepStartXfer (EP_IN_IDX (0), desc_dma, 0);
    ep0->tri_in = tri;
  }
}

STATIC
VOID
EndPoint0CompleteRequest (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_req_t   *req,
  IN usb3_dma_desc_t  *desc
  )
{
  usb3_pcd_ep_t      *ep = &pcd->ep0;

  if (req == NULL) {
    return;
  }

  if ((pcd->ep0state == EP0_OUT_DATA_PHASE) ||
      (pcd->ep0state == EP0_IN_DATA_PHASE)) {
    if (ep->is_in) {
      if (GET_DSCSTS_XFERCNT (desc->status) == 0) {
        pcd->ep0.is_in = 0;
        pcd->ep0state = EP0_OUT_WAIT_NRDY;
      }
    } else {
      pcd->ep0.is_in = 1;
      pcd->ep0state = EP0_IN_WAIT_NRDY;
    }
  }
}

STATIC
VOID
DwUsb3OsGetTrb (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_ep_t    *ep,
  IN usb3_pcd_req_t   *req
  )
{
  // If EP0, fill request with EP0 IN/OUT data TRB
  if (ep == &pcd->ep0) {
    if (ep->is_in) {
      req->trb = pcd->ep0_in_desc;
      req->trbdma = (UINT64)pcd->ep0_in_desc;
    } else {
      req->trb = pcd->ep0_out_desc;
      req->trbdma = (UINT64)pcd->ep0_out_desc;
    }
  } else {
    // fill request with TRB from the non-EP0 allocation
    req->trb = ep->ep_desc;
    req->trbdma = (UINT64)ep->ep_desc;
  }
}

STATIC
VOID
DwUsb3EndPoint0StartTransfer (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_req_t   *req
  )
{
  usb3_pcd_ep_t       *ep0 = &pcd->ep0;
  usb3_dma_desc_t     *desc;
  UINT64              desc_dma;
  UINT32              desc_type, len;

  // get the DMA descriptor (TRB) for this request
  DwUsb3OsGetTrb (pcd, ep0, req);
  desc = req->trb;
  desc_dma = req->trbdma;

  if (ep0->is_in) {
    // start DMA on EP0 IN
    // DMA Descriptor (TRB) setup
    len = req->length;
    if (pcd->ep0state == EP0_IN_STATUS_PHASE) {
      if (ep0->three_stage) {
        desc_type = TRBCTL_STATUS_3;
      } else {
        desc_type = TRBCTL_STATUS_2;
      }
    } else {
      desc_type = TRBCTL_CTLDATA_1ST;
    }
    DwUsb3FillDesc (
      desc,
      (UINT64)req->bufdma,
      len,
      0,
      desc_type,
      DSCCTL_IOC | DSCCTL_ISP | DSCCTL_LST,
      1
      );
    // issue DEPSTRTXFER command to EP0 IN
    ep0->tri_in = DwUsb3DepStartXfer (EP_IN_IDX (0), desc_dma, 0);
  } else {
    // start DMA on EP0 OUT
    // DMA Descriptor (TRB) setup
    len = ALIGN (req->length, ep0->maxpacket);
    if (pcd->ep0state == EP0_OUT_STATUS_PHASE) {
      if (ep0->three_stage) {
        desc_type = TRBCTL_STATUS_3;
      } else {
        desc_type = TRBCTL_STATUS_2;
      }
    } else {
      desc_type = TRBCTL_CTLDATA_1ST;
    }
    DwUsb3FillDesc (
      desc,
      (UINT64)req->bufdma,
      len,
      0,
      desc_type,
      DSCCTL_IOC | DSCCTL_ISP | DSCCTL_LST,
      1
      );
    // issue DEPSTRTXFER command to EP0 OUT
    ep0->tri_out = DwUsb3DepStartXfer (EP_OUT_IDX (0), desc_dma, 0);
  }
}

STATIC
INTN
DwUsb3EndPointXStartTransfer (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_ep_t    *ep
  )
{
  usb3_pcd_req_t      *req = &ep->req;
  usb3_dma_desc_t     *desc;
  UINT64              desc_dma;
  UINT32              len;

  // get the TRB for this request
  DwUsb3OsGetTrb (pcd, ep, req);
  desc = req->trb;
  desc_dma = req->trbdma;

  if (ep->is_in) {
    // For IN, TRB length is just xfer length
    len = req->length;
    if (ep->xfer_started && !(desc->control & DSCCTL_HWO)) {
      DEBUG ((DEBUG_INFO, "[%a] last tx succ, but not in 10s!\n", __func__));
      ep->xfer_started = 0;
    }
  } else {
    // For OUT, TRB length must be multiple of maxpacket
    // must be power of 2, use cheap AND
    len = (req->length + ep->maxpacket - 1) & ~(ep->maxpacket - 1);
    req->length = len;
  }
  // DMA descriptor setup
  DwUsb3FillDesc (
    desc,
    (UINT64)req->bufdma,
    len,
    0,
    TRBCTL_NORMAL,
    DSCCTL_IOC | DSCCTL_ISP | DSCCTL_LST,
    1
    );
  if (ep->is_in) {
    // start DMA on EPn IN
    if (ep->xfer_started) {
      // issue DEPUPDTXFER command to EP
      DwUsb3DepUpdateXfer (EP_IN_IDX (ep->num), ep->tri_in);
    } else {
      ep->tri_in = DwUsb3DepStartXfer (EP_IN_IDX (ep->num), desc_dma, 0);
      ep->xfer_started = 1;
    }
  } else {
    // start DMA on EPn OUT
    if (ep->xfer_started) {
      // issue DEPUPDTXFER command to EP
      DwUsb3DepUpdateXfer (EP_OUT_IDX (ep->num), ep->tri_out);
    } else {
      ep->tri_out = DwUsb3DepStartXfer (EP_OUT_IDX (ep->num), desc_dma, 0);
      ep->xfer_started = 1;
    }
  }
  if (ep->is_in) {
    UINT32       count = 0;
    // wait until send complete
    while ((desc->control & DSCCTL_HWO) && (count < 1000000)) {
      MicroSecondDelay (10);
      count++;
    }
    if (count >= 1000000) {
      DEBUG ((DEBUG_INFO, "[%a]: ep%d transfer timeout!\n", __func__, ep->num));
      DEBUG ((DEBUG_INFO, "please disconnect then connect USB cable again to recovery!\n"));
      return -1;
    }
    ep->xfer_started = 0;
  }
  return 0;
}

#if 0
STATIC
VOID
DwUsb3EndPointXStopTransfer (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_ep_t    *ep
  )
{
  if (ep->is_in) {
    DwUsb3DepStopXfer (EP_IN_IDX (ep->num), ep->tri_in);
  } else {
    DwUsb3DepStopXfer (EP_OUT_IDX (ep->num), ep->tri_out);
  }
}
#endif

STATIC
VOID
SetupInStatusPhase (
  IN usb3_pcd_t       *pcd,
  IN VOID             *buf
  )
{
  usb3_pcd_ep_t       *ep0 = &pcd->ep0;

  if (pcd->ep0state == EP0_STALL)
    return;

  ep0->is_in = 1;
  pcd->ep0state = EP0_IN_STATUS_PHASE;
  pcd->ep0_req.bufdma = buf;
  pcd->ep0_req.length = 0;
  pcd->ep0_req.actual = 0;
  DwUsb3EndPoint0StartTransfer (pcd, &pcd->ep0_req);
}

STATIC
VOID
SetupOutStatusPhase (
  IN usb3_pcd_t       *pcd,
  IN VOID             *buf
  )
{
  usb3_pcd_ep_t       *ep0 = &pcd->ep0;

  if (pcd->ep0state == EP0_STALL)
    return;

  ep0->is_in = 0;
  pcd->ep0state = EP0_OUT_STATUS_PHASE;
  pcd->ep0_req.bufdma = buf;
  pcd->ep0_req.length = 0;
  pcd->ep0_req.actual = 0;
  DwUsb3EndPoint0StartTransfer (pcd, &pcd->ep0_req);
}

STATIC
VOID
DwUsb3HandleEndPoint0 (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_req_t   *req,
  IN UINT32           event
  )
{
  usb3_pcd_ep_t       *ep0 = &pcd->ep0;
  usb3_dma_desc_t     *desc;
  UINT32              byte_count, len;

  switch (pcd->ep0state) {
  case EP0_IN_DATA_PHASE:
    if (req == NULL) {
      req = &pcd->ep0_req;
    }
    desc = pcd->ep0_in_desc;

    if (desc->control & DSCCTL_HWO) {
      goto out;
    }

    if (GET_DSCSTS_TRBRSP (desc->status) == TRBRSP_SETUP_PEND) {
      // start of a new control transfer
      desc->status = 0;
    }
    byte_count = req->length - GET_DSCSTS_XFERCNT (desc->status);
    req->actual += byte_count;
    req->bufdma += byte_count;

    if (req->actual < req->length) {
      // IN CONTINUE, stall EP0
      EndPoint0DoStall (pcd);
    } else if (ep0->send_zlp) {
      // CONTINUE TRANSFER IN ZLP
      EndPoint0ContinueTransfer (pcd, req);
      ep0->send_zlp = 0;
    } else {
      // COMPLETE IN TRANSFER
      EndPoint0CompleteRequest (pcd, req, desc);
    }
    break;
  case EP0_OUT_DATA_PHASE:
    if (req == NULL) {
      req = &pcd->ep0_req;
    }
    desc = pcd->ep0_out_desc;

    if (desc->control & DSCCTL_HWO) {
      goto out;
    }

    if (GET_DSCSTS_TRBRSP (desc->status) == TRBRSP_SETUP_PEND) {
      // start of a new control transfer
      desc->status = 0;
    }
    len = (req->length + ep0->maxpacket - 1) & ~(ep0->maxpacket - 1);
    byte_count = len - GET_DSCSTS_XFERCNT (desc->status);
    req->actual += byte_count;
    req->bufdma += byte_count;

    if (req->actual < req->length) {
      // IN CONTINUE, stall EP0
      EndPoint0DoStall (pcd);
    } else if (ep0->send_zlp) {
      // CONTINUE TRANSFER IN ZLP
      EndPoint0ContinueTransfer (pcd, req);
      ep0->send_zlp = 0;
    } else {
      // COMPLETE IN TRANSFER
      EndPoint0CompleteRequest (pcd, req, desc);
    }
    break;
#if 0
  case EP0_IN_WAIT_NRDY:
  case EP0_OUT_WAIT_NRDY:
    if (ep0->is_in) {
      SetupInStatusPhase (pcd, gEndPoint0SetupPacket);
    } else {
      SetupOutStatusPhase (pcd, gEndPoint0SetupPacket);
    }
    break;
  case EP0_IN_STATUS_PHASE:
  case EP0_OUT_STATUS_PHASE:
    if (ep0->is_in) {
      desc = pcd->ep0_in_desc;
    } else {
      desc = pcd->ep0_out_desc;
    }
//ASSERT (0);
    EndPoint0CompleteRequest (pcd, req, desc);
    // skip test mode
    pcd->ep0state = EP0_IDLE;
    ep0->stopped = 1;
    ep0->is_in = 0;  // OUT for next SETUP
    // prepare for more SETUP packets
    DwUsb3Ep0OutStart (pcd);
    break;
#else
  case EP0_IN_WAIT_NRDY:
    if (ep0->is_in) {
      SetupInStatusPhase (pcd, gEndPoint0SetupPacket);
    } else {
      ASSERT (0);
    }
    break;
  case EP0_OUT_WAIT_NRDY:
    if (!ep0->is_in) {
      SetupOutStatusPhase (pcd, gEndPoint0SetupPacket);
    } else {
      ASSERT (0);
    }
    break;
  case EP0_IN_STATUS_PHASE:
    if (ep0->is_in) {
      desc = pcd->ep0_in_desc;
    } else {
      ASSERT (0);
    }
    EndPoint0CompleteRequest (pcd, req, desc);
    pcd->ep0state = EP0_IDLE;
    ep0->stopped = 1;
    ep0->is_in = 0;  // OUT for next SETUP
    // prepare for more SETUP packets
    DwUsb3Ep0OutStart (pcd);
    break;
  case EP0_OUT_STATUS_PHASE:
    if (!ep0->is_in) {
      desc = pcd->ep0_out_desc;
    } else {
      ASSERT (0);
    }
    EndPoint0CompleteRequest (pcd, req, desc);
    pcd->ep0state = EP0_IDLE;
    ep0->stopped = 1;
    ep0->is_in = 0;  // OUT for next SETUP
    // prepare for more SETUP packets
    DwUsb3Ep0OutStart (pcd);
    break;
#endif
  case EP0_STALL:
    break;
  case EP0_IDLE:
    break;
  default:
    DEBUG ((DEBUG_ERROR, "%a: invalid state %d\n", __func__, pcd->ep0state));
    break;
  }
out:
  return;
}

STATIC
usb3_pcd_ep_t *
Addr2EndPoint (
  IN usb3_pcd_t       *pcd,
  IN UINT16           index
  )
{
  UINT32              ep_num;

  ep_num = UE_GET_ADDR (index);
  if (ep_num == 0) {
    return &pcd->ep0;
  } else {
    if (UE_GET_DIR (index) == UE_DIR_IN) {
      return &pcd->in_ep;
    }
    return &pcd->out_ep;
  }
}

STATIC
VOID
DwUsb3DoGetStatus (
  IN usb3_pcd_t       *pcd
  )
{
  usb_device_request_t   *ctrl = &gEndPoint0SetupPacket->req;
  UINT8                  *status = gEndPoint0StatusBuf;
  usb3_pcd_ep_t          *ep;

  if (ctrl->wLength != 2) {
    EndPoint0DoStall (pcd);
    return;
  }

  switch (UT_GET_RECIPIENT (ctrl->bmRequestType)) {
  case UT_DEVICE:
    *status = 0;   // bus powered
    if (pcd->speed == USB_SPEED_SUPER) {
      if (pcd->state == USB3_STATE_CONFIGURED) {
        if (MmioRead32 (DCTL) & DCTL_INIT_U1_EN) {
          *status |= 1 << 2;
        }
        if (MmioRead32 (DCTL) & DCTL_INIT_U2_EN) {
          *status |= 1 << 3;
        }
        *status |= (UINT8)(pcd->ltm_enable << 4);
      }
    }
    *(status + 1) = 0;
    break;
  case UT_INTERFACE:
    *status = 0;
    *(status + 1) = 0;
    break;
  case UT_ENDPOINT:
    ep = Addr2EndPoint (pcd, ctrl->wIndex);
    *status = ep->stopped;
    *(status + 1) = 0;
    break;
  default:
    EndPoint0DoStall (pcd);
    return;
  }
  pcd->ep0_req.bufdma = (UINT64 *)status;
  pcd->ep0_req.length = 2;
  pcd->ep0_req.actual = 0;
  DwUsb3EndPoint0StartTransfer (pcd, &pcd->ep0_req);
}

STATIC
VOID
DoClearHalt (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_ep_t    *ep
  )
{
  if (ep->is_in) {
    DwUsb3DepClearStall (EP_IN_IDX (ep->num));
  } else {
    DwUsb3DepClearStall (EP_OUT_IDX (ep->num));
  }
  if (ep->stopped) {
    ep->stopped = 0;
  }
}

STATIC
VOID
Usb3PcdEpEnable (
  IN usb3_pcd_t        *pcd,
  IN usb3_pcd_ep_t     *ep
  )
{
  // activate the EP
  ep->stopped = 0;
  ep->xfer_started = 0;
  ep->ep_desc->control = 0;
  ep->ep_desc->status = 0;
  // set initial data pid.
  if (ep->type == EPTYPE_BULK) {
    ep->data_pid_start = 0;
  }
  DwUsb3EpActivate (pcd, ep);
}

STATIC
VOID
DwUsb3DoClearFeature (
  IN usb3_pcd_t       *pcd
  )
{
  usb_device_request_t  *ctrl = &gEndPoint0SetupPacket->req;
  usb3_pcd_ep_t  *ep;

  switch (UT_GET_RECIPIENT (ctrl->bmRequestType)) {
  case UT_DEVICE:
    switch (ctrl->wValue) {
    case UF_U1_ENABLE:
      if ((pcd->speed != USB_SPEED_SUPER) ||
          (pcd->state != USB3_STATE_CONFIGURED)) {
        EndPoint0DoStall (pcd);
        return;
      }
      MmioAnd32 (DCTL, ~DCTL_INIT_U1_EN);
      break;
    case UF_U2_ENABLE:
      if ((pcd->speed != USB_SPEED_SUPER) ||
          (pcd->state != USB3_STATE_CONFIGURED)) {
        EndPoint0DoStall (pcd);
        return;
      }
      MmioAnd32 (DCTL, ~DCTL_INIT_U2_EN);
      break;
    case UF_LTM_ENABLE:
      if ((pcd->speed != USB_SPEED_SUPER) ||
          (pcd->state != USB3_STATE_CONFIGURED) ||
          (ctrl->wIndex != 0)) {
        EndPoint0DoStall (pcd);
        return;
      }
      pcd->ltm_enable = 0;
      break;
    default:
      EndPoint0DoStall (pcd);
      return;
    }
    break;
  case UT_INTERFACE:
    // if FUNCTION_SUSPEND
    if (ctrl->wValue) {
      EndPoint0DoStall (pcd);
      return;
    }
    break;
  case UT_ENDPOINT:
    ep = Addr2EndPoint (pcd, ctrl->wIndex);
    if (ctrl->wValue != UF_ENDPOINT_HALT) {
      EndPoint0DoStall (pcd);
      return;
    }
    DoClearHalt (pcd, ep);
    break;
  default:
    DEBUG ((DEBUG_ERROR, "invalid bmRequestType :%d\n", UT_GET_RECIPIENT (ctrl->bmRequestType)));
    break;
  }
  pcd->ep0.is_in = 1;
  pcd->ep0state = EP0_IN_WAIT_NRDY;
}

STATIC
VOID
DwUsb3DoSetFeature (
  IN usb3_pcd_t       *pcd
  )
{
  usb_device_request_t  *ctrl = &gEndPoint0SetupPacket->req;
  usb3_pcd_ep_t  *ep;

  switch (UT_GET_RECIPIENT (ctrl->bmRequestType)) {
  case UT_DEVICE:
    switch (ctrl->wValue) {
    case UF_DEVICE_REMOTE_WAKEUP:
      break;
    case UF_TEST_MODE:
      pcd->test_mode_nr = ctrl->wIndex >> 8;
      pcd->test_mode = 1;
      break;
    case UF_U1_ENABLE:
      if ((pcd->speed != USB_SPEED_SUPER) ||
          (pcd->state != USB3_STATE_CONFIGURED)) {
        EndPoint0DoStall (pcd);
        return;
      }
      MmioOr32 (DCTL, DCTL_INIT_U1_EN);
      break;
    case UF_U2_ENABLE:
      if ((pcd->speed != USB_SPEED_SUPER) ||
          (pcd->state != USB3_STATE_CONFIGURED)) {
        EndPoint0DoStall (pcd);
        return;
      }
      MmioOr32 (DCTL, DCTL_INIT_U2_EN);
      break;
    case UF_LTM_ENABLE:
      if ((pcd->speed != USB_SPEED_SUPER) ||
          (pcd->state != USB3_STATE_CONFIGURED) ||
          (ctrl->wIndex != 0)) {
        EndPoint0DoStall (pcd);
        return;
      }
      pcd->ltm_enable = 1;
      break;
    default:
      EndPoint0DoStall (pcd);
      return;
    }
    break;
  case UT_INTERFACE:
    // if FUNCTION_SUSPEND
    if (ctrl->wValue) {
      EndPoint0DoStall (pcd);
      return;
    }
    break;
  case UT_ENDPOINT:
    ep = Addr2EndPoint (pcd, ctrl->wIndex);
    if (ctrl->wValue != UF_ENDPOINT_HALT) {
      EndPoint0DoStall (pcd);
      return;
    }
    ep->stopped = 1;
    if (ep->is_in) {
      DwUsb3DepClearStall (EP_IN_IDX (ep->num));
    } else {
      DwUsb3DepClearStall (EP_OUT_IDX (ep->num));
    }
    break;
  default:
    DEBUG ((DEBUG_ERROR, "invalid bmRequestType %d\n", UT_GET_RECIPIENT (ctrl->bmRequestType)));
    break;
  }
  pcd->ep0.is_in = 1;
  pcd->ep0state = EP0_IN_WAIT_NRDY;
}

STATIC
VOID
DwUsb3DoSetAddress (
  IN usb3_pcd_t          *pcd
  )
{
  usb_device_request_t *ctrl = &gEndPoint0SetupPacket->req;

  if (ctrl->bmRequestType == UT_DEVICE) {
    SET_DEVADDR (ctrl->wValue);
    pcd->ep0.is_in = 1;
    pcd->ep0state = EP0_IN_WAIT_NRDY;
    if (ctrl->wValue) {
      pcd->state = USB3_STATE_ADDRESSED;
    } else {
      pcd->state = USB3_STATE_DEFAULT;
    }
  }
}

#if 0
STATIC
UINTN
UsbStatus (
  IN UINTN                        online,
  IN UINTN                        speed
  )
{
  if (online) {
}

VOID
DwUsb3SetConfig (
  IN usb3_pcd_t           *pcd
  )
{
  usb_device_request_t   *ctrl = &gEndPoint0SetupPacket->req;
  UINT16         wvalue = ctrl->wValue;
  usb3_pcd_ep_t     *ep;

DEBUG ((DEBUG_ERROR, "#%a, %d, wvalue:0x%x\n", __func__, __LINE__, wvalue));
  if (ctrl->bmRequestType != (UT_WRITE | UT_STANDARD | UT_DEVICE)) {
    EndPoint0DoStall (pcd);
    return;
  }

  if (!wvalue || (wvalue == CONFIG_VALUE)) {
    UINT32         speed;
    pcd->new_config = (UINT8)wvalue;
    // set new configuration
    if (wvalue) {
      // activate bulk in endpoint
      ep = &pcd->in_ep;
      Usb3PcdEpEnable (pcd, ep);
      // activate bulk out endpoint
      ep = &pcd->out_ep;
      Usb3PcdEpEnable (pcd, ep);
      // prepare for next bulk transfer
      speed = DwUsb3GetDeviceSpeed (pcd);
      (VOID)speed;

#if 0
      if (g_usb_ops->status) {
        g_usb_ops->status (ctrl->wValue ? 1 : 0,
                           speed == USB_SPEED_SUPER ? USB_SS : speed == USB_SPEED_HIGH ? USB_HS : USB_FS);
      }
      usb_status ();
#endif
      pcd->state = USB3_STATE_CONFIGURED;
    } else {
      pcd->state = USB3_STATE_ADDRESSED;
    }
DEBUG ((DEBUG_ERROR, "#%a, %d, state:%d\n", __func__, __LINE__, pcd->state));
    pcd->ep0.is_in = 1;
    pcd->ep0state = EP0_IN_WAIT_NRDY;
  } else {
    EndPoint0DoStall (pcd);
  }
}
#endif

STATIC
VOID
DwUsb3DoGetConfig (
  IN usb3_pcd_t       *pcd
  )
{
  usb_device_request_t  *ctrl = &gEndPoint0SetupPacket->req;
  UINT8  *status = gEndPoint0StatusBuf;

  if (ctrl->bmRequestType != (UT_READ | UT_STANDARD | UT_DEVICE)) {
    EndPoint0DoStall (pcd);
    return;
  }
  // Notify host the current config value
  *status = pcd->new_config;
  pcd->ep0_req.bufdma = (UINT64 *)status;
  pcd->ep0_req.length = 1;
  pcd->ep0_req.actual = 0;
  DwUsb3EndPoint0StartTransfer (pcd, &pcd->ep0_req);
}

STATIC
VOID
DwUsb3DoSetConfig (
  IN usb3_pcd_t       *pcd
  )
{
  usb_device_request_t  *ctrl = &gEndPoint0SetupPacket->req;
  UINT16  wvalue = ctrl->wValue;
  usb3_pcd_ep_t  *ep;

  if (ctrl->bmRequestType != (UT_WRITE | UT_STANDARD | UT_DEVICE)) {
    EndPoint0DoStall (pcd);
    return;
  }

  if (!wvalue || (wvalue == CONFIG_VALUE)) {
    //UINT32 speed;

    pcd->new_config = (UINT8)wvalue;
    // set new configuration
    if (wvalue) {
      // activate bulk in endpoint
      ep = &pcd->in_ep;
      Usb3PcdEpEnable (pcd, ep);
      // activate bulk out endpoint
      ep = &pcd->out_ep;
      Usb3PcdEpEnable (pcd, ep);
#if 0
      // prepare for next bulk transfer
      speed = DwUsb3GetDeviceSpeed (pcd);
      (VOID)speed;
      g_usb_ops->status
#endif
      pcd->state = USB3_STATE_CONFIGURED;
      {
        // prepare for EP1 OUT
        usb3_pcd_ep_t                 *ep = &pcd->out_ep;
        usb3_pcd_req_t                *req = &ep->req;

        // AndroidFast App will free the rx buffer.
        gRxBuf = AllocatePool (DATA_SIZE);
        ASSERT (gRxBuf != NULL);
        WriteBackDataCacheRange (gRxBuf, DATA_SIZE);
        req->bufdma = (UINT64 *)gRxBuf;
        if (mDataBufferSize == 0) {
          req->length = CMD_SIZE;
        } else if (mDataBufferSize > DATA_SIZE) {
          req->length = DATA_SIZE;
          mDataBufferSize = mDataBufferSize - DATA_SIZE;
        } else if (mDataBufferSize > CMD_SIZE) {
          req->length = CMD_SIZE;
          mDataBufferSize = mDataBufferSize - CMD_SIZE;
        } else {
          req->length = mDataBufferSize;
          mDataBufferSize = 0;
        }
        DwUsb3EndPointXStartTransfer (pcd, ep);
      }
    } else {
      pcd->state = USB3_STATE_ADDRESSED;
    }
    pcd->ep0.is_in = 1;
    pcd->ep0state = EP0_IN_WAIT_NRDY;
  } else {
    EndPoint0DoStall (pcd);
  }
}

STATIC
VOID
DwUsb3DoGetDescriptor (
  IN usb3_pcd_t       *pcd
  )
{
  usb_device_request_t  *ctrl = &gEndPoint0SetupPacket->req;
  UINT8                 dt = ctrl->wValue >> 8;
  UINT8                 index = (UINT8)ctrl->wValue;
  UINT16                len = ctrl->wLength;
  UINT8                 *buf = gEndPoint0StatusBuf;
  UINT16                value = 0;
  EFI_USB_STRING_DESCRIPTOR        *Descriptor = NULL;

  if (ctrl->bmRequestType != (UT_READ | UT_STANDARD | UT_DEVICE)) {
    EndPoint0DoStall (pcd);
    return;
  }

  switch (dt) {
  case UDESC_DEVICE:
    {
      struct usb_device_descriptor  *dev = &gDwUsb3DevDesc;
      dev->bLength = sizeof (struct usb_device_descriptor);
      dev->bDescriptorType = UDESC_DEVICE;
      dev->bDeviceClass = 0;
      dev->bDeviceSubClass = 0;
      dev->bDeviceProtocol = 0;
      if (pcd->speed == USB_SPEED_SUPER) {
        dev->bcdUSB = 0x300;
        // 2^9 = 512
        dev->bMaxPacketSize0 = 9;
      } else {
        dev->bcdUSB = 0x0200;
        dev->bMaxPacketSize0 = 0x40;
      }
      dev->idVendor = usb_port_activity_config.idVendor;
      dev->idProduct = usb_port_activity_config.idProduct;
      dev->bcdDevice = 0x0100;
      dev->iManufacturer = STRING_MANUFACTURER;
      dev->iProduct = STRING_PRODUCT;
      dev->iSerialNumber = STRING_SERIAL;
      dev->bNumConfigurations = 1;
      value = sizeof (struct usb_device_descriptor);
      CopyMem ((void *)buf, (void *)dev, value);
    }
    break;
  case UDESC_DEVICE_QUALIFIER:
    {
      struct usb_qualifier_descriptor   *qual = (struct usb_qualifier_descriptor *)buf;
      struct usb_device_descriptor *dev = &gDwUsb3DevDesc;

      qual->bLength = sizeof (*qual);
      qual->bDescriptorType = UDESC_DEVICE_QUALIFIER;
      qual->bcdUSB = dev->bcdUSB;
      qual->bDeviceClass = dev->bDeviceClass;
      qual->bDeviceSubClass = dev->bDeviceSubClass;
      qual->bDeviceProtocol = dev->bDeviceProtocol;
      qual->bMaxPacketSize0 = dev->bMaxPacketSize0;
      qual->bNumConfigurations = 1;
      qual->bRESERVED = 0;
      value = sizeof (struct usb_qualifier_descriptor);
    }
    break;

  case UDESC_CONFIG:
    {
      struct usb_config_descriptor    *config = (struct usb_config_descriptor *)buf;

      config->bLength = sizeof (*config);
      config->bDescriptorType = UDESC_CONFIG;
      config->bNumInterfaces = 1;
      config->bConfigurationValue = 1;
      config->iConfiguration = 0;
      config->bmAttributes = USB_CONFIG_ATT_ONE;

      if (pcd->speed == USB_SPEED_SUPER) {
        config->bMaxPower = 0x50;
      } else {
        config->bMaxPower = 0x80;
      }
      buf += sizeof (*config);

      intf.bInterfaceSubClass = usb_port_activity_config.bInterfaceSubClass;
      intf.bInterfaceProtocol = usb_port_activity_config.bInterfaceProtocol;
      CopyMem ((void *)buf, (void *)&intf, sizeof (intf));
      buf += sizeof (intf);

      switch (pcd->speed) {
      case USB_SPEED_SUPER:
        CopyMem (buf, &ss_bulk_in, sizeof (ss_bulk_in));
        buf += sizeof (ss_bulk_in);
        CopyMem (buf, &ep_comp, sizeof (ep_comp));
        buf += sizeof (ep_comp);
        CopyMem (buf, &ss_bulk_out, sizeof (ss_bulk_out));
        buf += sizeof (ss_bulk_out);
        CopyMem (buf, &ep_comp, sizeof (ep_comp));

        config->wTotalLength = sizeof (*config) + sizeof (intf) + sizeof (ss_bulk_in) +
                               sizeof (ep_comp) + sizeof (ss_bulk_out) + sizeof (ep_comp);
        break;

      default: // HS/FS
        {
          struct usb_endpoint_descriptor  *endp = (struct usb_endpoint_descriptor *)buf;

          CopyMem (buf, &hs_bulk_in, sizeof (hs_bulk_in));
          (endp++)->wMaxPacketSize = pcd->in_ep.maxpacket;
          buf += sizeof (hs_bulk_in);
          CopyMem (buf, &hs_bulk_out, sizeof (hs_bulk_out));
          endp->wMaxPacketSize = pcd->out_ep.maxpacket;
          config->wTotalLength = sizeof (*config) + sizeof (intf) + sizeof (hs_bulk_in) +
                                 sizeof (hs_bulk_out);
          break;
        }
      }
      value = config->wTotalLength;
    }
    break;

  case UDESC_STRING:
    {
      switch (index) {
      case STRING_LANGUAGE:
        Descriptor = (EFI_USB_STRING_DESCRIPTOR *)(UINTN)gEndPoint0StatusBuf;
        ASSERT (Descriptor != NULL);
        Descriptor->Length = LANG_LENGTH * sizeof (CHAR16);
        Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
        DwUsb->GetLang (Descriptor->String, &Descriptor->Length);
        value = Descriptor->Length;
        break;
      case STRING_MANUFACTURER:
        Descriptor = (EFI_USB_STRING_DESCRIPTOR *)(UINTN)gEndPoint0StatusBuf;
        ASSERT (Descriptor != NULL);
        Descriptor->Length = MANU_FACTURER_STRING_LENGTH * sizeof (CHAR16);
        Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
        DwUsb->GetManuFacturer (Descriptor->String, &Descriptor->Length);
        value = Descriptor->Length;
        break;
      case STRING_PRODUCT:
        Descriptor = (EFI_USB_STRING_DESCRIPTOR *)(UINTN)gEndPoint0StatusBuf;
        ASSERT (Descriptor != NULL);
        Descriptor->Length = PRODUCT_STRING_LENGTH * sizeof (CHAR16);
        Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
        DwUsb->GetProduct (Descriptor->String, &Descriptor->Length);
        value = Descriptor->Length;
        break;
      case STRING_SERIAL:
        Descriptor = (EFI_USB_STRING_DESCRIPTOR *)(UINTN)gEndPoint0StatusBuf;
        ASSERT (Descriptor != NULL);
        Descriptor->Length = SERIAL_STRING_LENGTH * sizeof (CHAR16);
        Descriptor->DescriptorType = USB_DESC_TYPE_STRING;
        DwUsb->GetSerialNo (Descriptor->String, &Descriptor->Length);
        value = Descriptor->Length;
        break;
      default:
        EndPoint0DoStall (pcd);
        break;
      }
    }
    break;

  case UDESC_BOS:
    if (pcd->speed != USB_SPEED_SUPER) {
      EndPoint0DoStall (pcd);
      return;
    }
    value = bos.wTotalLength;
    CopyMem (buf, &bos, sizeof (bos));
    buf += sizeof (bos);
    CopyMem (buf, &cap1, sizeof (cap1));
    buf += sizeof (cap1);
    CopyMem (buf, &cap2, sizeof (cap2));
    buf += sizeof (cap2);
    CopyMem (buf, &cap3, sizeof (cap3));
    break;
  default:
    EndPoint0DoStall (pcd);
    return;
  }
  pcd->ep0_req.bufdma = (UINT64 *)gEndPoint0StatusBuf;
  pcd->ep0_req.length = value < len ? value : len;
  pcd->ep0_req.actual = 0;
  DwUsb3EndPoint0StartTransfer (pcd, &pcd->ep0_req);
}

STATIC
VOID
DwUsb3DoSetup (
  IN usb3_pcd_t       *pcd
  )
{
  usb_device_request_t  *ctrl = &gEndPoint0SetupPacket->req;
  usb3_pcd_ep_t         *ep0 = &pcd->ep0;
  UINT16                wLength;

  wLength = ctrl->wLength;
  ep0->stopped = 0;
  ep0->three_stage = 1;
  if (ctrl->bmRequestType & UE_DIR_IN) {
    ep0->is_in = 1;
    pcd->ep0state = EP0_IN_DATA_PHASE;
  } else {
    ep0->is_in = 0;
    pcd->ep0state = EP0_OUT_DATA_PHASE;
  }

  if (wLength == 0) {
    ep0->is_in = 1;
    pcd->ep0state = EP0_IN_WAIT_NRDY;
    ep0->three_stage = 0;
  }
  if (UT_GET_TYPE (ctrl->bmRequestType) != UT_STANDARD) {
    EndPoint0DoStall (pcd);
    return;
  }

  switch (ctrl->bRequest) {
  case UR_GET_STATUS:
    DwUsb3DoGetStatus (pcd);
    break;
  case UR_CLEAR_FEATURE:
    DwUsb3DoClearFeature (pcd);
    break;
  case UR_SET_FEATURE:
    DwUsb3DoSetFeature (pcd);
    break;
  case UR_SET_ADDRESS:
    DwUsb3DoSetAddress (pcd);
    break;
  case UR_SET_CONFIG:
    DwUsb3DoSetConfig (pcd);
    MmioOr32 (DCTL, DCTL_ACCEPT_U1_EN);
    MmioOr32 (DCTL, DCTL_ACCEPT_U2_EN);
    DEBUG ((DEBUG_INFO, "enum done"));
    pcd->ltm_enable = 0;
    break;
  case UR_GET_CONFIG:
    DwUsb3DoGetConfig (pcd);
    break;
  case UR_GET_DESCRIPTOR:
    DwUsb3DoGetDescriptor (pcd);
    break;
  case UR_SET_SEL:
    // for now this is a no-op
    pcd->ep0_req.bufdma = (UINT64 *)gEndPoint0StatusBuf;
    pcd->ep0_req.length = USB3_STATUS_BUF_SIZE;
    pcd->ep0_req.actual = 0;
    ep0->send_zlp = 0;
    DwUsb3EndPoint0StartTransfer (pcd, &pcd->ep0_req);
    break;
  default:
    EndPoint0DoStall (pcd);
    break;
  }
}

STATIC
VOID
DwUsb3OsHandleEndPoint0 (
  IN usb3_pcd_t       *pcd,
  IN UINT32           event
  )
{
  if (pcd->ep0state == EP0_IDLE) {
    DwUsb3DoSetup (pcd);
  } else {
    DwUsb3HandleEndPoint0 (pcd, NULL, event);
  }
}

STATIC
VOID
DwUsb3RequestDone (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_ep_t    *ep,
  IN usb3_pcd_req_t   *req,
  IN UINTN            status
  )
{
  if (ep != &pcd->ep0) {
    req->trb = NULL;
  }
  if (req->complete) {
    req->complete (req->actual, status);
  } else {
    if (!ep->is_in) {
      ASSERT (req->actual <= req->length);
      InvalidateDataCacheRange (gRxBuf, req->actual);
      mDataReceivedCallback (req->actual, gRxBuf);
    }
  }
  req->actual = 0;
}

STATIC
VOID
DwUsb3EndPointcompleteRequest (
  IN usb3_pcd_t       *pcd,
  IN usb3_pcd_ep_t    *ep,
  IN UINT32           event
  )
{
  usb3_pcd_req_t           *req = &ep->req;
  usb3_dma_desc_t          *desc = req->trb;
  UINT32 byte_count;

  ep->send_zlp = 0;
  if (!desc) {
    return;
  }

  if (desc->control & DSCCTL_HWO) {
    return;
  }

  if (ep->is_in) {
    // IN ep
    if (GET_DSCSTS_XFERCNT (desc->status) == 0) {
      req->actual += req->length;
    }
    // reset IN tri
    ep->tri_in = 0;
    // complete the IN request
    // flush for dma?
    DwUsb3RequestDone (pcd, ep, req, 0);
  } else {
    // OUT ep
    byte_count = req->length - GET_DSCSTS_XFERCNT (desc->status);
    req->actual += byte_count;
    //req->bufdma += byte_count;
    // reset OUT tri
    ep->tri_out = 0;
    // OUT transfer complete or not
    // complete the OUT request
    // FIXME flush dma?
    DwUsb3RequestDone (pcd, ep, req, 0);
    {
      // prepare for EP1 OUT
      usb3_pcd_ep_t                 *ep = &pcd->out_ep;
      usb3_pcd_req_t                *req = &ep->req;

      ZeroMem (req, sizeof (usb3_pcd_req_t));
      gRxBuf = AllocatePool (DATA_SIZE);
      ASSERT (gRxBuf != NULL);
      WriteBackDataCacheRange (gRxBuf, DATA_SIZE);
      req->bufdma = (UINT64 *)gRxBuf;
      if (mDataBufferSize == 0) {
        req->length = CMD_SIZE;
      } else if (mDataBufferSize > DATA_SIZE) {
        req->length = DATA_SIZE;
        mDataBufferSize = mDataBufferSize - DATA_SIZE;
      } else if (mDataBufferSize > CMD_SIZE) {
        req->length = CMD_SIZE;
        mDataBufferSize = mDataBufferSize - CMD_SIZE;
      } else {
        req->length = mDataBufferSize;
        mDataBufferSize = 0;
      }
      DwUsb3EndPointXStartTransfer (pcd, ep);
    }
  }
}

STATIC
VOID
DwUsb3HandleEndPointInterrupt (
  IN usb3_pcd_t       *pcd,
  IN UINTN            PhySep,
  IN UINT32           event
  )
{
  usb3_pcd_ep_t       *ep;
  UINT32              epnum, is_in;

  // Physical Out EPs are even, physical In EPs are odd
  is_in = (UINT32)PhySep & 1;
  epnum = ((UINT32)PhySep >> 1) & 0xF;

  // Get the EP pointer
  if (is_in) {
    ep = DwUsb3GetInEndPoint (pcd, epnum);
  } else {
    ep = DwUsb3GetOutEndPoint (pcd, epnum);
  }

  switch (event & GEVNT_DEPEVT_INTTYPE_MASK) {
  case GEVNT_DEPEVT_INTTYPE_XFER_CMPL:
    ep->xfer_started = 0;
    // complete the transfer
    if (epnum == 0) {
      DwUsb3OsHandleEndPoint0 (pcd, event);
    } else {
      DwUsb3EndPointcompleteRequest (pcd, ep, event);
    }
    break;
  case GEVNT_DEPEVT_INTTYPE_XFER_IN_PROG:
    break;
  case GEVNT_DEPEVT_INTTYPE_XFER_NRDY:
    if (epnum == 0) {
      switch (pcd->ep0state) {
#if 1
      case EP0_IN_WAIT_NRDY:
        if (is_in) {
          DwUsb3OsHandleEndPoint0 (pcd, event);
        } else {
        }
        break;
      case EP0_OUT_WAIT_NRDY:
        if (!is_in) {
          DwUsb3OsHandleEndPoint0 (pcd, event);
        } else {
        }
        break;
#else
      case EP0_IN_WAIT_NRDY:
      case EP0_OUT_WAIT_NRDY:
        DwUsb3OsHandleEndPoint0 (pcd, event);
        break;
#endif
      default:
        break;
      }
    } else {
    }
    break;
  default:
    DEBUG ((DEBUG_ERROR, "invalid event %d\n", event & GEVNT_DEPEVT_INTTYPE_MASK));
    break;
  }
}

STATIC
UINTN
DwUsb3HandleEvent (
  VOID
  )
{
  usb3_pcd_t          *pcd = &gPcd;
  UINT32              Count, Index, Event, Intr;
  UINT32              PhySep;

  Count = GET_EVENTBUF_COUNT ();
  // reset event buffer when it's full
  if ((GEVNTCOUNT_EVNTCOUNT (Count) == GEVNTCOUNT_EVNTCOUNT_MASK) ||
      (Count >= DWUSB3_EVENT_BUF_SIZE * sizeof (UINT32))) {
    UPDATE_EVENTBUF_COUNT (Count);
    Count = 0;
  }

  for (Index = 0; Index < Count; Index += sizeof (UINT32)) {
    Event = DwUsb3GetEventBufEvent (DWUSB3_EVENT_BUF_SIZE << 2);
    UPDATE_EVENTBUF_COUNT (sizeof (UINT32));
    if (Event == 0) {
      // ignore null events
      continue;
    }
    if (Event & GEVNT_NON_EP) {
      Intr = Event & GEVNT_INTTYPE_MASK;
      if (Intr == GEVNT_INTTYPE (EVENT_DEV_INT)) {
        DwUsb3HandleDeviceInterrupt (pcd, Event);
      }
    } else {
      PhySep = (Event & GEVNT_DEPEVT_EPNUM_MASK) >> GEVNT_DEPEVT_EPNUM_SHIFT;
      DwUsb3HandleEndPointInterrupt (pcd, PhySep, Event);
    }
  }
  return 0;
}

STATIC
VOID
DwUsb3Poll (
  IN EFI_EVENT        Event,
  IN VOID            *Context
  )
{
  if (DwUsb3HandleEvent ()) {
    DEBUG ((DEBUG_ERROR, "error: exit from usb_poll\n"));
    return;
  }
}

EFI_STATUS
EFIAPI
DwUsb3Start (
  IN USB_DEVICE_DESCRIPTOR   *DeviceDescriptor,
  IN VOID                   **Descriptors,
  IN USB_DEVICE_RX_CALLBACK   RxCallback,
  IN USB_DEVICE_TX_CALLBACK   TxCallback
  )
{
  EFI_STATUS             Status;
  EFI_EVENT              TimerEvent;

  //gEventBuf = UncachedAllocateAlignedZeroPool (DWUSB3_EVENT_BUF_SIZE << 2, 256);
  gEventBuf = UncachedAllocatePages (EFI_SIZE_TO_PAGES (DWUSB3_EVENT_BUF_SIZE << 2));
  if (gEventBuf == NULL) {
    return EFI_OUT_OF_RESOURCES;
  }
  ZeroMem (gEventBuf, EFI_SIZE_TO_PAGES (DWUSB3_EVENT_BUF_SIZE << 2));
  gEventPtr = gEventBuf;
  DriverInit ();
  DwUsb3Init ();
  Status = gBS->CreateEvent (
                  EVT_TIMER | EVT_NOTIFY_SIGNAL,
                  TPL_CALLBACK,
                  DwUsb3Poll,
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
  mDataReceivedCallback = RxCallback;
  return Status;
}

EFI_STATUS
DwUsb3Send (
  IN        UINT8  EndpointIndex,
  IN        UINTN  Size,
  IN  CONST VOID  *Buffer
  )
{
  usb3_pcd_t                    *pcd = &gPcd;
  usb3_pcd_ep_t                 *ep = &pcd->in_ep;
  usb3_pcd_req_t                *req = &ep->req;

  WriteBackDataCacheRange ((VOID *)Buffer, Size);
  req->bufdma = (UINT64 *)Buffer;
  req->length = Size;
  DwUsb3EndPointXStartTransfer (pcd, ep);
  return EFI_SUCCESS;
}

EFI_STATUS
DwUsb3Request (
  IN UINTN   BufferSize
  )
{
  if (BufferSize) {
    mDataBufferSize = BufferSize;
  }
  return EFI_SUCCESS;
}

USB_DEVICE_PROTOCOL mUsbDevice = {
  DwUsb3Start,
  DwUsb3Send,
  DwUsb3Request
};

EFI_STATUS
EFIAPI
DwUsb3EntryPoint (
  IN EFI_HANDLE                            ImageHandle,
  IN EFI_SYSTEM_TABLE                      *SystemTable
  )
{
  EFI_STATUS      Status;

  gEndPoint0SetupPacket = UncachedAllocatePages (EFI_SIZE_TO_PAGES (sizeof (usb_setup_pkt_t) * 5));
  if (gEndPoint0SetupPacket == NULL) {
    return EFI_OUT_OF_RESOURCES;
  }
  gEndPoint0StatusBuf = UncachedAllocatePages (EFI_SIZE_TO_PAGES (USB3_STATUS_BUF_SIZE * sizeof (UINT8)));
  if (gEndPoint0StatusBuf == NULL) {
    return EFI_OUT_OF_RESOURCES;
  }
#if 0
  gRxBuf = UncachedAllocatePages (1);
  if (gRxBuf == NULL) {
    return EFI_OUT_OF_RESOURCES;
  }
#endif
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
