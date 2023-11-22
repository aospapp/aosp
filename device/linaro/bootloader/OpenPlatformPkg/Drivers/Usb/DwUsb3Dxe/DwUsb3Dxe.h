/** @file

  Copyright (c) 2017, Linaro Limited. All rights reserved.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#ifndef __DW_USB3_DXE_H__
#define __DW_USB3_DXE_H__

#define DW_USB3_BASE                     FixedPcdGet32 (PcdDwUsb3DxeBaseAddress)

#define GSBUCFG0                         (DW_USB3_BASE + 0xC100)
#define GCTL                             (DW_USB3_BASE + 0xC110)

#define GCTL_PWRDNSCALE_MASK             (0x1FFF << 19)
#define GCTL_PWRDNSCALE(x)               (((x) & 0x1FFF) << 19)
#define GCTL_U2RSTECN                    BIT16
#define GCTL_PRTCAPDIR_MASK              (BIT13 | BIT12)
#define GCTL_PRTCAPDIR_HOST              BIT12
#define GCTL_PRTCAPDIR_DEVICE            BIT13
#define GCTL_PRTCAPDIR_OTG               (BIT13 | BIT12)
#define GCTL_U2EXIT_LFPS                 BIT2

#define GUSB2PHYCFG(x)                   (DW_USB3_BASE + 0xC200 + (((x) & 0xF) << 2))

#define GUSB2PHYCFG_USBTRDTIM_MASK       (0xF << 10)
#define GUSB2PHYCFG_USBTRDTIM(x)         (((x) & 0xF) << 10)
#define GUSB2PHYCFG_SUSPHY               BIT6

#define GUSB3PIPECTL(x)                  (DW_USB3_BASE + 0xC2C0 + (((x) & 0x3) << 2))

#define PIPECTL_DELAYP1TRANS             BIT18
#define PIPECTL_SUSPEND_EN               BIT17
#define PIPECTL_LFPS_FILTER              BIT9
#define PIPECTL_TX_DEMPH_MASK            (0x3 << 1)
#define PIPECTL_TX_DEMPH(x)              (((x) & 0x3) << 1)

#define GTXFIFOSIZ(x)                    (DW_USB3_BASE + 0xC300 + (((x) & 0x1F) << 2))
#define GRXFIFOSIZ(x)                    (DW_USB3_BASE + 0xC380 + (((x) & 0x1F) << 2))

#define FIFOSIZ_ADDR(x)                  (((x) & 0xFFFF) << 16)
#define FIFOSIZ_DEP(x)                   ((x) & 0xFFFF)

#define GEVNTADRL(x)                     (DW_USB3_BASE + 0xC400 + (((x) & 0x1F) << 2))
#define GEVNTADRH(x)                     (DW_USB3_BASE + 0xC404 + (((x) & 0x1F) << 2))
#define GEVNTSIZ(x)                      (DW_USB3_BASE + 0xC408 + (((x) & 0x1F) << 2))

#define GEVNTSIZ_EVNTINTMASK             BIT31
#define GEVNTSIZ_EVNTSIZ_MASK            (0xFFFF)
#define GEVNTSIZ_EVNTSIZ(x)              ((x) & 0xFFFF)

#define GEVNTCOUNT(x)                    (DW_USB3_BASE + 0xC40C + (((x) & 0x1F) << 2))
#define GEVNTCOUNT_EVNTCOUNT_MASK        (0xFFFF)
#define GEVNTCOUNT_EVNTCOUNT(x)          ((x) & 0xFFFF)

// Non-Endpoint specific event flag
#define GEVNT_INTTYPE_MASK               (0x7F << 1)
#define GEVNT_INTTYPE(x)                 (((x) & 0x7F) << 1)
#define EVENT_I2C_INT                    4
#define EVENT_CARKIT_INT                 3
#define EVENT_OTG_INT                    1
#define EVENT_DEV_INT                    0

#define GEVNT_NON_EP                     BIT0
// Endpoint specific event flag
#define GEVNT_DEPEVT_INTTYPE_MASK        (0xF << 6)
#define GEVNT_DEPEVT_INTTYPE(x)          (((x) & 0xF) << 6)
#define GEVNT_DEPEVT_INTTYPE_EPCMD_CMPL          (7 << 6)
#define GEVNT_DEPEVT_INTTYPE_STRM_EVT            (6 << 6)
#define GEVNT_DEPEVT_INTTYPE_FIFOXRUN            (4 << 6)
#define GEVNT_DEPEVT_INTTYPE_XFER_NRDY           (3 << 6)
#define GEVNT_DEPEVT_INTTYPE_XFER_IN_PROG        (2 << 6)
#define GEVNT_DEPEVT_INTTYPE_XFER_CMPL           (1 << 6)
#define GEVNT_DEPEVT_EPNUM_MASK          (0x1F << 1)
#define GEVNT_DEPEVT_EPNUM_SHIFT         1
#define GEVNT_DEPEVT_EPNUM(x)            (((x) & 0x1F) << 1)
// Devices specific event flag
#define GEVNT_DEVT_MASK                  (0xF << 8)
#define GEVNT_DEVT_SHIFT                 8
#define GEVNT_DEVT(x)                    (((x) & 0xF) << 8)
#define GEVNT_DEVT_INACT_TIMEOUT_RCVD    (0x15 << 8)
#define GEVNT_DEVT_VNDR_DEV_TST_RCVD     (0x14 << 8)
#define GEVNT_DEVT_OVERFLOW              (0x13 << 8)
#define GEVNT_DEVT_CMD_CMPL              (0x12 << 8)
#define GEVNT_DEVT_ERRATICERR            (0x11 << 8)
#define GEVNT_DEVT_SOF                   (0x7 << 8)
#define GEVNT_DEVT_EOPF                  (0x6 << 8)
#define GEVNT_DEVT_HIBER_REQ             (0x5 << 8)
#define GEVNT_DEVT_WKUP                  (0x4 << 8)
#define GEVNT_DEVT_ULST_CHNG             (0x3 << 8)
#define GEVNT_DEVT_CONNDONE              (0x2 << 8)
#define GEVNT_DEVT_USBRESET              (0x1 << 8)
#define GEVNT_DEVT_DISCONN               (0x0 << 8)

#define DCFG                             (DW_USB3_BASE + 0xC700)

#define DCFG_NUMP_MASK                   (0x1F << 17)
#define DCFG_NUMP(x)                     (((x) & 0x1F) << 17)
#define DCFG_DEVADDR_MASK                (0x7F << 3)
#define DCFG_DEVADDR(x)                  (((x) & 0x7F) << 3)
#define DCFG_DEVSPD_MASK                 (0x7)
#define DCFG_DEVSPD(x)                   ((x) & 0x7)
#define DEVSPD_HS_PHY_30MHZ_OR_60MHZ     0
#define DEVSPD_FS_PHY_30MHZ_OR_60MHZ     1
#define DEVSPD_LS_PHY_6MHZ               2
#define DEVSPD_FS_PHY_48MHZ              3
#define DEVSPD_SS_PHY_125MHZ_OR_250MHZ   4

#define DCTL                             (DW_USB3_BASE + 0xC704)

#define DCTL_RUN_STOP                    BIT31
#define DCTL_CSFTRST                     BIT30
#define DCTL_INIT_U2_EN                  BIT12
#define DCTL_ACCEPT_U2_EN                BIT11
#define DCTL_INIT_U1_EN                  BIT10
#define DCTL_ACCEPT_U1_EN                BIT9

#define DEVTEN                           (DW_USB3_BASE + 0xC708)
#define DEVTEN_CONNECTDONEEN             BIT2
#define DEVTEN_USBRSTEN                  BIT1
#define DEVTEN_DISCONNEN                 BIT0

#define DSTS                             (DW_USB3_BASE + 0xC70C)
#define DSTS_GET_DEVSPD(x)               ((x) & 0x7)

#define DALEPENA                         (DW_USB3_BASE + 0xC720)

#define DEPCMDPAR2(x)                    (DW_USB3_BASE + 0xC800 + ((x) & 0x1F) * 0x10)
#define DEPCMDPAR1(x)                    (DW_USB3_BASE + 0xC804 + ((x) & 0x1F) * 0x10)
#define DEPCMDPAR0(x)                    (DW_USB3_BASE + 0xC808 + ((x) & 0x1F) * 0x10)
#define DEPCMD(x)                        (DW_USB3_BASE + 0xc80C + ((x) & 0x1F) * 0x10)

#define DEPCMD_COMMANDPARAM_MASK         (0xFFFF << 16)
#define DEPCMD_COMMANDPARAM(x)           (((x) & 0xFFFF) << 16)
/* Stream Number or uFrame (input) */
#define DEPCMD_STR_NUM_OR_UF_MASK        (0xFFFF << 16)
#define DEPCMD_STR_NUM_OR_UF(x)          (((x) & 0xFFFF) << 16)
/* Transfer Resource Index (output) */
#define DEPCMD_XFER_RSRC_IDX_SHIFT       16
#define DEPCMD_XFER_RSRC_IDX_MASK        (0x7F << 16)
#define DEPCMD_XFER_RSRC_IDX(x)          (((x) & 0x7F) << 16)
#define GET_DEPCMD_XFER_RSRC_IDX(x)      (((x) >> 16) & 0x7F)
#define DEPCMD_CMDACT                    BIT10
#define DEPCMD_CMDTYPE_MASK              0xFF
#define DEPCMD_CMDTYPE(x)                ((x) & 0xFF)

