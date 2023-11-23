/*
 * Copyright (C) 2016-2019 ARM Limited. All rights reserved.
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
#include <assert.h>
#include <atomic>

#include <cutils/properties.h>

#if GRALLOC_VERSION_MAJOR <= 1
#include <hardware/hardware.h>
#endif

#if GRALLOC_VERSION_MAJOR == 1
#include <hardware/gralloc1.h>
#elif GRALLOC_VERSION_MAJOR == 0
#include <hardware/gralloc.h>
#endif

#include "mali_gralloc_module.h"
#include "mali_gralloc_bufferallocation.h"
#include "mali_gralloc_ion.h"
#include "mali_gralloc_private_interface_types.h"
#include "mali_gralloc_buffer.h"
#include "gralloc_buffer_priv.h"
#include "mali_gralloc_bufferdescriptor.h"
#include "mali_gralloc_debug.h"
#include "format_info.h"

#define AFBC_PIXELS_PER_BLOCK 256
#define AFBC_HEADER_BUFFER_BYTES_PER_BLOCKENTRY 16
#define EXT_SIZE 256

bool afbc_format_fallback(uint32_t * const format_idx, const uint64_t usage, bool force);


/*
 * Get a global unique ID
 */
static uint64_t getUniqueId()
{
	static std::atomic<uint32_t> counter(0);
	uint64_t id = static_cast<uint64_t>(getpid()) << 32;
	return id | counter++;
}

static void afbc_buffer_align(const bool is_tiled, int *size)
{
	const uint16_t AFBC_BODY_BUFFER_BYTE_ALIGNMENT = 1024;

	int buffer_byte_alignment = AFBC_BODY_BUFFER_BYTE_ALIGNMENT;

	if (is_tiled)
	{
		buffer_byte_alignment = 4 * AFBC_BODY_BUFFER_BYTE_ALIGNMENT;
	}

	*size = GRALLOC_ALIGN(*size, buffer_byte_alignment);
}

/*
 * Obtain AFBC superblock dimensions from type.
 */
static rect_t get_afbc_sb_size(AllocBaseType alloc_base_type)
{
	const uint16_t AFBC_BASIC_BLOCK_WIDTH = 16;
	const uint16_t AFBC_BASIC_BLOCK_HEIGHT = 16;
	const uint16_t AFBC_WIDE_BLOCK_WIDTH = 32;
	const uint16_t AFBC_WIDE_BLOCK_HEIGHT = 8;
	const uint16_t AFBC_EXTRAWIDE_BLOCK_WIDTH = 64;
	const uint16_t AFBC_EXTRAWIDE_BLOCK_HEIGHT = 4;

	rect_t sb = {0, 0};

	switch(alloc_base_type)
	{
		case UNCOMPRESSED:
			break;
		case AFBC:
			sb.width = AFBC_BASIC_BLOCK_WIDTH;
			sb.height = AFBC_BASIC_BLOCK_HEIGHT;
			break;
		case AFBC_WIDEBLK:
			sb.width = AFBC_WIDE_BLOCK_WIDTH;
			sb.height = AFBC_WIDE_BLOCK_HEIGHT;
			break;
		case AFBC_EXTRAWIDEBLK:
			sb.width = AFBC_EXTRAWIDE_BLOCK_WIDTH;
			sb.height = AFBC_EXTRAWIDE_BLOCK_HEIGHT;
			break;
	}
	return sb;
}

/*
 * Obtain AFBC superblock dimensions for specific plane.
 *
 * See alloc_type_t for more information.
 */
static rect_t get_afbc_sb_size(alloc_type_t alloc_type, const uint8_t plane)
{
	if (plane > 0 && alloc_type.is_afbc() && alloc_type.is_multi_plane)
	{
		return get_afbc_sb_size(AFBC_EXTRAWIDEBLK);
	}
	else
	{
		return get_afbc_sb_size(alloc_type.primary_type);
	}
}


bool get_alloc_type(const uint64_t format_ext,
                    const uint32_t format_idx,
                    const uint64_t usage,
                    alloc_type_t * const alloc_type)
{
	alloc_type->primary_type = UNCOMPRESSED;
	alloc_type->is_multi_plane = formats[format_idx].npln > 1;
	alloc_type->is_tiled = false;
	alloc_type->is_padded = false;
	alloc_type->is_frontbuffer_safe = false;

	/* Determine AFBC type for this format. This is used to decide alignment.
	   Split block does not affect alignment, and therefore doesn't affect the allocation type. */
	if (format_ext & MALI_GRALLOC_INTFMT_AFBCENABLE_MASK)
	{
		/* YUV transform shall not be enabled for a YUV format */
		if ((formats[format_idx].is_yuv == true) && (format_ext & MALI_GRALLOC_INTFMT_AFBC_YUV_TRANSFORM))
		{
			ALOGW("YUV Transform is incorrectly enabled for format = (%s 0x%x). Extended internal format = (%s 0x%" PRIx64 ")\n",
			      format_name(formats[format_idx].id), formats[format_idx].id, format_name(format_ext), format_ext);
		}

		/* Determine primary AFBC (superblock) type. */
		alloc_type->primary_type = AFBC;
		if (format_ext & MALI_GRALLOC_INTFMT_AFBC_WIDEBLK)
		{
			alloc_type->primary_type = AFBC_WIDEBLK;
		}
		else if (format_ext & MALI_GRALLOC_INTFMT_AFBC_EXTRAWIDEBLK)
		{
			alloc_type->primary_type = AFBC_EXTRAWIDEBLK;
		}

		if (format_ext & MALI_GRALLOC_INTFMT_AFBC_TILED_HEADERS)
		{
			alloc_type->is_tiled = true;

			if (formats[format_idx].npln > 1 &&
				(format_ext & MALI_GRALLOC_INTFMT_AFBC_EXTRAWIDEBLK) == 0)
			{
				ALOGW("Extra-wide AFBC must be signalled for multi-plane formats. "
				      "Falling back to single plane AFBC.");
				alloc_type->is_multi_plane = false;
			}

			if (format_ext & MALI_GRALLOC_INTFMT_AFBC_DOUBLE_BODY)
			{
				alloc_type->is_frontbuffer_safe = true;
			}
		}
		else
		{
			if (formats[format_idx].npln > 1)
			{
				ALOGW("Multi-plane AFBC is not supported without tiling. "
				      "Falling back to single plane AFBC.");
			}
			alloc_type->is_multi_plane = false;
		}

		if (format_ext & MALI_GRALLOC_INTFMT_AFBC_EXTRAWIDEBLK &&
			!alloc_type->is_tiled)
		{
			/* Headers must be tiled for extra-wide. */
			ALOGE("ERROR: Invalid to specify extra-wide block without tiled headers.");
			return false;
		}

		if (alloc_type->is_frontbuffer_safe &&
		    (format_ext & (MALI_GRALLOC_INTFMT_AFBC_WIDEBLK | MALI_GRALLOC_INTFMT_AFBC_EXTRAWIDEBLK)))
		{
			ALOGE("ERROR: Front-buffer safe not supported with wide/extra-wide block.");
		}

		if (formats[format_idx].npln == 1 &&
		    format_ext & MALI_GRALLOC_INTFMT_AFBC_WIDEBLK &&
		    format_ext & MALI_GRALLOC_INTFMT_AFBC_EXTRAWIDEBLK)
		{
			/* "Wide + Extra-wide" implicitly means "multi-plane". */
			ALOGE("ERROR: Invalid to specify multiplane AFBC with single plane format.");
			return false;
		}

		if (usage & MALI_GRALLOC_USAGE_AFBC_PADDING)
		{
			alloc_type->is_padded = true;
		}
	}

	return true;
}

