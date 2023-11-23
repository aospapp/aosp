/*
 * Copyright (C) 2018-2019 ARM Limited. All rights reserved.
 *
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <inttypes.h>
#include "gralloc_helper.h"
#include "mali_gralloc_formats.h"
#include "format_info.h"

#include <unordered_map>
#include <mutex>

/* Default width aligned to whole pixel (CPU access). */
#define ALIGN_W_CPU_DEFAULT .align_w_cpu = 1

/*
 * Format table, containing format properties.
 *
 * NOTE: This table should only be used within
 * the gralloc library and not by clients directly.
 */
const format_info_t formats[] = {

	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGB_565,                .npln = 1, .ncmp = 3, .bps = 6,  .bpp_afbc = { 16, 0, 0 },  .bpp = { 16, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = true,  .is_yuv = false, .afbc = true,  .linear = true,  .yuv_transform = true,  .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RGB_565" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGB_888,                .npln = 1, .ncmp = 3, .bps = 8,  .bpp_afbc = { 24, 0, 0 },  .bpp = { 24, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = true,  .is_yuv = false, .afbc = true,  .linear = true,  .yuv_transform = true,  .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RGB_888" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBA_8888,              .npln = 1, .ncmp = 4, .bps = 8,  .bpp_afbc = { 32, 0, 0 },  .bpp = { 32, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = true,  .is_rgb = true,  .is_yuv = false, .afbc = true,  .linear = true,  .yuv_transform = true,  .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RGBA_8888" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_BGRA_8888,              .npln = 1, .ncmp = 4, .bps = 8,  .bpp_afbc = { 32, 0, 0 },  .bpp = { 32, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = true,  .is_rgb = true,  .is_yuv = false, .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_BGRA_8888" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBX_8888,              .npln = 1, .ncmp = 3, .bps = 8,  .bpp_afbc = { 32, 0, 0 },  .bpp = { 32, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = true,  .is_yuv = false, .afbc = true,  .linear = true,  .yuv_transform = true,  .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RGBX_8888" },
#if PLATFORM_SDK_VERSION >= 26
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBA_1010102,           .npln = 1, .ncmp = 4, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 32, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = true,  .is_rgb = true,  .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = true,  .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RGBA_1010102" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBA_16161616,          .npln = 1, .ncmp = 4, .bps = 16, .bpp_afbc = { 0, 0, 0 },   .bpp = { 64, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = true,  .is_rgb = true,  .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RGBA_16161616" },
#endif /* PLATFORM_SDK_VERSION >= 26 */

	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y8,                     .npln = 1, .ncmp = 1, .bps = 8,  .bpp_afbc = { 8, 0, 0 },   .bpp = { 8, 0, 0 },   .hsub = 1, .vsub = 1, .align_w =  2, .align_h =  2, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_Y8" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y16,                    .npln = 1, .ncmp = 1, .bps = 16, .bpp_afbc = { 16, 0, 0 },  .bpp = { 16, 0, 0 },  .hsub = 1, .vsub = 1, .align_w =  2, .align_h =  2, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_Y16" },

	/* 420 (8-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV420_8BIT_I,          .npln = 1, .ncmp = 3, .bps = 8,  .bpp_afbc = { 12, 0, 0 },  .bpp = { 0, 0, 0 },   .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = false, .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_YUV420_8BIT_I" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_NV12,                   .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 8, 16, 0 },  .bpp = { 8, 16, 0 },  .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_NV12" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_NV21,                   .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 8, 16, 0 },  .bpp = { 8, 16, 0 },  .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_NV21" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YV12,                   .npln = 3, .ncmp = 3, .bps = 8,  .bpp_afbc = { 8, 8, 8 },   .bpp = { 8, 8, 8 },   .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_YV12" },

	/* 422 (8-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV422_8BIT,            .npln = 1, .ncmp = 3, .bps = 8,  .bpp_afbc = { 16, 0, 0 },  .bpp = { 16, 0, 0 },  .hsub = 2, .vsub = 1, .align_w =  2, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_YUV422_8BIT" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_NV16,                   .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 8, 16, 0 },  .bpp = { 8, 16, 0 },  .hsub = 2, .vsub = 1, .align_w =  2, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_NV16" },

	/* 420 (10-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV420_10BIT_I,         .npln = 1, .ncmp = 3, .bps = 10, .bpp_afbc = { 15, 0, 0 },  .bpp = { 0, 0, 0 },   .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = false, .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_YUV420_10BIT_I" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y0L2,                   .npln = 1, .ncmp = 4, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 0, 0 },  .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 2, .has_alpha = true,  .is_rgb = false, .is_yuv = true,  .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_Y0L2" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_P010,                   .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 10, 20, 0 }, .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_P010" },

	/* 422 (10-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y210,                   .npln = 1, .ncmp = 3, .bps = 10, .bpp_afbc = { 20, 0, 0 },  .bpp = { 32, 0, 0 },  .hsub = 2, .vsub = 1, .align_w =  2, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_Y210" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_P210,                   .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 10, 20, 0 }, .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 1, .align_w =  2, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = true,  .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_P210" },

	/* 444 (10-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV444_10BIT_I,         .npln = 1, .ncmp = 3, .bps = 10, .bpp_afbc = { 30, 0, 0 },  .bpp = { 0, 0, 0 },   .hsub = 1, .vsub = 1, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = true,  .linear = false, .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_YUV444_10BIT_I" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y410,                   .npln = 1, .ncmp = 4, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 32, 0, 0 },  .hsub = 1, .vsub = 1, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = true,  .is_rgb = false, .is_yuv = true,  .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_Y410" },

	/* Other */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RAW16,                  .npln = 1, .ncmp = 1, .bps = 16, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  2, .align_h =  2, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RAW16" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RAW12,                  .npln = 1, .ncmp = 1, .bps = 12, .bpp_afbc = { 0, 0, 0 },   .bpp = { 12, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  4, .align_h =  2, .align_w_cpu = 4,    .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RAW12" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RAW10,                  .npln = 1, .ncmp = 1, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 10, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  4, .align_h =  2, .align_w_cpu = 4,    .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_RAW10" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_BLOB,                   .npln = 1, .ncmp = 1, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8, 0, 0 },   .hsub = 0, .vsub = 0, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_BLOB" },

#if PLATFORM_SDK_VERSION >= 28
	/* Depth and Stencil */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_16,               .npln = 1, .ncmp = 1, .bps = 16, .bpp_afbc = { 0, 0, 0},    .bpp = { 16, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_16" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24,               .npln = 1, .ncmp = 1, .bps = 24, .bpp_afbc = { 0, 0, 0 },   .bpp = { 24, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24_STENCIL_8,     .npln = 1, .ncmp = 2, .bps = 24, .bpp_afbc = { 0, 0, 0 },   .bpp = { 32, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24_STENCIL_8" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F,              .npln = 1, .ncmp = 1, .bps = 32, .bpp_afbc = { 0, 0, 0 },   .bpp = { 32, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F_STENCIL_8,    .npln = 1, .ncmp = 2, .bps = 32, .bpp_afbc = { 0, 0, 0 },   .bpp = { 40, 0, 0 },  .hsub = 0, .vsub = 0, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F_STENCIL_8" },
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_STENCIL_8,              .npln = 1, .ncmp = 1, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  0, 0 },  .hsub = 0, .vsub = 0, .align_w =  1, .align_h =  1, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = false, .afbc = false, .linear = true,  .yuv_transform = false, .flex = false, .planes_contiguous = false, .name = "MALI_GRALLOC_FORMAT_INTERNAL_STENCIL_8" },
#endif
	/* Exynos Formats */
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M,              .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL,         .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M,              .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YV12_M,                      .npln = 3, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,   8, 8 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YV12_M" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M,               .npln = 3, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,   8, 8 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B,         .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_TILED,        .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 16,.has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_TILED" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN,               .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B,          .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M,                .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P,                 .npln = 3, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,   8, 8 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P" },
	{ .id = HAL_PIXEL_FORMAT_YCrCb_420_SP,                       .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_YCrCb_420_SP" },

	/* SBWC Formats */
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC,          .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC,      .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC,         .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC,     .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC,         .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC,     .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50,     .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75,     .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50,      .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75,      .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40, .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60, .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80, .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40,  .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60,  .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60" },
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80,  .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w =  2, .align_h =  2, ALIGN_W_CPU_DEFAULT, .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80" },

	/* Google Formats */
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_NV12_SP,                     .npln = 2, .ncmp = 3, .bps = 8,  .bpp_afbc = { 0, 0, 0 },   .bpp = { 8,  16, 0 }, .hsub = 2, .vsub = 2, .align_w = 64, .align_h =  8, .align_w_cpu = 64,   .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = true,  .name = "HAL_PIXEL_FORMAT_GOOGLE_NV12_SP" },
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_NV12_SP_10B,                 .npln = 2, .ncmp = 3, .bps = 10, .bpp_afbc = { 0, 0, 0 },   .bpp = { 16, 32, 0 }, .hsub = 2, .vsub = 2, .align_w = 64, .align_h =  8, .align_w_cpu = 64,   .tile_size = 1, .has_alpha = false, .is_rgb = false, .is_yuv = true,  .afbc = false,  .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = true,  .name = "HAL_PIXEL_FORMAT_GOOGLE_NV12_SP_10B" },
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_R_8,                         .npln = 1, .ncmp = 1, .bps = 8,  .bpp_afbc = { 8, 0, 0 },   .bpp = { 8, 0, 0 },   .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = true,  .is_yuv = false, .afbc = true,   .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_GOOGLE_R_8" },
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_RG_88,                       .npln = 1, .ncmp = 2, .bps = 8,  .bpp_afbc = { 16, 0, 0 },  .bpp = { 16, 0, 0 },  .hsub = 0, .vsub = 0, .align_w = 16, .align_h = 16, .align_w_cpu = 16,   .tile_size = 1, .has_alpha = false, .is_rgb = true,  .is_yuv = false, .afbc = true,   .linear = true, .yuv_transform = false, .flex = true,  .planes_contiguous = false, .name = "HAL_PIXEL_FORMAT_GOOGLE_RG_88" },

};