/* EP registers range as: OUT0, IN0, OUT1, IN1, ... */
#define EP_OUT_IDX(x)                    ((x) * 2)
#define EP_IN_IDX(x)                     (((x) * 2) + 1)

#define CMDTYPE_SET_EP_CFG               1
#define CMDTYPE_SET_XFER_CFG             2
#define CMDTYPE_GET_EP_STATE             3
#define CMDTYPE_SET_STALL                4
#define CMDTYPE_CLR_STALL                5
#define CMDTYPE_START_XFER               6
#define CMDTYPE_UPDATE_XFER              7
#define CMDTYPE_END_XFER                 8
#define CMDTYPE_START_NEW_CFG            9

#define EPTYPE_CONTROL                   0
#define EPTYPE_ISOC                      1
#define EPTYPE_BULK                      2
#define EPTYPE_INTR                      3

#define CFG_ACTION_INIT                  0
#define CFG_ACTION_RESTORE               1
#define CFG_ACTION_MODIFY                2

#define EPCFG0_CFG_ACTION_MASK           (0x3 << 30)
#define EPCFG0_CFG_ACTION(x)             (((x) & 0x3) << 30)
#define EPCFG0_BRSTSIZ_MASK              (0xF << 22)
#define EPCFG0_BRSTSIZ(x)                (((x) & 0xF) << 22)
#define EPCFG0_TXFNUM_MASK               (0x1F << 17)
#define EPCFG0_TXFNUM(x)                 (((x) & 0x1F) << 17)
#define EPCFG0_MPS_MASK                  (0x7FF << 3)
#define EPCFG0_MPS(x)                    (((x) & 0x7FF) << 3)
#define EPCFG0_EPTYPE_MASK               (0x3 << 1)
#define EPCFG0_EPTYPE_SHIFT              1
#define EPCFG0_EPTYPE(x)                 (((x) & 0x3) << 1)