/*
 * Initialise AFBC header based on superblock layout.
 * Width and height should already be AFBC aligned.
 */
void init_afbc(uint8_t *buf, const uint64_t alloc_format,
               const bool is_multi_plane,
               const int w, const int h)
{
	const bool is_tiled = ((alloc_format & MALI_GRALLOC_INTFMT_AFBC_TILED_HEADERS)
	                         == MALI_GRALLOC_INTFMT_AFBC_TILED_HEADERS);
	const uint32_t n_headers = (w * h) / AFBC_PIXELS_PER_BLOCK;
	int body_offset = n_headers * AFBC_HEADER_BUFFER_BYTES_PER_BLOCKENTRY;

	afbc_buffer_align(is_tiled, &body_offset);

	/*
	 * Declare the AFBC header initialisation values for each superblock layout.
	 * Tiled headers (AFBC 1.2) can be initialised to zero for non-subsampled formats
	 * (SB layouts: 0, 3, 4, 7).
	 */
	uint32_t headers[][4] = {
		{ (uint32_t)body_offset, 0x1, 0x10000, 0x0 }, /* Layouts 0, 3, 4, 7 */
		{ ((uint32_t)body_offset + (1 << 28)), 0x80200040, 0x1004000, 0x20080 } /* Layouts 1, 5 */
	};
	if ((alloc_format & MALI_GRALLOC_INTFMT_AFBC_TILED_HEADERS))
	{
		/* Zero out body_offset for non-subsampled formats. */
		memset(headers[0], 0, sizeof(uint32_t) * 4);
	}

	/* Map base format to AFBC header layout */
	const uint32_t base_format = alloc_format & MALI_GRALLOC_INTFMT_FMT_MASK;

	/* Sub-sampled formats use layouts 1 and 5 which is index 1 in the headers array.
	 * 1 = 4:2:0 16x16, 5 = 4:2:0 32x8.
	 *
	 * Non-subsampled use layouts 0, 3, 4 and 7, which is index 0.
	 * 0 = 16x16, 3 = 32x8 + split, 4 = 32x8, 7 = 64x4.
	 *
	 * When using separated planes for YUV formats, the header layout is the non-subsampled one
	 * as there is a header per-plane and there is no sub-sampling within the plane.
	 * Separated plane only supports 32x8 or 64x4 for the luma plane, so the first plane must be 4 or 7.
	 * Seperated plane only supports 64x4 for subsequent planes, so these must be header layout 7.
	 */
	const uint32_t layout = is_subsampled_yuv(base_format) && !is_multi_plane ? 1 : 0;

	ALOGV("Writing AFBC header layout %d for format (%s %" PRIx32 ")", layout, format_name(base_format), base_format);

	for (uint32_t i = 0; i < n_headers; i++)
	{
		memcpy(buf, headers[layout], sizeof(headers[layout]));
		buf += sizeof(headers[layout]);
	}
}

static int max(int a, int b)
{
	return a > b ? a : b;
}

static int max(int a, int b, int c)
{
	return c > max(a, b) ? c : max(a, b);
}

static int max(int a, int b, int c, int d)
{
	return d > max(a, b, c) ? d : max(a, b, c);
}

/*
 * Obtain plane allocation dimensions (in pixels).
 *
 * NOTE: pixel stride, where defined for format, is
 * incorporated into allocation dimensions.
 */
static void get_pixel_w_h(uint32_t * const width,
                          uint32_t * const height,
                          const format_info_t format,
                          const alloc_type_t alloc_type,
                          const uint8_t plane,
                          bool has_cpu_usage)
{
	const rect_t sb = get_afbc_sb_size(alloc_type, plane);

	/*
	 * Round-up plane dimensions, to multiple of:
	 * - Samples for all channels (sub-sampled formats)
	 * - Memory bytes/words (some packed formats)
	 */
	if (plane == 0 || !format.planes_contiguous)
	{
		*width = GRALLOC_ALIGN(*width, format.align_w);
		*height = GRALLOC_ALIGN(*height, format.align_h);
	}

	/*
	 * Sub-sample (sub-sampled) planes.
	 */
	if (plane > 0)
	{
		*width /= format.hsub;
		*height /= format.vsub;
	}

	/*
	 * Pixel alignment (width),
	 * where format stride is stated in pixels.
	 */
	int pixel_align_w = 0, pixel_align_h = 0;
	if (has_cpu_usage && (plane == 0 || !format.planes_contiguous))
	{
		pixel_align_w = format.align_w_cpu;
	}
	else if (alloc_type.is_afbc())
	{
#define HEADER_STRIDE_ALIGN_IN_SUPER_BLOCKS (0)
		uint32_t num_sb_align = 0;
		if (alloc_type.is_padded && !format.is_yuv)
		{
			/* Align to 4 superblocks in width --> 64-byte,
			 * assuming 16-byte header per superblock.
			 */
			num_sb_align = 4;
		}
		pixel_align_w = max(HEADER_STRIDE_ALIGN_IN_SUPER_BLOCKS, num_sb_align) * sb.width;
	}

	/*
	 * Determine AFBC tile size when allocating tiled headers.
	 */
	rect_t afbc_tile = sb;
	if (alloc_type.is_tiled)
	{
		afbc_tile.width = format.bpp_afbc[plane] > 32 ? 4 * afbc_tile.width : 8 * afbc_tile.width;
		afbc_tile.height = format.bpp_afbc[plane] > 32 ? 4 * afbc_tile.height : 8 * afbc_tile.height;
	}

	if (AFBC_WIDEBLK == alloc_type.primary_type && !alloc_type.is_tiled)
	{
		/*
		 * Special case for wide block (32x8) AFBC with linear (non-tiled)
		 * headers: hardware reads and writes 32x16 blocks so we need to
		 * pad the body buffer accordingly.
		 *
		 * Note that this branch will not be taken for multi-plane AFBC
		 * since that requires tiled headers.
		 */
		pixel_align_h = max(pixel_align_h, 16);
	}

	ALOGV("Plane[%hhu]: [SUB-SAMPLE] w:%d, h:%d\n", plane, *width, *height);
	ALOGV("Plane[%hhu]: [PIXEL_ALIGN] w:%d, h:%d\n", plane, pixel_align_w, pixel_align_h);
	ALOGV("Plane[%hhu]: [LINEAR_TILE] w:%" PRIu16 "\n", plane, format.tile_size);
	ALOGV("Plane[%hhu]: [AFBC_TILE] w:%" PRIu16 ", h:%" PRIu16 "\n", plane, afbc_tile.width, afbc_tile.height);

	*width = GRALLOC_ALIGN(*width, max(1, pixel_align_w, format.tile_size, afbc_tile.width));
	*height = GRALLOC_ALIGN(*height, max(1, pixel_align_h, format.tile_size, afbc_tile.height));

}



