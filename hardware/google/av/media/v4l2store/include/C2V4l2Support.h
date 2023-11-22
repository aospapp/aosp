// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef ANDROID_CODEC2_V4L2_SUPPORT_H
#define ANDROID_CODEC2_V4L2_SUPPORT_H

#include <C2Component.h>

#include <memory>

namespace android {

/**
 * Returns the C2VDA component store.
 * \retval nullptr if the platform component store could not be obtained
 */
std::shared_ptr<C2ComponentStore> GetCodec2VDAComponentStore();
}  // namespace android

#endif  // ANDROID_CODEC2_V4L2_SUPPORT_H
