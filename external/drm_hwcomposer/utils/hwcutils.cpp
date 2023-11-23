/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_GRAPHICS
#define LOG_TAG "hwc-drm-utils"

#include <log/log.h>
#include <ui/Gralloc.h>
#include <ui/GraphicBufferMapper.h>

#include "bufferinfo/BufferInfoGetter.h"
#include "drm/DrmFbImporter.h"
#include "drmhwcomposer.h"

#define UNUSED(x) (void)(x)

namespace android {

int DrmHwcLayer::ImportBuffer(DrmDevice *drmDevice) {
  buffer_info = hwc_drm_bo_t{};

  int ret = BufferInfoGetter::GetInstance()->ConvertBoInfo(sf_handle,
                                                           &buffer_info);
  if (ret) {
    ALOGE("Failed to convert buffer info %d", ret);
    return ret;
  }

  FbIdHandle = drmDevice->GetDrmFbImporter().GetOrCreateFbId(&buffer_info);
  if (!FbIdHandle) {
    ALOGE("Failed to import buffer");
    return -EINVAL;
  }

  return 0;
}

void DrmHwcLayer::SetTransform(int32_t sf_transform) {
  transform = 0;
  // 270* and 180* cannot be combined with flips. More specifically, they
  // already contain both horizontal and vertical flips, so those fields are
  // redundant in this case. 90* rotation can be combined with either horizontal
  // flip or vertical flip, so treat it differently
  if (sf_transform == HWC_TRANSFORM_ROT_270) {
    transform = DrmHwcTransform::kRotate270;
  } else if (sf_transform == HWC_TRANSFORM_ROT_180) {
    transform = DrmHwcTransform::kRotate180;
  } else {
    if (sf_transform & HWC_TRANSFORM_FLIP_H)
      transform |= DrmHwcTransform::kFlipH;
    if (sf_transform & HWC_TRANSFORM_FLIP_V)
      transform |= DrmHwcTransform::kFlipV;
    if (sf_transform & HWC_TRANSFORM_ROT_90)
      transform |= DrmHwcTransform::kRotate90;
  }
}
}  // namespace android