/* Endpoint Number */
#define EPCFG1_EP_NUM_MASK               (0xF << 26)
#define EPCFG1_EP_NUM(x)                 (((x) & 0xF) << 26)
/* Endpoint Direction */
#define EPCFG1_EP_DIR_IN                 BIT25
/* Stream Not Ready */
#define EPCFG1_XFER_NRDY                 BIT10
/* XferInProgress Enable */
#define EPCFG1_XFER_IN_PROG              BIT9
/* Stream Completed */
#define EPCFG1_XFER_CMPL                 BIT8

#define USB_SPEED_UNKNOWN                0
#define USB_SPEED_LOW                    1
#define USB_SPEED_FULL                   2
#define USB_SPEED_HIGH                   3
#define USB_SPEED_VARIABLE               4
#define USB_SPEED_SUPER                  5

// DMA registers
#define DSCSTS_TRBRSP_MASK               (0xF << 28)
#define DSCSTS_TRBRSP(x)                 (((x) & 0xF) << 28)
#define GET_DSCSTS_TRBRSP(x)             (((x) >> 28) & 0xF)
#define TRBRSP_MISSED_ISOC_IN            1
#define TRBRSP_SETUP_PEND                2
#define TRBRSP_XFER_IN_PROG              4
#define DSCSTS_PCM1_MASK                 (0x3 << 24)
#define DSCSTS_PCM1(x)                   (((x) & 0x3) << 24)
#define DSCSTS_XFERCNT_MASK              0xFFFFFF
#define DSCSTS_XFERCNT(x)                ((x) & 0xFFFFFF)
#define GET_DSCSTS_XFERCNT(x)            ((x) & 0xFFFFFF)

