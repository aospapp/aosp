// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef ANDROID_V4L2_CODEC2_COMMON_COMMON_H
#define ANDROID_V4L2_CODEC2_COMMON_COMMON_H

#include <inttypes.h>

namespace android {

// The offset and stride of a video frame plane.
struct VideoFramePlane {
    uint32_t mOffset;
    uint32_t mStride;
};

enum class HalPixelFormat : uint32_t {
    UNKNOWN = 0x0,
    // The pixel formats defined in Android but are used among C2VDAComponent.
    YCbCr_420_888 = 0x23,
    YV12 = 0x32315659,
    NV12 = 0x3231564e,
};

enum class InputCodec {
    H264,
    VP8,
    VP9,
};

} // namespace android

#endif  // ANDROID_V4L2_CODEC2_COMMON_COMMON_H
