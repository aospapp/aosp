/*
 * Copyright (C) 2016-2018 ARM Limited. All rights reserved.
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

#ifndef MALI_GRALLOC_BUFFERDESCRIPTOR_H_
#define MALI_GRALLOC_BUFFERDESCRIPTOR_H_

#include <hardware/hardware.h>
#include "gralloc_priv.h"
#include "mali_gralloc_module.h"
#include "mali_gralloc_formats.h"

#if GRALLOC_VERSION_MAJOR == 1
#include <hardware/gralloc1.h>
#elif GRALLOC_VERSION_MAJOR == 0
#include <hardware/gralloc.h>
#endif

typedef uint64_t gralloc_buffer_descriptor_t;

typedef struct buffer_descriptor
{
	uint32_t signature;

	uint32_t width;
	uint32_t height;
	uint64_t producer_usage;
	uint64_t consumer_usage;
	uint64_t hal_format;
	uint32_t layer_count;

	mali_gralloc_format_type format_type;
	union
	{
		size_t size;
		size_t sizes[3];
		uint64_t padding[3];
	};
	int pixel_stride;
	uint64_t internal_format;
	uint64_t alloc_format;
	int fd_count;
	int alloc_video_private_data;
	plane_info_t plane_info[MAX_PLANES];
	int plane_count;

#ifdef __cplusplus
	buffer_descriptor() :
	    signature(0),
	    width(0),
	    height(0),
	    producer_usage(0),
	    consumer_usage(0),
	    hal_format(0),
	    layer_count(0),
	    format_type(MALI_GRALLOC_FORMAT_TYPE_USAGE),
		pixel_stride(0),
	    internal_format(0),
	    alloc_format(0),
		fd_count(1),
		alloc_video_private_data(0),
		plane_count(0)

	{
		sizes[0] = sizes[1] = sizes[2] = 0;
		memset(plane_info, 0, sizeof(plane_info_t) * MAX_PLANES);
	}
#endif

	void dump() const
	{
		ALOGI("buffer_descriptor: "
				"wh(%u %u) "
				"usage_pc(0x%" PRIx64 " 0x%" PRIx64 ") "
				"hal_format(0x%" PRIx64 ") "
				"layer_count(%u) sizes(%zu %zu %zu) "
				"strde(%d) byte_stride(%d) alloc_wh(%d %d) "
				"internal_format(0x%" PRIx64 ") alloc_format(0x%" PRIx64 ") "
				"fd_count(%d) plane_count(%d)"
				"\n",
				width, height, producer_usage, consumer_usage, hal_format,
				layer_count, sizes[0], sizes[1], sizes[2],
				pixel_stride, plane_info[0].byte_stride, plane_info[0].alloc_width, plane_info[0].alloc_height,
				internal_format, alloc_format, fd_count, plane_count
			);
	}
} buffer_descriptor_t;

#if GRALLOC_VERSION_MAJOR == 1
int mali_gralloc_create_descriptor_internal(gralloc1_buffer_descriptor_t *outDescriptor);
int mali_gralloc_destroy_descriptor_internal(gralloc1_buffer_descriptor_t descriptor);
int mali_gralloc_set_dimensions_internal(gralloc1_buffer_descriptor_t descriptor, uint32_t width, uint32_t height);
int mali_gralloc_set_format_internal(gralloc1_buffer_descriptor_t descriptor, int32_t format);
int mali_gralloc_set_producerusage_internal(gralloc1_buffer_descriptor_t descriptor, uint64_t usage);
int mali_gralloc_set_consumerusage_internal(gralloc1_buffer_descriptor_t descriptor, uint64_t usage);

int mali_gralloc_get_backing_store_internal(buffer_handle_t buffer, gralloc1_backing_store_t *outStore);
int mali_gralloc_get_consumer_usage_internal(buffer_handle_t buffer, uint64_t *outUsage);
int mali_gralloc_get_dimensions_internal(buffer_handle_t buffer, uint32_t *outWidth, uint32_t *outHeight);
int mali_gralloc_get_format_internal(buffer_handle_t buffer, int32_t *outFormat);
int mali_gralloc_get_producer_usage_internal(buffer_handle_t buffer, uint64_t *outUsage);
#if PLATFORM_SDK_VERSION >= 26
int mali_gralloc_set_layer_count_internal(gralloc1_buffer_descriptor_t descriptor, uint32_t layerCount);
int mali_gralloc_get_layer_count_internal(buffer_handle_t buffer, uint32_t *outLayerCount);
#endif
#endif
int mali_gralloc_query_getstride(buffer_handle_t handle, int *pixelStride);

#endif /* MALI_GRALLOC_BUFFERDESCRIPTOR_H_ */