#define DSCCTL_STRMID_SOFN(x)            (((x) & 0xFFFF) << 14)
#define DSCCTL_IOC                       BIT11
#define DSCCTL_ISP                       BIT10
#define DSCCTL_TRBCTL_MASK               (0x3F << 4)
#define DSCCTL_TRBCTL(x)                 (((x) & 0x3F) << 4)
#define DSCCTL_LST                       BIT1
#define DSCCTL_HWO                       BIT0
#define TRBCTL_NORMAL                    1
#define TRBCTL_SETUP                     2
#define TRBCTL_STATUS_2                  3
#define TRBCTL_STATUS_3                  4
#define TRBCTL_CTLDATA_1ST               5
#define TRBCTL_ISOC_1ST                  6
#define TRBCTL_ISOC                      7
#define TRBCTL_LINK                      8
#define TRBCTL_NORMAL_ZLP                9


#define UE_DIR_IN                        0x80
#define UE_DIR_OUT                       0
#define UE_SET_DIR(a, d)                 ((a) | (((d) & 1) << 7))
#define UE_GET_DIR(a)                    ((a) & 0x80)
#define UE_ADDR                          0x0F
#define UE_GET_ADDR(a)                   ((a) & UE_ADDR)

#define UT_GET_DIR(a) ((a) & 0x80)
#define UT_WRITE		0x00
#define UT_READ			0x80

#define UT_GET_TYPE(a)                   ((a) & 0x60)
#define UT_STANDARD                      0x00
#define UT_CLASS                         0x20
#define UT_VENDOR                        0x40

#define UT_GET_RECIPIENT(a) ((a) & 0x1f)
#define UT_DEVICE		0x00
#define UT_INTERFACE    0x01
#define UT_ENDPOINT		0x02
#define UT_OTHER		0x03

#define UR_GET_STATUS                    0x00
#define UR_CLEAR_FEATURE                 0x01
#define UR_SET_FEATURE                   0x03
#define UR_SET_ADDRESS                   0x05
#define UR_GET_DESCRIPTOR                0x06
#define UR_SET_DESCRIPTOR                0x07
#define UR_GET_CONFIG                    0x08
#define UR_SET_CONFIG                    0x09
#define UR_GET_INTERFACE                 0x0A
#define UR_SET_INTERFACE                 0x0B
#define UR_SYNCH_FRAME                   0x0C
#define UR_SET_SEL                       0x30
#define UR_SET_ISOC_DELAY                0x31

/* Feature numbers */
#define UF_ENDPOINT_HALT	        0
#define UF_DEVICE_REMOTE_WAKEUP	    1
#define UF_TEST_MODE		        2
#define UF_DEVICE_B_HNP_ENABLE	    3
#define UF_DEVICE_A_HNP_SUPPORT	    4
#define UF_DEVICE_A_ALT_HNP_SUPPORT 5
#define UF_FUNCTION_SUSPEND	0
#define UF_U1_ENABLE		48
#define UF_U2_ENABLE		49
#define UF_LTM_ENABLE		50