const size_t num_formats = sizeof(formats)/sizeof(formats[0]);


#define S_LIN F_LIN
#define S_AFBC F_AFBC

/* This table represents the superset of flags for each base format and producer/consumer.
 * Where IP does not support a capability, it should be defined and not set.
 */
const format_ip_support_t formats_ip_support[] = {

	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGB_565,                .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_NONE, .dpu_rd = F_LIN | S_AFBC,      .dpu_aeu_wr = F_AFBC, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGB_888,                .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_LIN,  .dpu_rd = F_LIN | F_AFBC,      .dpu_aeu_wr = F_AFBC, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBA_8888,              .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_LIN,  .dpu_rd = F_LIN | F_AFBC,      .dpu_aeu_wr = F_AFBC, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_BGRA_8888,              .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = S_LIN,          .gpu_rd = S_LIN,          .dpu_wr = F_LIN,  .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBX_8888,              .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = S_LIN | S_AFBC, .gpu_rd = S_LIN | S_AFBC, .dpu_wr = F_LIN,  .dpu_rd = F_LIN | S_AFBC,      .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
#if PLATFORM_SDK_VERSION >= 26
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBA_1010102,           .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_LIN,  .dpu_rd = F_LIN,               .dpu_aeu_wr = F_AFBC, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RGBA_16161616,          .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
#endif /* PLATFORM_SDK_VERSION >= 26 */

	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y8,                     .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y16,                    .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},

	/* 420 (8-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV420_8BIT_I,          .cpu_wr = F_NONE, .cpu_rd = F_NONE,  .gpu_wr = F_NONE,         .gpu_rd = F_AFBC,         .dpu_wr = F_NONE, .dpu_rd = F_AFBC,              .dpu_aeu_wr = F_AFBC, .vpu_wr = F_AFBC, .vpu_rd = F_AFBC},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_NV12,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_LIN,  .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_NV21,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = S_LIN,          .gpu_rd = S_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YV12,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},

	/* 422 (8-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV422_8BIT,            .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_NONE, .dpu_rd = F_LIN | F_AFBC,      .dpu_aeu_wr = F_AFBC, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_NV16,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},

	/* 420 (10-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV420_10BIT_I,         .cpu_wr = F_NONE, .cpu_rd = F_NONE,  .gpu_wr = F_AFBC,         .gpu_rd = F_AFBC,         .dpu_wr = F_NONE, .dpu_rd = F_AFBC,              .dpu_aeu_wr = F_AFBC, .vpu_wr = F_AFBC, .vpu_rd = F_AFBC},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y0L2,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_P010,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},

	/* 422 (10-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y210,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_P210,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},

	/* 444 (10-bit) */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_YUV444_10BIT_I,         .cpu_wr = F_NONE, .cpu_rd = F_NONE,  .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_Y410,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},

	/* Other */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RAW16,                  .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RAW12,                  .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_RAW10,                  .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_BLOB,                   .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},

