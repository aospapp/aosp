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
#ifndef _BIGO_H_
#define _BIGO_H_
#include <linux/ioctl.h>
#include <linux/types.h>
struct bigo_ioc_regs {
  __u64 regs;
  __u32 regs_size;
};
struct bigo_ioc_mapping {
  int fd;
  __u32 iova;
  __u32 offset;
  __u32 size;
};
struct bigo_ioc_frmsize {
  __u32 height;
  __u32 width;
};
struct bigo_cache_info {
  __u32 size;
  __u32 pid;
};
#define BIGO_IOC_MAGIC 'B'
#define _BIGO_IO(nr) _IO(BIGO_IOC_MAGIC, nr)
#define _BIGO_IOR(nr,size) _IOR(BIGO_IOC_MAGIC, nr, size)
#define _BIGO_IOW(nr,size) _IOW(BIGO_IOC_MAGIC, nr, size)
#define _BIGO_IOWR(nr,size) _IOWR(BIGO_IOC_MAGIC, nr, size)
enum bigo_cmd_id {
  BIGO_CMD_PROCESS,
  BIGO_CMD_ABORT,
  BIGO_CMD_MAP,
  BIGO_CMD_UNMAP,
  BIGO_CMD_CONFIG_FRMRATE,
  BIGO_CMD_CONFIG_FRMSIZE,
  BIGO_CMD_GET_CACHE_INFO,
  BIGO_CMD_CONFIG_SECURE,
  BIGO_CMD_CONFIG_PRIORITY,
  BIGO_CMD_MAXNR,
};
#define BIGO_IOCX_PROCESS _BIGO_IOWR(BIGO_CMD_PROCESS, struct bigo_ioc_regs)
#define BIGO_IOCX_MAP _BIGO_IOWR(BIGO_CMD_MAP, struct bigo_ioc_mapping)
#define BIGO_IOCX_UNMAP _BIGO_IOW(BIGO_CMD_UNMAP, struct bigo_ioc_mapping)
#define BIGO_IOCX_CONFIG_FRMRATE _BIGO_IOW(BIGO_CMD_CONFIG_FRMRATE, __u32)
#define BIGO_IOCX_CONFIG_FRMSIZE _BIGO_IOW(BIGO_CMD_CONFIG_FRMSIZE, struct bigo_ioc_frmsize)
#define BIGO_IOCX_GET_CACHE_INFO _BIGO_IOR(BIGO_CMD_GET_CACHE_INFO, struct bigo_cache_info)
#define BIGO_IOCX_ABORT _BIGO_IO(BIGO_CMD_ABORT)
#define BIGO_IOCX_CONFIG_SECURE _BIGO_IOW(BIGO_CMD_CONFIG_SECURE, __u32)
#define BIGO_IOCX_CONFIG_PRIORITY _BIGO_IOW(BIGO_CMD_CONFIG_PRIORITY, __s32)
#endif