#define  UDESC_DEVICE		    0x01
#define  UDESC_CONFIG		    0x02
#define  UDESC_STRING		    0x03
#define  UDESC_INTERFACE	    0x04
#define  UDESC_ENDPOINT		    0x05
#define  UDESC_SS_USB_COMPANION	0x30
#define  UDESC_DEVICE_QUALIFIER	0x06
#define  UDESC_BOS		        0x0f
#define  UDESC_DEVICE_CAPABILITY 0x10

#define STRING_LANGUAGE         0
#define STRING_MANUFACTURER     1
#define STRING_PRODUCT          2
#define STRING_SERIAL           3

#define CONFIG_VALUE    1

#define USB3_BULK_IN_EP                  1
#define USB3_BULK_OUT_EP                 1

#define USB_ENUM_ADB_PORT_VID             0x18D1
#define USB_ENUM_ADB_PORT_PID             0xD00D
#define USB_ENUM_INTERFACE_ADB_SUBCLASS   0x42
#define USB_ENUM_INTERFACE_ADB_PROTOCOL   0x03

struct usb3_pcd;

typedef enum pcd_state {
	USB3_STATE_UNCONNECTED,	/* no host */
	USB3_STATE_DEFAULT,
	USB3_STATE_ADDRESSED,
	USB3_STATE_CONFIGURED,
} pcdstate_e;

typedef enum ep0_state {
	EP0_IDLE,
	EP0_IN_DATA_PHASE,
	EP0_OUT_DATA_PHASE,
	EP0_IN_WAIT_NRDY,
	EP0_OUT_WAIT_NRDY,
	EP0_IN_STATUS_PHASE,
	EP0_OUT_STATUS_PHASE,
	EP0_STALL,
} ep0state_e;

typedef struct usb3_dma_desc {
	/** Buffer Pointer - Low address quadlet */
	UINT32	bptl;

	/** Buffer Pointer - High address quadlet */
	UINT32	bpth;

	/** Status quadlet. Fields defined in enum @ref desc_sts_data. */
	UINT32	status;

	/** Control quadlet. Fields defined in enum @ref desc_ctl_data. */
	UINT32	control;
} usb3_dma_desc_t;

typedef struct usb3_pcd_req {
	usb3_dma_desc_t *trb;
	UINT64 trbdma;

	UINT32 length;
	UINT32 actual;

	UINT64 *bufdma;
	int (*complete)(unsigned actual, int status);
} usb3_pcd_req_t;

typedef struct usb_device_request {
	UINT8 bmRequestType;
	UINT8 bRequest;
	UINT16 wValue;
	UINT16 wIndex;
	UINT16 wLength;
} usb_device_request_t;

#pragma pack(1)
/** USB_DT_DEVICE: Device descriptor */
typedef struct usb_device_descriptor {
	UINT8  bLength;
	UINT8  bDescriptorType;

	UINT16 bcdUSB;
#define USB_CLASS_COMM          0x02
#define USB_CLASS_VENDOR_SPEC   0xFF
#define USB_SC_VENDOR_SPEC      0xFF
#define USB_PR_VENDOR_SPEC      0xFF
	UINT8  bDeviceClass;
	UINT8  bDeviceSubClass;
	UINT8  bDeviceProtocol;
	UINT8  bMaxPacketSize0;
	UINT16 idVendor;
	UINT16 idProduct;
	UINT16 bcdDevice;
	UINT8  iManufacturer;
	UINT8  iProduct;
	UINT8  iSerialNumber;
	UINT8  bNumConfigurations;
} usb_device_descriptor_t;

/* USB_DT_CONFIG: Config descriptor */
typedef struct usb_config_descriptor {
	UINT8  bLength;
	UINT8  bDescriptorType;

	UINT16 wTotalLength;
	UINT8  bNumInterfaces;
#define CONFIG_VALUE    1
	UINT8  bConfigurationValue;
	UINT8  iConfiguration;
#define USB_CONFIG_ATT_ONE      (1 << 7)
	UINT8  bmAttributes;
#define USB_CONFIG_VBUS_DRAW    (0xFA)
	UINT8  bMaxPower;
} usb_config_descriptor_t;