#if PLATFORM_SDK_VERSION >= 28
	/* Depth and Stencil */
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_16,               .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24,               .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24_STENCIL_8,     .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F,              .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F_STENCIL_8,    .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
	{ .id = MALI_GRALLOC_FORMAT_INTERNAL_STENCIL_8,              .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_NONE,         .gpu_rd = F_NONE,         .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN, .vpu_rd = F_LIN},
#endif
	/* Exynos Formats */
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M,              .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL,         .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M,              .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YV12_M,                      .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M,               .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B,         .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_TILED,        .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN,               .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B,          .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M,                .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P,                 .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN,          .gpu_rd = F_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_YCrCb_420_SP,                       .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = S_LIN,          .gpu_rd = S_LIN,          .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},

	/* SBWC Formats */
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC,          .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC,      .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC,         .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC,     .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC,         .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC,     .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},

	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50,     .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75,     .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50,      .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75,      .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40, .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60, .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80, .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40,  .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60,  .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80,  .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_NONE, .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_NV12_SP,                     .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_LIN,  .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_NV12_SP_10B,                 .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN         , .gpu_rd = F_LIN         , .dpu_wr = F_LIN,  .dpu_rd = F_LIN,               .dpu_aeu_wr = F_NONE, .vpu_wr = F_LIN,  .vpu_rd = F_LIN},
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_R_8,                         .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_NONE, .vpu_rd = F_NONE},
	{ .id = HAL_PIXEL_FORMAT_GOOGLE_RG_88,                       .cpu_wr = F_LIN,  .cpu_rd = F_LIN,   .gpu_wr = F_LIN | F_AFBC, .gpu_rd = F_LIN | F_AFBC, .dpu_wr = F_NONE, .dpu_rd = F_NONE,              .dpu_aeu_wr = F_NONE, .vpu_wr = F_NONE, .vpu_rd = F_NONE},
};

