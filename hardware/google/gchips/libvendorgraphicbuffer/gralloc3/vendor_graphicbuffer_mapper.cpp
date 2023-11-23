/*
 * Copyright (C) 2020 Samsung Electronics Co. Ltd.
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

#include <ui/Gralloc.h>
#include "VendorGraphicBuffer.h"

using namespace android;
using namespace vendor::graphics;

status_t VendorGraphicBufferMapper::lock64(buffer_handle_t handle, uint64_t usage, const Rect& bounds,
	void** vaddr, int32_t* outBytesPerPixel, int32_t* outBytesPerStride)
{
	return lockAsync(handle, usage, usage, bounds, vaddr,
			-1, outBytesPerPixel, outBytesPerStride);
}

status_t VendorGraphicBufferMapper::lockYCbCr64(buffer_handle_t handle,
	uint64_t usage, const Rect& bounds, android_ycbcr *ycbcr)
{
	return getGrallocMapper().lock(handle, usage, bounds, -1, ycbcr);
}
