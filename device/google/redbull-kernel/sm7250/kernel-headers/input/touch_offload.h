/****************************************************************************
 ****************************************************************************
 ***
 ***   This header was automatically generated from a Linux kernel header
 ***   of the same name, to make information necessary for userspace to
 ***   call into the kernel available to libc.  It contains only constants,
 ***   structures, and macros generated from the original header, and thus,
 ***   contains no copyrightable information.
 ***
 ***   To edit the content of this header, modify the corresponding
 ***   source file (e.g. under external/kernel-headers/original/) then
 ***   run bionic/libc/kernel/tools/update_all.py
 ***
 ***   Any manual change here will be lost the next time this script will
 ***   be run. You've been warned!
 ***
 ****************************************************************************
 ****************************************************************************/
#ifndef _TOUCH_OFFLOAD_H
#define _TOUCH_OFFLOAD_H
#define TOUCH_OFFLOAD_INTERFACE_MAJOR_VERSION 2
#define TOUCH_OFFLOAD_INTERFACE_MINOR_VERSION 0
#define TOUCH_OFFLOAD_MAGIC '7'
#define BUS_TYPE_I2C 0
#define BUS_TYPE_SPI 1
#define BUS_TYPE_I3C 2
#define HEATMAP_SIZE_PARTIAL 0
#define HEATMAP_SIZE_FULL 1
#define TOUCH_DATA_TYPE_COORD 0x0001
#define TOUCH_DATA_TYPE_RAW 0x0002
#define TOUCH_DATA_TYPE_FILTERED 0x0004
#define TOUCH_DATA_TYPE_BASELINE 0x0008
#define TOUCH_DATA_TYPE_STRENGTH 0x0010
#define TOUCH_SCAN_TYPE_MUTUAL 0x0040
#define TOUCH_SCAN_TYPE_SELF 0x0080
#define CONTEXT_CHANNEL_TYPE_DRIVER_STATUS 0x0100
#define CONTEXT_CHANNEL_TYPE_STYLUS_STATUS 0x0200
#define CONTEXT_CHANNEL_BIT_START 0x0100
#define CONTEXT_CHANNEL_BIT_END 0x0200
struct TouchOffloadCaps {
  __u32 touch_offload_major_version;
  __u32 touch_offload_minor_version;
  __u8 reserved1[8];
  __u32 device_id;
  __u16 display_width;
  __u16 display_height;
  __u16 tx_size;
  __u16 rx_size;
  __u8 bus_type;
  __u32 bus_speed_hz;
  __u8 reserved2[16];
  __u8 heatmap_size;
  __u16 touch_data_types;
  __u16 touch_scan_types;
  __u16 context_channel_types;
  __u8 reserved3[16];
  __u8 continuous_reporting;
  __u8 noise_reporting;
  __u8 cancel_reporting;
  __u8 size_reporting;
  __u8 rotation_reporting;
  __u8 filter_grip;
  __u8 filter_palm;
  __u8 num_sensitivity_settings;
  __u8 auto_reporting;
  __u8 reserved4[32];
} __attribute__((packed));
struct TouchOffloadConfig {
  __u8 continuous_reporting;
  __u8 noise_reporting;
  __u8 cancel_reporting;
  __u8 filter_grip;
  __u8 filter_palm;
  __u8 sensitivity_setting;
  __u8 auto_reporting;
  __u8 reserved1[16];
  __u8 read_coords;
  __u16 mutual_data_types;
  __u16 self_data_types;
  __u16 context_channel_types;
  __u8 reserved2[16];
} __attribute__((packed));
struct TouchOffloadFrameHeader {
  __u32 frame_size;
  __u64 index;
  __u64 timestamp;
  __u8 num_channels;
} __attribute__((packed));
struct TouchOffloadChannelHeader {
  __u32 channel_type;
  __u32 channel_size;
} __attribute__((packed));
enum CoordStatus {
  COORD_STATUS_INACTIVE = 0x00,
  COORD_STATUS_FINGER = 0x01,
  COORD_STATUS_EDGE = 0x02,
  COORD_STATUS_PALM = 0x03,
  COORD_STATUS_CANCEL = 0x04,
  COORD_STATUS_PEN = 0x05
};
#define MAX_COORDS 10
struct TouchOffloadCoord {
  __u16 x;
  __u16 y;
  enum CoordStatus status;
  __u32 major;
  __u32 minor;
  __u32 pressure;
  __s16 rotation;
  __u8 reserved1[16];
} __attribute__((packed));
struct TouchOffloadDataCoord {
  struct TouchOffloadChannelHeader header;
  struct TouchOffloadCoord coords[MAX_COORDS];
  __u8 reserved1[16];
} __attribute__((packed));
#define TOUCH_OFFLOAD_FRAME_SIZE_COORD (sizeof(struct TouchOffloadDataCoord))
struct TouchOffloadData2d {
  struct TouchOffloadChannelHeader header;
  __u16 tx_size;
  __u16 rx_size;
  __u8 reserved1[16];
  __u8 data[1];
} __attribute__((packed));
#define TOUCH_OFFLOAD_DATA_SIZE_2D(rx,tx) (sizeof(__u16) * (rx) * (tx))
#define TOUCH_OFFLOAD_FRAME_SIZE_2D(rx,tx) (sizeof(struct TouchOffloadData2d) - 1 + TOUCH_OFFLOAD_DATA_SIZE_2D((rx), (tx)))
struct TouchOffloadData1d {
  struct TouchOffloadChannelHeader header;
  __u16 tx_size;
  __u16 rx_size;
  __u8 reserved1[16];
  __u8 data[1];
} __attribute__((packed));
#define TOUCH_OFFLOAD_DATA_SIZE_1D(rx,tx) (sizeof(__u16) * ((rx) + (tx)))
#define TOUCH_OFFLOAD_FRAME_SIZE_1D(rx,tx) (sizeof(struct TouchOffloadData1d) - 1 + TOUCH_OFFLOAD_DATA_SIZE_1D((rx), (tx)))
struct TouchOffloadDriverStatus {
  struct TouchOffloadChannelHeader header;
  struct {
    __u32 screen_state : 1;
    __u32 display_refresh_rate : 1;
    __u32 touch_report_rate : 1;
    __u32 noise_state : 1;
    __u32 water_mode : 1;
    __u32 charger_state : 1;
    __u32 hinge_angle : 1;
    __u32 offload_timestamp : 1;
  } contents;
  __u8 reserved1[8];
  __u8 screen_state;
  __u8 display_refresh_rate;
  __u8 touch_report_rate;
  __u8 noise_state;
  __u8 water_mode;
  __u8 charger_state;
  __s16 hinge_angle;
  __u64 offload_timestamp;
  __u8 reserved2[32];
} __attribute__((packed));
#define TOUCH_OFFLOAD_FRAME_SIZE_DRIVER_STATUS (sizeof(struct TouchOffloadDriverStatus))
struct TouchOffloadStylusStatus {
  struct TouchOffloadChannelHeader header;
  struct {
    __u32 coords : 1;
    __u32 coords_timestamp : 1;
    __u32 pen_paired : 1;
    __u32 pen_active : 1;
  } contents;
  __u8 reserved1[8];
  struct TouchOffloadCoord coords[MAX_COORDS];
  __u64 coords_timestamp;
  __u8 reserved2[16];
  __u8 pen_paired;
  __u8 pen_active;
  __u8 reserved3[16];
} __attribute__((packed));
#define TOUCH_OFFLOAD_FRAME_SIZE_STYLUS_STATUS (sizeof(struct TouchOffloadStylusStatus))
struct TouchOffloadIocGetCaps {
  struct TouchOffloadCaps caps;
  __u8 reserved1[16];
} __attribute__((packed));
struct TouchOffloadIocConfigure {
  struct TouchOffloadConfig config;
  __u8 reserved1[16];
} __attribute__((packed));
struct TouchOffloadIocReport {
  __u64 index;
  __u64 timestamp;
  __u8 num_coords;
  __u8 reserved1[16];
  struct TouchOffloadCoord coords[MAX_COORDS];
} __attribute__((packed));
#define TOUCH_OFFLOAD_IOC_RD_GETCAPS _IOR(TOUCH_OFFLOAD_MAGIC, 0, struct TouchOffloadIocGetCaps)
#define TOUCH_OFFLOAD_IOC_WR_CONFIGURE _IOW(TOUCH_OFFLOAD_MAGIC, 1, struct TouchOffloadIocConfigure)
#define TOUCH_OFFLOAD_IOC_START _IOC(TOUCH_OFFLOAD_MAGIC, 2)
#define TOUCH_OFFLOAD_IOC_WR_REPORT _IOW(TOUCH_OFFLOAD_MAGIC, 3, struct TouchOffloadIocReport)
#define TOUCH_OFFLOAD_IOC_STOP _IOC(TOUCH_OFFLOAD_MAGIC, 4)
#endif