/* USB_DT_DEVICE_QUALIFIER: Device Qualifier descriptor */
typedef struct usb_qualifier_descriptor {
	UINT8  bLength;
	UINT8  bDescriptorType;

	UINT16 bcdUSB;
	UINT8  bDeviceClass;
	UINT8  bDeviceSubClass;
	UINT8  bDeviceProtocol;
	UINT8  bMaxPacketSize0;
	UINT8  bNumConfigurations;
	UINT8  bRESERVED;
} usb_qualifier_descriptor_t;

/* USB_DT_INTERFACE: Interface descriptor */
typedef struct usb_interface_descriptor {
	UINT8  bLength;
	UINT8  bDescriptorType;

	UINT8  bInterfaceNumber;
	UINT8  bAlternateSetting;
	UINT8  bNumEndpoints;
	UINT8  bInterfaceClass;
	UINT8  bInterfaceSubClass;
	UINT8  bInterfaceProtocol;
	UINT8  iInterface;
} usb_interface_descriptor_t;

/* USB_DT_ENDPOINT: Endpoint descriptor */
typedef struct usb_endpoint_descriptor {
	UINT8  bLength;
	UINT8  bDescriptorType;

	UINT8  bEndpointAddress;
	UINT8  bmAttributes;
#define USB_ENDPOINT_XFER_CONTROL	0x00
#define USB_ENDPOINT_XFER_ISOC		0x01
#define USB_ENDPOINT_XFER_BULK		0x02
#define USB_ENDPOINT_XFER_INT		0x03
	UINT16 wMaxPacketSize;
	UINT8  bInterval;
} usb_endpoint_descriptor_t;

/* USB_DT_SS_ENDPOINT_COMP: SuperSpeed Endpoint Companion descriptor */
typedef struct usb_ss_ep_comp_descriptor {
	UINT8  bLength;
	UINT8  bDescriptorType;

	UINT8  bMaxBurst;
	UINT8  bmAttributes;
	UINT16 wBytesPerInterval;
} usb_ss_ep_comp_descriptor_t;

/* WUSB BOS Descriptor (Binary device Object Store) */
typedef struct wusb_bos_desc {
	UINT8 bLength;
	UINT8 bDescriptorType;
	UINT16 wTotalLength;
	UINT8 bNumDeviceCaps;
} wusb_bos_desc_t;

#define USB_DEVICE_CAPABILITY_20_EXTENSION	0x02
typedef struct usb_dev_cap_20_ext_desc {
	UINT8 bLength;
	UINT8 bDescriptorType;
	UINT8 bDevCapabilityType;
#define USB_20_EXT_LPM				0x02
	UINT32 bmAttributes;
} usb_dev_cap_20_ext_desc_t;

#define USB_DEVICE_CAPABILITY_SS_USB		0x03
typedef struct usb_dev_cap_ss_usb {
	UINT8 bLength;
	UINT8 bDescriptorType;
	UINT8 bDevCapabilityType;
#define USB_DC_SS_USB_LTM_CAPABLE		0x02
	UINT8 bmAttributes;
#define USB_DC_SS_USB_SPEED_SUPPORT_LOW		0x01
#define USB_DC_SS_USB_SPEED_SUPPORT_FULL	0x02
#define USB_DC_SS_USB_SPEED_SUPPORT_HIGH	0x04
#define USB_DC_SS_USB_SPEED_SUPPORT_SS		0x08
	UINT32 wSpeedsSupported;
	UINT8 bFunctionalitySupport;
	UINT8 bU1DevExitLat;
	UINT32 wU2DevExitLat;
} usb_dev_cap_ss_usb_t;