const size_t num_ip_formats = sizeof(formats_ip_support)/sizeof(formats_ip_support[0]);

typedef struct
{
    uint32_t hal_format;
    bool is_flex;
    uint32_t internal_format;
} hal_int_fmt;


static const hal_int_fmt hal_to_internal_format[] =
{
	{ HAL_PIXEL_FORMAT_RGBA_8888,              false, MALI_GRALLOC_FORMAT_INTERNAL_RGBA_8888 },
	{ HAL_PIXEL_FORMAT_RGBX_8888,              false, MALI_GRALLOC_FORMAT_INTERNAL_RGBX_8888 },
	{ HAL_PIXEL_FORMAT_RGB_888,                false, MALI_GRALLOC_FORMAT_INTERNAL_RGB_888 },
	{ HAL_PIXEL_FORMAT_RGB_565,                false, MALI_GRALLOC_FORMAT_INTERNAL_RGB_565 },
	{ HAL_PIXEL_FORMAT_BGRA_8888,              false, MALI_GRALLOC_FORMAT_INTERNAL_BGRA_8888 },
	{ HAL_PIXEL_FORMAT_YCbCr_422_SP,           false, MALI_GRALLOC_FORMAT_INTERNAL_NV16 },
	{ HAL_PIXEL_FORMAT_YCrCb_420_SP,           false, MALI_GRALLOC_FORMAT_INTERNAL_NV21 },
	{ HAL_PIXEL_FORMAT_YCbCr_422_I,            false, MALI_GRALLOC_FORMAT_INTERNAL_YUV422_8BIT },
#if PLATFORM_SDK_VERSION >= 26
	{ HAL_PIXEL_FORMAT_RGBA_FP16,              false, MALI_GRALLOC_FORMAT_INTERNAL_RGBA_16161616 },
#endif
	{ HAL_PIXEL_FORMAT_RAW16,                  false, MALI_GRALLOC_FORMAT_INTERNAL_RAW16 },
	{ HAL_PIXEL_FORMAT_BLOB,                   false, MALI_GRALLOC_FORMAT_INTERNAL_BLOB },
	{ HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED, true,  MALI_GRALLOC_FORMAT_INTERNAL_NV12 },
	{ HAL_PIXEL_FORMAT_YCbCr_420_888,          true,  MALI_GRALLOC_FORMAT_INTERNAL_NV12 },
	{ HAL_PIXEL_FORMAT_RAW_OPAQUE,             false, MALI_GRALLOC_FORMAT_INTERNAL_UNDEFINED },
	{ HAL_PIXEL_FORMAT_RAW10,                  false, MALI_GRALLOC_FORMAT_INTERNAL_RAW10 },
	{ HAL_PIXEL_FORMAT_RAW12,                  false, MALI_GRALLOC_FORMAT_INTERNAL_RAW12 },
	{ HAL_PIXEL_FORMAT_YCbCr_422_888,          true,  MALI_GRALLOC_FORMAT_INTERNAL_YUV422_8BIT },
	{ HAL_PIXEL_FORMAT_YCbCr_444_888,          true,  MALI_GRALLOC_FORMAT_INTERNAL_UNDEFINED },
	{ HAL_PIXEL_FORMAT_FLEX_RGB_888,           true,  MALI_GRALLOC_FORMAT_INTERNAL_UNDEFINED },
	{ HAL_PIXEL_FORMAT_FLEX_RGBA_8888,         true,  MALI_GRALLOC_FORMAT_INTERNAL_UNDEFINED },
#if PLATFORM_SDK_VERSION >= 26
	{ HAL_PIXEL_FORMAT_RGBA_1010102,           false, MALI_GRALLOC_FORMAT_INTERNAL_RGBA_1010102 },
#endif
#if PLATFORM_SDK_VERSION >= 28
	{ HAL_PIXEL_FORMAT_DEPTH_16,               false, MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_16 },
	{ HAL_PIXEL_FORMAT_DEPTH_24,               false, MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24 },
	{ HAL_PIXEL_FORMAT_DEPTH_24_STENCIL_8,     false, MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_24_STENCIL_8 },
	{ HAL_PIXEL_FORMAT_DEPTH_32F,              false, MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F },
	{ HAL_PIXEL_FORMAT_DEPTH_32F_STENCIL_8,    false, MALI_GRALLOC_FORMAT_INTERNAL_DEPTH_32F_STENCIL_8 },
	{ HAL_PIXEL_FORMAT_STENCIL_8,              false, MALI_GRALLOC_FORMAT_INTERNAL_STENCIL_8 },
	{ HAL_PIXEL_FORMAT_YCBCR_P010,             false, MALI_GRALLOC_FORMAT_INTERNAL_P010 },
#endif
	{ HAL_PIXEL_FORMAT_Y8,                     false, MALI_GRALLOC_FORMAT_INTERNAL_Y8 },
	{ HAL_PIXEL_FORMAT_Y16,                    false, MALI_GRALLOC_FORMAT_INTERNAL_Y16 },
	{ HAL_PIXEL_FORMAT_YV12,                   false, MALI_GRALLOC_FORMAT_INTERNAL_YV12 },
	/* Exynos Formats */
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP,         true, MALI_GRALLOC_FORMAT_INTERNAL_NV12 },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M,       false, HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M      },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL,  false, HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M,       false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M      },
	{ HAL_PIXEL_FORMAT_EXYNOS_YV12_M,               false, HAL_PIXEL_FORMAT_EXYNOS_YV12_M              },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M,        false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M       },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B,  false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_TILED, false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_TILED},
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN,        false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN       },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B,   false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B  },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M,         false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M        },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P,          false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P         },
	/* SBWC Formats */
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC,       false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC      },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC,   false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC  },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC,      false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC     },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC,  false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC,      false, HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC     },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC,  false, HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC },

	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50,     false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50     },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75,     false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75     },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50,      false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50      },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75,      false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75      },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40, false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40 },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60, false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60 },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80, false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80 },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40,  false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40  },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60,  false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60  },
	{ HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80,  false, HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80  },
	{ HAL_PIXEL_FORMAT_GOOGLE_NV12_SP,                     true,  HAL_PIXEL_FORMAT_GOOGLE_NV12_SP                     },
	{ HAL_PIXEL_FORMAT_GOOGLE_NV12_SP_10B,                 true,  HAL_PIXEL_FORMAT_GOOGLE_NV12_SP_10B                 },
	{ HAL_PIXEL_FORMAT_GOOGLE_R_8,                         false,  HAL_PIXEL_FORMAT_GOOGLE_R_8                        },
	{ HAL_PIXEL_FORMAT_GOOGLE_RG_88,                       false,  HAL_PIXEL_FORMAT_GOOGLE_RG_88                      },
};