static uint32_t gcd(uint32_t a, uint32_t b)
{
	uint32_t r, t;

	if (a == b)
	{
		return a;
	}
	else if (a < b)
	{
		t = a;
		a = b;
		b = t;
	}

	while (b != 0)
	{
		r = a % b;
		a = b;
		b = r;
	}

	return a;
}

uint32_t lcm(uint32_t a, uint32_t b)
{
	if (a != 0 && b != 0)
	{
		return (a * b) / gcd(a, b);
	}

	return max(a, b);
}


/*
 * YV12 stride has additional complexity since chroma stride
 * must conform to the following:
 *
 * c_stride = ALIGN(stride/2, 16)
 *
 * Since the stride alignment must satisfy both CPU and HW
 * constraints, the luma stride must be doubled.
 */
static void update_yv12_stride(int8_t plane,
                               uint32_t luma_stride,
                               uint32_t stride_align,
                               uint32_t * byte_stride)
{
	if (plane == 0)
	{
		/*
		 * Ensure luma stride is aligned to "2*lcm(hw_align, cpu_align)" so
		 * that chroma stride can satisfy both CPU and HW alignment
		 * constraints when only half luma stride (as mandated for format).
		 */
		*byte_stride = GRALLOC_ALIGN(luma_stride, 2 * stride_align);
	}
	else
	{
		/*
		 * Derive chroma stride from luma and verify it is:
		 * 1. Aligned to lcm(hw_align, cpu_align)
		 * 2. Multiple of 16px (16 bytes)
		 */
		*byte_stride = luma_stride / 2;
		assert(*byte_stride == GRALLOC_ALIGN(*byte_stride, stride_align));
		assert(*byte_stride & 15 == 0);
	}
}


/*
 * Calculate allocation size.
 *
 * Determine the width and height of each plane based on pixel alignment for
 * both uncompressed and AFBC allocations.
 *
 * @param width           [in]    Buffer width.
 * @param height          [in]    Buffer height.
 * @param alloc_type      [in]    Allocation type inc. whether tiled and/or multi-plane.
 * @param format          [in]    Pixel format.
 * @param has_cpu_usage   [in]    CPU usage requested (in addition to any other).
 * @param size            [out]   Total calculated buffer size including all planes.
 * @param plane_info      [out]   Array of calculated information for each plane. Includes
 *                                offset, byte stride and allocation width and height.
 */