#define USB_DEVICE_CAPABILITY_CONTAINER_ID	0x04
typedef struct usb_dev_cap_container_id {
	UINT8 bLength;
	UINT8 bDescriptorType;
	UINT8 bDevCapabilityType;
	UINT8 bReserved;
	UINT8 containerID[16];
} usb_dev_cap_container_id_t;
#pragma pack()

typedef union usb_setup_pkt {
	usb_device_request_t req;
	UINT32 d32[2];
	UINT8 d8[8];
} usb_setup_pkt_t;

typedef struct usb3_pcd_ep {
	struct usb3_pcd *pcd;

  UINT8          EpInIdx;
  UINT8          EpOutIdx;
  UINT8          phys;

	//UINT8 phys;
	UINT8 num;
	UINT8 type;
	UINT8 maxburst;
	UINT16 maxpacket;
	/* Tx FIFO # for IN EPs */
	UINT8 tx_fifo_num;

	/* The Transfer Resource Index from the Start Transfer command */
	UINT8 tri_out;
	UINT8 tri_in;

	UINT8 stopped;
	/* Send ZLP */
	UINT8 send_zlp;
	/* True if 3-stage control transfer */
	UINT8 three_stage;
	/* True if transfer has been started on EP */
	UINT8 xfer_started;
	/* EP direction 0 = OUT */
	UINT8 is_in;
	/* True if endpoint is active */
	UINT8 active;
	/* Initial data pid of bulk endpoint */
	UINT8 data_pid_start;

	/* ep_desc (excluding ep0) */
	usb3_dma_desc_t *ep_desc;

#if 0
	/* TRB descriptor must be aligned to 16 bytes */
	UINT8 epx_desc[32];
#endif

	/* request (excluding ep0) */
	usb3_pcd_req_t req;
} usb3_pcd_ep_t;

typedef struct usb3_pcd {
	//struct usb3_device *usb3_dev;

	INT32 link_state;
	pcdstate_e state;
	UINT8 new_config;
	ep0state_e ep0state;

	UINT32 eps_enabled;
	UINT32 ltm_enable;

	usb3_pcd_ep_t ep0;
	usb3_pcd_ep_t out_ep;
	usb3_pcd_ep_t in_ep;

	/*
	usb3_dev_global_regs_t *dev_global_regs;
	usb3_dev_ep_regs_t *out_ep_regs;
	usb3_dev_ep_regs_t *in_ep_regs;
	*/

	usb3_pcd_req_t ep0_req;

	UINT8 speed;

	usb3_dma_desc_t *ep0_setup_desc;
	usb3_dma_desc_t *ep0_in_desc;
	usb3_dma_desc_t *ep0_out_desc;

	/* TRB descriptor must be aligned to 16 bytes */
#if 0
	UINT8 ep0_setup[32];
	UINT8 ep0_in[32];
	UINT8 ep0_out[32];

	usb_setup_pkt_t ep0_setup_pkt[5];

#define USB3_STATUS_BUF_SIZE    512
	UINT8 ep0_status_buf[USB3_STATUS_BUF_SIZE];

#define USB3_BULK_BUF_SIZE      2048
	UINT8 ss_bulk_buf[USB3_BULK_BUF_SIZE];
#endif

	UINT32 file_type;
	UINT32 file_address;
	UINT32 file_capacity;
	UINT32 file_total_frame;
	UINT32 file_curr_frame;
	UINT32 file_next_frame;
	UINT32 file_received;
	UINT32 file_complete;

	UINT16 test_mode_nr;
	UINT16 test_mode;
} usb3_pcd_t;

struct usb_enum_port_param {
  UINT16     idVendor;
  UINT16     idProduct;
  UINT8      bInterfaceSubClass;
  UINT8      bInterfaceProtocol;
};

#if 0
typedef struct usb3_pcd_req {
	usb3_dma_desc_t *trb;
	UINT64 trbdma;

	UINT32 length;
	UINT32 actual;

	UINT64 *bufdma;
	int (*complete)(unsigned actual, int status);
} usb3_pcd_req_t;

#endif

#endif /* __DW_USB3_DXE_H__ */