const size_t num_hal_formats = sizeof(hal_to_internal_format)/sizeof(hal_to_internal_format[0]);


/*
 *  Finds "Look-up Table" index for the given format
 *
 * @param base_format [in]   Format for which index is required.
 *
 * @return index, when the format is found in the look up table
 *         -1, otherwise
 *
 */
int32_t get_format_index(const uint32_t base_format)
{
	int32_t format_idx;
	for (format_idx = 0; format_idx < (int32_t)num_formats; format_idx++)
	{
		if (formats[format_idx].id == base_format)
		{
			break;
		}
	}
	if (format_idx >= (int32_t)num_formats)
	{
		ALOGE("ERROR: Format allocation info not found for format: %" PRIx32, base_format);
		return -1;
	}

	return format_idx;
}


int32_t get_ip_format_index(const uint32_t base_format)
{
	int32_t format_idx;
	for (format_idx = 0; format_idx < (int32_t)num_ip_formats; format_idx++)
	{
		if (formats_ip_support[format_idx].id == base_format)
		{
			break;
		}
	}
	if (format_idx >= (int32_t)num_ip_formats)
	{
		ALOGE("ERROR: IP support not found for format: %" PRIx32, base_format);
		return -1;
	}

	return format_idx;
}


/*
 * Attempt to map base HAL format to an internal format and
 * validate format is supported for allocation.
 *
 * @param map_to_internal [in]  1: Forces mapping to (and validation of) internal format
 *                              0: Only maps flex HAL formats to internal
 *
 * @return internal_format, where internal format is found
 *         HAL format, where map_to_internal == 0 and base_format is not flex
 *         MALI_GRALLOC_FORMAT_INTERNAL_UNDEFINED, otherwise
 *
 * NOTE: Base format might be either a HAL format or (already) an internal format.
 *
 */