static void calc_allocation_size(const int width,
                                 const int height,
                                 const alloc_type_t alloc_type,
                                 const format_info_t format,
                                 const bool has_cpu_usage,
                                 const bool has_hw_usage,
                                 size_t * const size,
                                 plane_info_t plane_info[MAX_PLANES])
{
	plane_info[0].offset = 0;

	*size = 0;
	for (uint8_t plane = 0; plane < format.npln; plane++)
	{
		plane_info[plane].alloc_width = width;
		plane_info[plane].alloc_height = height;
		get_pixel_w_h(&plane_info[plane].alloc_width,
		              &plane_info[plane].alloc_height,
		              format,
		              alloc_type,
		              plane,
		              has_cpu_usage);
		ALOGV("Aligned w=%d, h=%d (in pixels)",
		      plane_info[plane].alloc_width, plane_info[plane].alloc_height);

		/*
		 * Calculate byte stride (per plane).
		 */
		if (alloc_type.is_afbc())
		{
			assert((plane_info[plane].alloc_width * format.bpp_afbc[plane]) % 8 == 0);
			plane_info[plane].byte_stride = (plane_info[plane].alloc_width * format.bpp_afbc[plane]) / 8;
		}
		else
		{
			assert((plane_info[plane].alloc_width * format.bpp[plane]) % 8 == 0);
			plane_info[plane].byte_stride = (plane_info[plane].alloc_width * format.bpp[plane]) / 8;

			/*
			 * Align byte stride (uncompressed allocations only).
			 *
			 * Find the lowest-common-multiple of:
			 * 1. hw_align: Minimum byte stride alignment for HW IP (has_hw_usage == true)
			 * 2. cpu_align: Byte equivalent of 'align_w_cpu' (has_cpu_usage == true)
			 *
			 * NOTE: Pixel stride is defined as multiple of 'align_w_cpu'.
			 */
			uint16_t hw_align = 0;
			if (has_hw_usage)
			{
				hw_align = format.is_yuv ? 16 : (format.is_rgb ? 64 : 0);
			}

			uint32_t cpu_align = 0;
			assert((format.bpp[plane] * format.align_w_cpu) % 8 == 0);
			if (plane == 0 || !format.planes_contiguous)
				cpu_align = (format.bpp[plane] * format.align_w_cpu) / 8;

			uint32_t stride_align = lcm(hw_align, cpu_align);
			if (stride_align)
			{
				plane_info[plane].byte_stride = GRALLOC_ALIGN(plane_info[plane].byte_stride, stride_align);
				plane_info[plane].alloc_width = plane_info[plane].byte_stride * 8 / format.bpp[plane];
			}

			/*
			 * Update YV12 stride with both CPU & HW usage due to constraint of chroma stride.
			 * Width is anyway aligned to 16px for luma and chroma (has_cpu_usage).
			 */
			/*
			 * This function can make yv12 align to 32 pixels or higher.
			 * Some apps do not use the provided stride value when a buffer is created
			 * Those apps may assume yv12 buffers are aligned to 32 pixels.
			 * So do not call this function.
			if (format.id == MALI_GRALLOC_FORMAT_INTERNAL_YV12 && has_hw_usage && has_cpu_usage)
			{
				update_yv12_stride(plane,
				                   plane_info[0].byte_stride,
				                   stride_align,
				                   &plane_info[plane].byte_stride);
			}
			*/
			GRALLOC_UNUSED(update_yv12_stride);
		}
		ALOGV("Byte stride: %d", plane_info[plane].byte_stride);

		/*
		 * Pixel stride (CPU usage only).
		 * Not used in size calculation but exposed to client.
		 */
		if (plane == 0)
		{
			assert((plane_info[plane].byte_stride * 8) % format.bpp[plane] == 0);
			ALOGV("Pixel stride: %d", (plane_info[plane].byte_stride * 8) / format.bpp[plane]);
		}

		const uint32_t sb_num = (plane_info[plane].alloc_width * plane_info[plane].alloc_height)
		                      / AFBC_PIXELS_PER_BLOCK;

		/*
		 * Calculate body size (per plane).
		 */
		int body_size = 0;
		if (alloc_type.is_afbc())
		{
			const rect_t sb = get_afbc_sb_size(alloc_type, plane);
			const int sb_bytes = GRALLOC_ALIGN((format.bpp_afbc[plane] * sb.width * sb.height) / 8, 128);
			body_size = sb_num * sb_bytes;

			/* When AFBC planes are stored in separate buffers and this is not the last plane,
			   also align the body buffer to make the subsequent header aligned. */
			if (format.npln > 1 && plane < 2)
			{
				afbc_buffer_align(alloc_type.is_tiled, &body_size);
			}

			if (alloc_type.is_frontbuffer_safe)
			{
				int back_buffer_size = body_size;
				afbc_buffer_align(alloc_type.is_tiled, &back_buffer_size);
				body_size += back_buffer_size;
			}
		}
		else
		{
			body_size = (plane_info[plane].byte_stride) * plane_info[plane].alloc_height;
		}
		ALOGV("Body size: %d", body_size);


		/*
		 * Calculate header size (per plane).
		 */
		int header_size = 0;
		if (alloc_type.is_afbc())
		{
			/* As this is AFBC, calculate header size for this plane.
			 * Always align the header, which will make the body buffer aligned.
			 */
			header_size = sb_num * AFBC_HEADER_BUFFER_BYTES_PER_BLOCKENTRY;
			afbc_buffer_align(alloc_type.is_tiled, &header_size);
		}
		ALOGV("AFBC Header size: %d", header_size);

		/*
		 * Set offset for separate chroma planes.
		 */
		if (plane > 0)
		{
			plane_info[plane].offset = *size;
		}

		/*
		 * Set overall size.
		 * Size must be updated after offset.
		 */
		*size += body_size + header_size;
		ALOGV("size=%zu",*size);
	}
}



/*
 * Validate selected format against requested.
 * Return true if valid, false otherwise.
 */
static bool validate_format(const format_info_t * const format,
                            const alloc_type_t alloc_type,
                            const buffer_descriptor_t * const bufDescriptor)
{
	if (alloc_type.is_afbc())
	{
		/*
		 * Validate format is supported by AFBC specification and gralloc.
		 */
		if (format->afbc == false)
		{
			ALOGE("ERROR: AFBC selected but not supported for base format: (%s 0x%" PRIx32")",
                              format_name(format->id), format->id);
			return false;
		}

		/*
		 * Enforce consistency between number of format planes and
		 * request for single/multi-plane AFBC.
		 */
		if (((format->npln == 1 && alloc_type.is_multi_plane) ||
		    (format->npln > 1 && !alloc_type.is_multi_plane)))
		{
			ALOGE("ERROR: Format ((%s %" PRIx32 "), num planes: %u) is incompatible with %s-plane AFBC request",
			      format_name(format->id), format->id, format->npln, (alloc_type.is_multi_plane) ? "multi" : "single");
			return false;
		}
	}
	else
	{
		if (format->linear == false)
		{
			ALOGE("ERROR: Uncompressed format requested but not supported for base format: (%s %" PRIx32 ")",
                              format_name(format->id), format->id);
			return false;
		}
	}

	if (format->id == MALI_GRALLOC_FORMAT_INTERNAL_BLOB &&
	    bufDescriptor->height != 1)
	{
		ALOGE("ERROR: Height for format BLOB must be 1.");
		return false;
	}

	return true;
}

static int color_space_for_dimensions(int width, int height) {
	auto [sm, lg] = std::minmax(width, height);
	int64_t area = static_cast<int64_t>(sm) * static_cast<int64_t>(lg);

	if ((lg >= 3840) || (sm >= 3840) || (area >= (3840ll * 1634ll)))
	{
		return HAL_DATASPACE_STANDARD_BT2020;
	}

	if (lg <= 720)
	{
		if (sm <= 480)
		{
			return HAL_DATASPACE_STANDARD_BT601_525;
		}

		if (sm <= 576)
		{
			return HAL_DATASPACE_STANDARD_BT601_625;
		}
	}

	return HAL_DATASPACE_STANDARD_BT709;
}

