/*
 * Copyright (C) 2021 The Android Open Source Project
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

#pragma once

#include <aidl/com/google/hardware/pixel/display/BnDisplay.h>

#include "ExynosDevice.h"

namespace aidl {
namespace com {
namespace google {
namespace hardware {
namespace pixel {
namespace display {

using aidl::android::hardware::common::NativeHandle;

// Default implementation
class Display : public BnDisplay {
public:
    Display(ExynosDevice *device) : mDevice(device){};

    ndk::ScopedAStatus isHbmSupported(bool *_aidl_return) override;
    ndk::ScopedAStatus setHbmState(HbmState state) override;
    ndk::ScopedAStatus getHbmState(HbmState *_aidl_return) override;
    ndk::ScopedAStatus isLbeSupported(bool *_aidl_return) override;
    ndk::ScopedAStatus setLbeState(LbeState state) override;
    ndk::ScopedAStatus setLbeAmbientLight(int ambientLux) override;
    ndk::ScopedAStatus getLbeState(LbeState *_aidl_return) override;
    ndk::ScopedAStatus isLhbmSupported(bool *_aidl_return) override;
    ndk::ScopedAStatus setLhbmState(bool enabled) override;
    ndk::ScopedAStatus getLhbmState(bool *_aidl_return) override;
    ndk::ScopedAStatus setCompensationImageHandle(const NativeHandle &native_handle,
                                                  const std::string &imageName,
                                                  int *_aidl_return) override;
    ndk::ScopedAStatus setMinIdleRefreshRate(int fps, int *_aidl_return) override;
    ndk::ScopedAStatus setRefreshRateThrottle(int delayMs, int *_aidl_return) override;

private:
    ExynosDevice *mDevice = nullptr;
};
} // namespace display
} // namespace pixel
} // namespace hardware
} // namespace google
} // namespace com
} // namespace aidl