uint32_t get_internal_format(const uint32_t base_format, const bool map_to_internal)
{
	uint32_t internal_format = base_format;

	if (is_exynos_format(internal_format))
		goto out;

	for (int idx = 0; idx < (int)num_hal_formats; idx++)
	{
		if (hal_to_internal_format[idx].hal_format == base_format)
		{
			if (hal_to_internal_format[idx].is_flex || map_to_internal)
			{
				internal_format = hal_to_internal_format[idx].internal_format;
			}
			break;
		}
	}

	/* Ensure internal format is valid when expected. */
	if (map_to_internal && get_format_index(internal_format) < 0)
	{
		internal_format = MALI_GRALLOC_FORMAT_INTERNAL_UNDEFINED;
	}

out:
	return internal_format;
}


bool is_power2(uint8_t n)
{
	return ((n & (n-1)) == 0);
}


bool sanitize_formats(void)
{
	bool fail = false;

	for (int i = 0; i < (int)num_formats; i++)
	{
		const format_info_t * format = &formats[i];

		/* Identify invalid values. */
		if (format->id == 0 ||
		    format->npln == 0 || format->npln > 3 ||
		    format->ncmp == 0 ||
		    format->bps == 0 ||
		    format->align_w == 0 ||
		    format->align_h == 0 ||
		    format->align_w_cpu == 0 ||
		    format->tile_size == 0)
		{
			AERR("Format [id:0x%" PRIx32 "] property zero/out of range (unexpected)", format->id);
			fail = true;
		}

		if (format->is_rgb && format->is_yuv)
		{
			AERR("Format [id:0x%" PRIx32 "] cannot be both RGB and YUV", format->id);
			fail = true;
		}

		if (format->npln > format->ncmp)
		{
			AERR("Format [id:0x%" PRIx32 "] planes cannot exceed components", format->id);
			fail = true;
		}

		if (format->linear && (format->bps > format->bpp[0]))
		{
			AERR("Format [id:0x%" PRIx32 "] bpp should be greater than/equal to bps", format->id);
			fail = true;
		}

		if (format->afbc && (format->bps > format->bpp_afbc[0]))
		{
			AERR("Format [id:0x%" PRIx32 "] bpp_afbc should be greater than/equal to bps", format->id);
			fail = true;
		}

		if (!format->linear && format->tile_size > 1)
		{
			AERR("Format [id:0x%" PRIx32 "] tile_size must be set to 1 for formats without linear support", format->id);
			fail = true;
		}

		for (int pln = 0; pln < 3; pln++)
		{
			if (format->linear && (pln < format->npln) && (format->bpp[pln] == 0))
			{
				AERR("Format [id:0x%" PRIx32 "] does not have bpp defined for plane: %d", format->id, pln);
				fail = true;
			}
			else if (format->linear && (pln >= format->npln) && (format->bpp[pln] != 0))
			{
				AERR("Format [id:0x%" PRIx32 "] should not have bpp defined for plane: %d", format->id, pln);
				fail = true;
			}
			else if (!format->linear && (format->bpp[pln] != 0))
			{
				AERR("Format [id:0x%" PRIx32 "] which doesn't support linear should not have bpp defined", format->id);
				fail = true;
			}

			if (format->afbc && (pln < format->npln) && (format->bpp_afbc[pln] == 0))
			{
				AERR("Format [id:0x%" PRIx32 "] does not have bpp_afbc defined for plane: %d", format->id, pln);
				fail = true;
			}
			else if (format->afbc && (pln >= format->npln) && (format->bpp_afbc[pln] != 0))
			{
				AERR("Format [id:0x%" PRIx32 "] should not have bpp_afbc defined for plane: %d", format->id, pln);
				fail = true;
			}
		}

		if (format->is_yuv)
		{
			if (format->hsub == 0 || format->vsub == 0)
			{
				AERR("Format [id:0x%" PRIx32 "] hsub and vsub should be non-zero (YUV)", format->id);
				fail = true;
			}

			if (!is_power2(format->hsub) || !is_power2(format->vsub))
			{
				AERR("Format [id:0x%" PRIx32 "] hsub and vsub should be powers of 2", format->id);
				fail = true;
			}

			if ((format->align_w % format->hsub) != 0)
			{
				AERR("Format [id:0x%" PRIx32 "] align_w should be a multiple of hsub", format->id);
				fail = true;
			}

			if ((format->align_h % format->vsub) != 0)
			{
				AERR("Format [id:0x%" PRIx32 "] align_h should be a multiple of vsub", format->id);
				fail = true;
			}
		}
		else
		{
			if (format->hsub != 0 || format->vsub != 0)
			{
				AERR("Format [id:0x%" PRIx32 "] hsub and vsub should be zero (non-YUV)", format->id);
				fail = true;
			}
		}

		if (format->align_w == 0 || format->align_h == 0)
		{
			AERR("Format [id:0x%" PRIx32 "] align_w and align_h should be non-zero", format->id);
			fail = true;
		}
		else
		{
			if (!is_power2(format->align_w) || !is_power2(format->align_h))
			{
				AERR("Format [id:0x%" PRIx32 "] align_w and align_h should be powers of 2", format->id);
				fail = true;
			}

			if (!is_power2(format->align_w_cpu))
			{
				AERR("Format [id:0x%" PRIx32 "] align_w_cpu should be a power of 2", format->id);
				fail = true;
			}
		}
	}

	return fail;
}

const char *format_name(uint32_t base_format) {
	static std::once_flag name_map_flag;
	using NameMap = std::unordered_map<uint32_t, const char*>;
	static NameMap name_map;

	std::call_once(name_map_flag, []() {
		for (size_t i = 0; i < num_formats; ++i) {
			const format_info_t &format = formats[i];
			if (name_map.count(format.id)) {
				AERR("Format id 0x%" PRIx32 " mapped to %s and to %s",
				     format.id, name_map[format.id], format.name);
			} else {
				name_map[format.id] = format.name;
			}
		}
	});

	NameMap::const_iterator i = name_map.find(base_format);
	if (i == name_map.end()) {
		return "<unrecognized format>";
	}
	return i->second;
}

bool is_exynos_format(uint32_t base_format)
{
	switch (base_format)
	{
		case HAL_PIXEL_FORMAT_YCrCb_420_SP:
		case HAL_PIXEL_FORMAT_EXYNOS_YV12_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_TILED:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80:
			return true;
	}

	return false;
}