static int set_dataspace(private_handle_t * const hnd, uint64_t usage, int32_t format_idx)
{
	int color_space = HAL_DATASPACE_STANDARD_UNSPECIFIED;
	int range = HAL_DATASPACE_RANGE_UNSPECIFIED;
	int data_space = 0;
	hnd->yuv_info = MALI_YUV_NO_INFO;
	int rval = -1;
	int width = hnd->width;
	int height = hnd->height;

	static int csc_supported = -1;

	if (csc_supported == -1)
	{
		csc_supported = property_get_int32("ro.vendor.gpu.dataspace", 0);
#ifdef GRALLOC_NO_CSC_SUPPORTED
		csc_supported = 0;
#endif
	}

	if (gralloc_buffer_attr_map(hnd, true) < 0)
	{
		ALOGE("Failed to map attribute region.");
		goto out;
	}

	if (!csc_supported)
	{
		if (formats[format_idx].is_yuv)
		{
			color_space = HAL_DATASPACE_STANDARD_BT601_625;
			range = HAL_DATASPACE_RANGE_LIMITED;
			hnd->yuv_info = MALI_YUV_BT601_NARROW;

			/* Special cases for Camera producers */
			switch (hnd->format)
			{
				case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M:
				case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL:
					if (usage & GRALLOC_USAGE_HW_CAMERA_WRITE)
					{
						range = HAL_DATASPACE_RANGE_FULL;
						hnd->yuv_info = MALI_YUV_BT601_WIDE;
					}
			}

			data_space = color_space | range;
		}
		else
		{
			data_space = HAL_DATASPACE_STANDARD_BT709 | HAL_DATASPACE_RANGE_FULL;
		}

		gralloc_buffer_attr_write(hnd, GRALLOC_ARM_BUFFER_ATTR_FORCE_DATASPACE, &data_space);
	}
	else if (formats[format_idx].is_yuv)
	{
		/* Default YUV dataspace. */
		color_space = HAL_DATASPACE_STANDARD_BT709;
		range = HAL_DATASPACE_RANGE_LIMITED;

		/* SDR 10-bit YUV is assumed to be narrow BT709.
		 * Dataspace for HDR content will be set (by producer) according to the HDR metadata.
		 */
		if (formats[format_idx].bps >= 10)
		{
			color_space = HAL_DATASPACE_STANDARD_BT2020;
		}
		else
		{
			color_space = color_space_for_dimensions(width, height);
		}

#if GRALLOC_VERSION_MAJOR >= 1
		/* Override YUV dataspace based on private usage. */
		switch (usage & MALI_GRALLOC_USAGE_YUV_COLOR_SPACE_MASK)
		{
		case MALI_GRALLOC_USAGE_YUV_COLOR_SPACE_BT601:
			color_space = HAL_DATASPACE_STANDARD_BT601_625;
			break;
		case MALI_GRALLOC_USAGE_YUV_COLOR_SPACE_BT709:
			color_space = HAL_DATASPACE_STANDARD_BT709;
			break;
		case MALI_GRALLOC_USAGE_YUV_COLOR_SPACE_BT2020:
			color_space = HAL_DATASPACE_STANDARD_BT2020;
			break;
		}

		switch (usage & MALI_GRALLOC_USAGE_RANGE_MASK)
		{
		case MALI_GRALLOC_USAGE_RANGE_NARROW:
			range = HAL_DATASPACE_RANGE_LIMITED;
			break;
		case MALI_GRALLOC_USAGE_RANGE_WIDE:
			range = HAL_DATASPACE_RANGE_FULL;
			break;
		}

		/* Special cases for Camera producers */
		switch (hnd->format)
		{
			case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M:
			case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL:
				if (usage & GRALLOC_USAGE_HW_CAMERA_WRITE)
				{
					color_space = HAL_DATASPACE_STANDARD_BT601_625;
					range = HAL_DATASPACE_RANGE_FULL;
				}
		}

		data_space = color_space | range;

		/* Set deprecated yuv_info field. */
		switch (color_space)
		{
		case HAL_DATASPACE_STANDARD_BT601_625:
			if (range == HAL_DATASPACE_RANGE_LIMITED)
			{
				hnd->yuv_info = MALI_YUV_BT601_NARROW;
			}
			else
			{
				hnd->yuv_info = MALI_YUV_BT601_WIDE;
			}
			break;
		case HAL_DATASPACE_STANDARD_BT709:
			if (range == HAL_DATASPACE_RANGE_LIMITED)
			{
				hnd->yuv_info = MALI_YUV_BT709_NARROW;
			}
			else
			{
				hnd->yuv_info = MALI_YUV_BT709_WIDE;
			}
			break;
		}
#endif
	}
	else if (formats[format_idx].is_rgb)
	{
		/* Default RGB dataspace. */
		data_space = HAL_DATASPACE_STANDARD_BT709 | HAL_DATASPACE_RANGE_FULL;
	}

	gralloc_buffer_attr_write(hnd, GRALLOC_ARM_BUFFER_ATTR_DATASPACE, &data_space);

	gralloc_buffer_attr_unmap(hnd);

	rval = 0;
out:
	return rval;
}

/*
 * Compute SBWC buffer geometry for a buffer containing packed SBWC YUV data
 * with bits per pixel bpp, width w, and height h.
 * Returns a pair of { luma size, chroma size }.
 */
template <int bpp>
static std::pair<size_t, size_t> sbwc_sizes(int w, int h) {
	static_assert(bpp == 8 || bpp == 10, "Unexpected bit width");

	const size_t luma_body_size = (bpp == 8) ?
		SBWC_8B_Y_SIZE(w, h) : SBWC_10B_Y_SIZE(w, h);
	const size_t luma_header_size = (bpp == 8) ?
		SBWC_8B_Y_HEADER_SIZE(w, h) : SBWC_10B_Y_HEADER_SIZE(w, h);

	const size_t chroma_body_size = (bpp == 8) ?
		SBWC_8B_CBCR_SIZE(w, h) : SBWC_10B_CBCR_SIZE(w, h);
	const size_t chroma_header_size = (bpp == 8) ?
		SBWC_8B_CBCR_HEADER_SIZE(w, h) : SBWC_10B_CBCR_HEADER_SIZE(w, h);

	ALOGV("SBWC luma body size 0x%zx, header size 0x%zx", luma_body_size, luma_header_size);
	ALOGV("SBWC chroma body size 0x%zx, header size 0x%zx", chroma_body_size, chroma_header_size);

	return { luma_body_size + luma_header_size,
	         chroma_body_size + chroma_header_size };
}

