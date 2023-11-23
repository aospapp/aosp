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
#ifndef __SAMSUNG_DRM_H__
#define __SAMSUNG_DRM_H__
#ifdef __linux__
#include <linux/types.h>
#endif
#include "drm.h"
#ifdef __cplusplus
extern "C" {
#endif
#define DRM_SAMSUNG_HDR_EOTF_LUT_LEN 129
struct hdr_eotf_lut {
  __u16 posx[DRM_SAMSUNG_HDR_EOTF_LUT_LEN];
  __u32 posy[DRM_SAMSUNG_HDR_EOTF_LUT_LEN];
};
#define DRM_SAMSUNG_HDR_OETF_LUT_LEN 33
struct hdr_oetf_lut {
  __u16 posx[DRM_SAMSUNG_HDR_OETF_LUT_LEN];
  __u16 posy[DRM_SAMSUNG_HDR_OETF_LUT_LEN];
};
#define DRM_SAMSUNG_HDR_GM_DIMENS 3
struct hdr_gm_data {
  __u32 coeffs[DRM_SAMSUNG_HDR_GM_DIMENS * DRM_SAMSUNG_HDR_GM_DIMENS];
  __u32 offsets[DRM_SAMSUNG_HDR_GM_DIMENS];
};
#define DRM_SAMSUNG_HDR_TM_LUT_LEN 33
struct hdr_tm_data {
  __u16 coeff_r;
  __u16 coeff_g;
  __u16 coeff_b;
  __u16 rng_x_min;
  __u16 rng_x_max;
  __u16 rng_y_min;
  __u16 rng_y_max;
  __u16 posx[DRM_SAMSUNG_HDR_TM_LUT_LEN];
  __u32 posy[DRM_SAMSUNG_HDR_TM_LUT_LEN];
};
#define DRM_SAMSUNG_CGC_LUT_REG_CNT 2457
struct cgc_lut {
  __u32 r_values[DRM_SAMSUNG_CGC_LUT_REG_CNT];
  __u32 g_values[DRM_SAMSUNG_CGC_LUT_REG_CNT];
  __u32 b_values[DRM_SAMSUNG_CGC_LUT_REG_CNT];
};
#define DRM_SAMSUNG_CGC_DMA_LUT_ENTRY_CNT 4913
struct cgc_dma_lut {
  __u16 r_value;
  __u16 g_value;
  __u16 b_value;
};
#define DRM_SAMSUNG_MATRIX_DIMENS 3
struct exynos_matrix {
  __u16 coeffs[DRM_SAMSUNG_MATRIX_DIMENS * DRM_SAMSUNG_MATRIX_DIMENS];
  __u16 offsets[DRM_SAMSUNG_MATRIX_DIMENS];
};
struct dpp_size_range {
  __u32 min;
  __u32 max;
  __u32 align;
};
struct dpp_restriction {
  struct dpp_size_range src_f_w;
  struct dpp_size_range src_f_h;
  struct dpp_size_range src_w;
  struct dpp_size_range src_h;
  __u32 src_x_align;
  __u32 src_y_align;
  struct dpp_size_range dst_f_w;
  struct dpp_size_range dst_f_h;
  struct dpp_size_range dst_w;
  struct dpp_size_range dst_h;
  __u32 dst_x_align;
  __u32 dst_y_align;
  struct dpp_size_range blk_w;
  struct dpp_size_range blk_h;
  __u32 blk_x_align;
  __u32 blk_y_align;
  __u32 src_h_rot_max;
  __u32 scale_down;
  __u32 scale_up;
};
struct dpp_ch_restriction {
  __s32 id;
  __u64 attr;
  struct dpp_restriction restriction;
};
struct dither_config {
  __u8 en : 1;
  __u8 mode : 1;
  __u8 frame_con : 1;
  __u8 frame_offset : 2;
  __u8 table_sel_r : 1;
  __u8 table_sel_g : 1;
  __u8 table_sel_b : 1;
  __u32 reserved : 24;
};
struct attribute_range {
  __u32 min;
  __u32 max;
};
struct brightness_attribute {
  struct attribute_range nits;
  struct attribute_range level;
  struct attribute_range percentage;
};
struct brightness_capability {
  struct brightness_attribute normal;
  struct brightness_attribute hbm;
};
struct tui_hw_buffer {
  __u64 fb_physical;
  __u64 fb_size;
} __attribute__((packed));
#define EXYNOS_START_TUI 0x10
#define EXYNOS_FINISH_TUI 0x11
#define EXYNOS_TUI_REQUEST_BUFFER 0x20
#define EXYNOS_TUI_RELEASE_BUFFER 0x21
struct histogram_roi {
  __u16 start_x;
  __u16 start_y;
  __u16 hsize;
  __u16 vsize;
};
struct histogram_weights {
  __u16 weight_r;
  __u16 weight_g;
  __u16 weight_b;
};
#define HISTOGRAM_BIN_COUNT 256
struct histogram_bins {
  __u16 data[HISTOGRAM_BIN_COUNT];
};
#define EXYNOS_DRM_HISTOGRAM_EVENT 0x80000000
struct exynos_drm_histogram_event {
  struct drm_event base;
  struct histogram_bins bins;
  __u32 crtc_id;
};
enum exynos_prog_pos {
  POST_DQE,
  PRE_DQE,
};
#define EXYNOS_HISTOGRAM_REQUEST 0x0
#define EXYNOS_HISTOGRAM_CANCEL 0x1
#define DRM_IOCTL_EXYNOS_HISTOGRAM_REQUEST DRM_IOW(DRM_COMMAND_BASE + EXYNOS_HISTOGRAM_REQUEST, __u32)
#define DRM_IOCTL_EXYNOS_HISTOGRAM_CANCEL DRM_IOW(DRM_COMMAND_BASE + EXYNOS_HISTOGRAM_CANCEL, __u32)
#ifdef __cplusplus
}
#endif
#endif