static int prepare_descriptor_exynos_formats(buffer_descriptor_t *bufDescriptor)
{
	size_t luma_size=0, chroma_size=0, ext_size=256;
	int fd_count = 1;
	int stride = 0, byte_stride = 0;
	size_t luma_vstride = 0;
	// Keep around original requested format for later validation
	int w = bufDescriptor->width;
	int h = bufDescriptor->height;
	uint64_t usage = bufDescriptor->producer_usage | bufDescriptor->consumer_usage;
	int is_multiplane = 1;

	// Set default stride
	stride = GRALLOC_ALIGN(w, 16);
	luma_vstride = GRALLOC_ALIGN(h, 16);
	byte_stride = stride;

	if (usage & (GRALLOC1_CONSUMER_USAGE_VIDEO_ENCODER | GRALLOC1_PRODUCER_USAGE_VIDEO_DECODER))
	{
		usage |= GRALLOC1_CONSUMER_USAGE_VIDEO_PRIVATE_DATA;
		bufDescriptor->producer_usage |= GRALLOC1_CONSUMER_USAGE_VIDEO_PRIVATE_DATA;
		bufDescriptor->consumer_usage |= GRALLOC1_CONSUMER_USAGE_VIDEO_PRIVATE_DATA;
	}

	/* SWBC Formats have special size requirements */
	switch ((uint32_t)bufDescriptor->internal_format)
	{
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC:
			{
				std::tie(luma_size, chroma_size) = sbwc_sizes<8>(w, h);
				byte_stride = SBWC_8B_STRIDE(w);
				stride = GRALLOC_ALIGN(w, 32);
				luma_vstride = __ALIGN_UP(h, 8);

				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC:
			{
				std::tie(luma_size, chroma_size) = sbwc_sizes<10>(w, h);
				byte_stride = SBWC_10B_STRIDE(w);
				stride = GRALLOC_ALIGN(w, 32);
				luma_vstride = __ALIGN_UP(h, 8);

				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75:
			{
				uint32_t r = 100;

				switch ((uint32_t)bufDescriptor->internal_format)
				{
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50:
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50:
						r = 50;
						break;
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75:
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75:
						r = 75;
						break;
				}

				luma_size = SBWCL_8B_Y_SIZE(w, h, r);
				chroma_size = SBWCL_8B_CBCR_SIZE(w, h, r);

				/* How to set this information for lossy formats? */
				byte_stride = SBWCL_8B_STRIDE(w, r);

				stride = GRALLOC_ALIGN(w, 32);
				luma_vstride = __ALIGN_UP(h, 8);
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80:
			{
				uint32_t r = 100;

				switch ((uint32_t)bufDescriptor->internal_format)
				{
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40:
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40:
						r = 40;
						break;
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60:
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60:
						r = 60;
						break;
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80:
					case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80:
						r = 80;
						break;
				}

				luma_size = SBWCL_10B_Y_SIZE(w, h, r);
				chroma_size = SBWCL_10B_CBCR_SIZE(w, h, r);

				byte_stride = SBWCL_10B_STRIDE(w, r);

				stride = GRALLOC_ALIGN(w, 32);
				luma_vstride = __ALIGN_UP(h, 8);

				break;
			}
	}

	// Decide plane count and sizes
	switch ((uint32_t)bufDescriptor->internal_format)
	{
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC:
			{
				fd_count = 2;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC:
			{
				luma_size += chroma_size;
				chroma_size = 0;

				fd_count = 1;
				break;
			}
		case HAL_PIXEL_FORMAT_YCrCb_420_SP:
			{
				stride = w;
				byte_stride = stride;
				luma_size = PLANE_SIZE(stride , h * 3 / 2, ext_size);
				luma_vstride = h;
				fd_count = 1;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YV12_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M:
			{
				stride = GRALLOC_ALIGN(w, 32);
				byte_stride = stride;
				luma_size = PLANE_SIZE(stride, luma_vstride, ext_size);
#ifdef EXYNOS_CHROMA_VSTRIDE_ALIGN
				chroma_size = PLANE_SIZE(GRALLOC_ALIGN(stride / 2, 16), GRALLOC_ALIGN(luma_vstride / 2, CHROMA_VALIGN), ext_size);
#else
				chroma_size = PLANE_SIZE(GRALLOC_ALIGN(stride / 2, 16), (luma_vstride / 2), ext_size);
#endif
				fd_count = 3;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_TILED:
			{
				size_t chroma_vstride = GRALLOC_ALIGN(h / 2, 32);
				luma_vstride = GRALLOC_ALIGN(h, 32);
				luma_size = PLANE_SIZE(stride, luma_vstride, ext_size);
				chroma_size = PLANE_SIZE(stride, chroma_vstride, ext_size);
				byte_stride = stride * 16;
				fd_count = 2;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P:
			{
				/* Same as HAL_PIXEL_FORMAT_YV12 */
				chroma_size = PLANE_SIZE(GRALLOC_ALIGN(stride / 2, 16), h, ext_size);
				luma_size = PLANE_SIZE(stride, h, 0) + chroma_size;
				is_multiplane = 0;
				fd_count = 1;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M:
			{
				if (usage & GRALLOC1_CONSUMER_USAGE_VIDEO_PRIVATE_DATA)
				{
					luma_vstride = GRALLOC_ALIGN(h, 32);
				}

				luma_size = PLANE_SIZE(stride, luma_vstride, ext_size);
#ifdef EXYNOS_CHROMA_VSTRIDE_ALIGN
				chroma_size = PLANE_SIZE(stride, GRALLOC_ALIGN(luma_vstride / 2, CHROMA_VALIGN), ext_size);
#else
				chroma_size = PLANE_SIZE(stride, luma_vstride / 2, ext_size);
#endif
				fd_count = 2;

				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN:
			{
#ifdef EXYNOS_CHROMA_VSTRIDE_ALIGN
				chroma_size = NV12N_S8B_CHROMA_SIZE(stride, GRALLOC_ALIGN(luma_vstride / 2, CHROMA_VALIGN), ext_size);
#else
				chroma_size = NV12N_S8B_CHROMA_SIZE(stride, luma_vstride / 2, ext_size);
#endif
				luma_size = NV12N_S8B_LUMA_SIZE(stride, luma_vstride, ext_size) + chroma_size;

				fd_count = 1;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B:
			{
				stride = GRALLOC_ALIGN(w, BOARD_EXYNOS_S10B_FORMAT_ALIGN);
				byte_stride = stride;
				luma_size = NV12M_S8B_SIZE(stride, luma_vstride, ext_size) + NV12M_S2B_LUMA_SIZE(w, h, ext_size);
				chroma_size = NV12M_S8B_SIZE(stride, luma_vstride / 2, ext_size) + NV12M_S2B_CHROMA_SIZE(w, h, ext_size);
				fd_count = 2;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B:
			{
				stride = GRALLOC_ALIGN(w, BOARD_EXYNOS_S10B_FORMAT_ALIGN);
				byte_stride = stride;
				chroma_size = NV12N_S8B_CHROMA_SIZE(stride, luma_vstride / 2, ext_size) + NV12N_S2B_SIZE(w, luma_vstride / 2);
				luma_size = NV12N_S8B_LUMA_SIZE(stride, luma_vstride, ext_size) + NV12N_S2B_SIZE(w, luma_vstride) + chroma_size;
				fd_count = 1;
				break;
			}
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M:
			{
				chroma_size = P010_PLANE_SIZE(stride, luma_vstride / 2, ext_size);
				luma_size = P010_PLANE_SIZE(stride, luma_vstride, ext_size);
				byte_stride = stride * 2;
				fd_count = 2;
				break;
			}
		default:
			AERR("invalid yuv format %x\n", (uint32_t)bufDescriptor->internal_format);
			return -1;
	}

	switch ((uint32_t)bufDescriptor->internal_format)
	{
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B:
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B:
			if (usage & GRALLOC1_CONSUMER_USAGE_VIDEO_PRIVATE_DATA)
				bufDescriptor->alloc_video_private_data = 1;

			break;
		case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M:
			bufDescriptor->alloc_video_private_data = 1;
	}

	// Add MSCL_EXT_SIZE
	if (w % MSCL_ALIGN)
	{
		luma_size += MSCL_EXT_SIZE;
		chroma_size += MSCL_EXT_SIZE/2;
	}

	for (int i = 0; i < fd_count; i++)
	{
		size_t alloc_height = i == 0 ? luma_vstride : (luma_vstride / 2);
		size_t size = i == 0 ? luma_size : chroma_size;

#ifdef GRALLOC_MSCL_ALLOC_RESTRICTION
		size = size < SIZE_4K ? SIZE_4K : size;
#endif
		bufDescriptor->sizes[i] = size;

		bufDescriptor->plane_info[i].alloc_width = stride;
		bufDescriptor->plane_info[i].alloc_height = alloc_height;
		bufDescriptor->plane_info[i].byte_stride = byte_stride;
		bufDescriptor->plane_info[i].offset = 0;
	}

	bufDescriptor->fd_count = fd_count;

	return 0;
}


int mali_gralloc_derive_format_and_size(mali_gralloc_module *m,
                                        buffer_descriptor_t * const bufDescriptor)
{
	GRALLOC_UNUSED(m);
	alloc_type_t alloc_type;
	static bool warn_about_mutual_exclusive = true;

	int alloc_width = bufDescriptor->width;
	int alloc_height = bufDescriptor->height;
	uint64_t usage = bufDescriptor->producer_usage | bufDescriptor->consumer_usage;

	/*
	* Select optimal internal pixel format based upon
	* usage and requested format.
	*/
	bufDescriptor->alloc_format = mali_gralloc_select_format(bufDescriptor->hal_format,
	                                                         bufDescriptor->format_type,
	                                                         usage,
	                                                         bufDescriptor->width * bufDescriptor->height,
	                                                         &bufDescriptor->internal_format);
	if (bufDescriptor->alloc_format == MALI_GRALLOC_FORMAT_INTERNAL_UNDEFINED)
	{
		ALOGE("ERROR: Unrecognized and/or unsupported format 0x%" PRIx64 " and usage 0x%" PRIx64,
		       bufDescriptor->hal_format, usage);
		return -EINVAL;
	}
	else if (warn_about_mutual_exclusive &&
	        (bufDescriptor->alloc_format & 0x0000000100000000ULL) &&
	        (bufDescriptor->alloc_format & 0x0000000e00000000ULL))
	{
		/*
		 * Modifier bits are no longer mutually exclusive. Warn when
		 * any bits are set in addition to AFBC basic since these might
		 * have been handled differently by clients under the old scheme.
		 * AFBC basic is guaranteed to be signalled when any other AFBC
		 * flags are set.
		 * This flag is to avoid the mutually exclusive modifier bits warning
		 * being continuously emitted. (see comment below for explanation of warning).
		 */
		warn_about_mutual_exclusive = false;
		ALOGW("WARNING: internal format modifier bits not mutually exclusive. "
		      "AFBC basic bit is always set, so extended AFBC support bits must always be checked.");
	}

	int32_t format_idx = get_format_index(bufDescriptor->alloc_format & MALI_GRALLOC_INTFMT_FMT_MASK);
	if (format_idx == -1)
	{
		return -EINVAL;
	}
	ALOGV("alloc_format: (%s 0x%" PRIx64 ") format_idx: %d",
              format_name(bufDescriptor->alloc_format), bufDescriptor->alloc_format, format_idx);

	/*
	 * Obtain allocation type (uncompressed, AFBC basic, etc...)
	 */
	if (!get_alloc_type(bufDescriptor->alloc_format & MALI_GRALLOC_INTFMT_EXT_MASK,
	    format_idx, usage, &alloc_type))
	{
		return -EINVAL;
	}

	if (!validate_format(&formats[format_idx], alloc_type, bufDescriptor))
	{
		return -EINVAL;
	}

	if (is_exynos_format((uint32_t)bufDescriptor->alloc_format))
	{
		prepare_descriptor_exynos_formats(bufDescriptor);
	}
	else
	{
		/*
		 * Resolution of frame (allocation width and height) might require adjustment.
		 * This adjustment is only based upon specific usage and pixel format.
		 * If using AFBC, further adjustments to the allocation width and height will be made later
		 * based on AFBC alignment requirements and, for YUV, the plane properties.
		 */
		mali_gralloc_adjust_dimensions(bufDescriptor->alloc_format,
		                               usage,
		                               &alloc_width,
		                               &alloc_height);

		/* Obtain buffer size and plane information. */
		calc_allocation_size(alloc_width,
		                     alloc_height,
		                     alloc_type,
		                     formats[format_idx],
		                     usage & (GRALLOC_USAGE_SW_READ_MASK | GRALLOC_USAGE_SW_WRITE_MASK),
		                     usage & ~(GRALLOC_USAGE_PRIVATE_MASK | GRALLOC_USAGE_SW_READ_MASK | GRALLOC_USAGE_SW_WRITE_MASK),
		                     &bufDescriptor->size,
		                     bufDescriptor->plane_info);

	}

	bufDescriptor->plane_count = formats[format_idx].npln;

	switch ((uint32_t)bufDescriptor->alloc_format)
	{
		case MALI_GRALLOC_FORMAT_INTERNAL_RAW12:
		case MALI_GRALLOC_FORMAT_INTERNAL_RAW10:
			// RAW10 image width needs to be multiple of 16 so that the data can be CPU accessed
			// in 32bit unit
			if (bufDescriptor->width % 16 != 0) {
				ALOGE("ERROR: Width for HAL_PIXEL_FORMAT_RAW10 buffers has to be multiple of 16.");
				return -EINVAL;
			}
			// TODO: revert this back when b/152045385 is fixed.
			bufDescriptor->pixel_stride = bufDescriptor->width * 5 / 4;
			break;
		default:
			bufDescriptor->pixel_stride = bufDescriptor->plane_info[0].alloc_width;
	}

	/*
	 * Each layer of a multi-layer buffer must be aligned so that
	 * it is accessible by both producer and consumer. In most cases,
	 * the stride alignment is also sufficient for each layer, however
	 * for AFBC the header buffer alignment is more constrained (see
	 * AFBC specification v3.4, section 2.15: "Alignment requirements").
	 * Also update the buffer size to accommodate all layers.
	 */
	if (bufDescriptor->layer_count > 1)
	{
		if (bufDescriptor->alloc_format & MALI_GRALLOC_INTFMT_AFBCENABLE_MASK)
		{
			if (bufDescriptor->alloc_format & MALI_GRALLOC_INTFMT_AFBC_TILED_HEADERS)
			{
				bufDescriptor->size = GRALLOC_ALIGN(bufDescriptor->size, 4096);
			}
			else
			{
				bufDescriptor->size = GRALLOC_ALIGN(bufDescriptor->size, 128);
			}
		}

		bufDescriptor->size *= bufDescriptor->layer_count;
	}

	/* MFC requires EXT_SIZE padding */
	bufDescriptor->size += EXT_SIZE;

#ifdef GRALLOC_MSCL_ALIGN_RESTRICTION
	if (bufDescriptor->width % MSCL_ALIGN)
	{
		bufDescriptor->size += MSCL_EXT_SIZE;
	}
#endif

	ALOGV("size after padding = %zu  %zu  %zu", bufDescriptor->sizes[0], bufDescriptor->sizes[1], bufDescriptor->sizes[2]);

	return 0;
}


int mali_gralloc_buffer_allocate(mali_gralloc_module *m, const gralloc_buffer_descriptor_t *descriptors,
                                 uint32_t numDescriptors, buffer_handle_t *pHandle, bool *shared_backend)
{
	bool shared = false;
	uint64_t backing_store_id = 0x0;
	int err;

	for (uint32_t i = 0; i < numDescriptors; i++)
	{
		buffer_descriptor_t * const bufDescriptor = (buffer_descriptor_t *)(descriptors[i]);

		err = mali_gralloc_derive_format_and_size(m, bufDescriptor);
		if (err != 0)
		{
			return err;
		}
	}

	/* Allocate ION backing store memory */
	err = mali_gralloc_ion_allocate(descriptors, numDescriptors, pHandle, &shared);
	if (err < 0)
	{
		return err;
	}

	if (shared)
	{
		backing_store_id = getUniqueId();
	}

	for (uint32_t i = 0; i < numDescriptors; i++)
	{
		buffer_descriptor_t * const bufDescriptor = (buffer_descriptor_t *)descriptors[i];
		private_handle_t *hnd = (private_handle_t *)pHandle[i];
		uint64_t usage = bufDescriptor->consumer_usage | bufDescriptor->producer_usage;

		err = gralloc_buffer_attr_allocate(hnd);

		if (err < 0)
		{
			/* free all allocated ion buffer& attr buffer here.*/
			for (int idx = 0; idx < numDescriptors; idx++)
			{
				mali_gralloc_buffer_free(pHandle[idx]);
			}
			return err;
		}

		mali_gralloc_dump_buffer_add(hnd);

		const int32_t format_idx = get_format_index(bufDescriptor->alloc_format & MALI_GRALLOC_INTFMT_FMT_MASK);
		if (format_idx == -1)
		{
			return -EINVAL;
		}
		const int ret = set_dataspace(hnd, usage, format_idx);
		if (ret < 0)
		{
			return ret;
		}

#if GRALLOC_ARM_NO_EXTERNAL_AFBC == 0
		int afbc_prop = property_get_int32("ro.vendor.ddk.set.afbc", 0);

		if (afbc_prop == 1 && hnd->alloc_format & MALI_GRALLOC_INTFMT_AFBC_BASIC)
		{
			hnd->is_compressible = 1;

			int *mapAddr = (int*)mmap(0, sizeof(int), PROT_READ|PROT_WRITE, MAP_SHARED, hnd->fds[1], 0);
			if(mapAddr == NULL || mapAddr == MAP_FAILED)
			{
				int errnum = errno;
				ALOGE("Unable to mmap fd %d errno: %s", hnd->fds[1], strerror(errnum));
				hnd->is_compressible = 0;
				hnd->alloc_format    = ((uint64_t)hnd->alloc_format    & ~MALI_GRALLOC_INTFMT_AFBCENABLE_MASK);
				hnd->internal_format = ((uint64_t)hnd->internal_format & ~MALI_GRALLOC_INTFMT_AFBCENABLE_MASK);
			}
			else
			{
#define AFBC_INFO_SIZE                              (sizeof(int))
#define AFBC_ENABLE                                 (0xafbc)
				int afbc_flag = AFBC_ENABLE;
				memcpy(mapAddr, &afbc_flag, AFBC_INFO_SIZE);
				munmap(mapAddr, sizeof(int));
			}
		}
		else
		{
				hnd->is_compressible = 0;
				hnd->alloc_format    = ((uint64_t)hnd->alloc_format    & ~MALI_GRALLOC_INTFMT_AFBCENABLE_MASK);
				hnd->internal_format = ((uint64_t)hnd->internal_format & ~MALI_GRALLOC_INTFMT_AFBCENABLE_MASK);
		}
#endif

		if (shared)
		{
			/*each buffer will share the same backing store id.*/
			hnd->backing_store_id = backing_store_id;
		}
		else
		{
			/* each buffer will have an unique backing store id.*/
			hnd->backing_store_id = getUniqueId();
		}
	}

	if (NULL != shared_backend)
	{
		*shared_backend = shared;
	}

	return 0;
}

int mali_gralloc_buffer_free(buffer_handle_t pHandle)
{
	int rval = -1;
	private_handle_t * const hnd = (private_handle_t * const)(pHandle);

	if (hnd != NULL)
	{
		rval = gralloc_buffer_attr_free(hnd);
		mali_gralloc_ion_free(hnd);
	}

	return rval;
}
