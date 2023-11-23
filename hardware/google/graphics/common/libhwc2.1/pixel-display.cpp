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

#include "pixel-display.h"

#include <aidlcommonsupport/NativeHandle.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <sys/types.h>
#include <utils/Errors.h>

#include "ExynosDisplay.h"

extern int32_t load_png_image(const char *filepath, buffer_handle_t buffer);

using ::aidl::com::google::hardware::pixel::display::Display;

void PixelDisplayInit(ExynosDevice *device) {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    std::shared_ptr<Display> display = ndk::SharedRefBase::make<Display>(device);
    LOG(INFO) << "pixel display service start...";
    const std::string instance = std::string() + Display::descriptor + "/default";
    binder_status_t status =
            AServiceManager_addService(display->asBinder().get(), instance.c_str());
    CHECK(status == STATUS_OK);

    ABinderProcess_startThreadPool();
}

int32_t readCompensationImage(const aidl::android::hardware::common::NativeHandle &handle,
                              const std::string &imageName) {
    ALOGI("setCompensationImageHandle, imageName = %s", imageName.c_str());

    std::string shadowCompensationImage("/mnt/vendor/persist/display/");
    shadowCompensationImage.append(imageName);

    native_handle_t *clone = makeFromAidl(handle);

    return load_png_image(shadowCompensationImage.c_str(), static_cast<buffer_handle_t>(clone));
}

namespace aidl {
namespace com {
namespace google {
namespace hardware {
namespace pixel {
namespace display {

// ----------------------------------------------------------------------------

ndk::ScopedAStatus Display::isHbmSupported(bool *_aidl_return) {
    *_aidl_return = false;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Display::setHbmState(HbmState state) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::getHbmState(HbmState *_aidl_return) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::isLbeSupported(bool *_aidl_return) {
    if (mDevice) {
        *_aidl_return = mDevice->isLbeSupported();
        return ndk::ScopedAStatus::ok();
    }
    *_aidl_return = false;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Display::setLbeState(LbeState state) {
    if (mDevice) {
        mDevice->setLbeState(state);
        return ndk::ScopedAStatus::ok();
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::setLbeAmbientLight(int ambientLux) {
    if (mDevice) {
        mDevice->setLbeAmbientLight(ambientLux);
        return ndk::ScopedAStatus::ok();
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::getLbeState(LbeState *_aidl_return) {
    if (mDevice) {
        *_aidl_return = mDevice->getLbeState();
        return ndk::ScopedAStatus::ok();
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::isLhbmSupported(bool *_aidl_return) {
    if (mDevice) {
        *_aidl_return = mDevice->isLhbmSupported();
        return ndk::ScopedAStatus::ok();
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::setLhbmState(bool enabled) {
    if (mDevice && mDevice->isLhbmSupported()) {
        int32_t ret = mDevice->setLhbmState(enabled);
        if (!ret)
            return ndk::ScopedAStatus::ok();
        else if (ret == TIMED_OUT)
            return ndk::ScopedAStatus::fromExceptionCode(STATUS_TIMED_OUT);
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::getLhbmState(bool *_aidl_return) {
    if (mDevice && mDevice->isLhbmSupported()) {
        *_aidl_return = mDevice->getLhbmState();
        return ndk::ScopedAStatus::ok();
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::setCompensationImageHandle(const NativeHandle &native_handle,
                                                       const std::string &imageName,
                                                       int *_aidl_return) {
    if (mDevice && mDevice->isColorCalibratedByDevice()) {
        *_aidl_return = readCompensationImage(native_handle, imageName);
    } else {
        *_aidl_return = -1;
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Display::setMinIdleRefreshRate(int fps, int *_aidl_return) {
    if (mDevice) {
        *_aidl_return = mDevice->setMinIdleRefreshRate(fps);
        return ndk::ScopedAStatus::ok();
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Display::setRefreshRateThrottle(int delayMs, int *_aidl_return) {
    if (mDevice) {
        *_aidl_return = mDevice->setRefreshRateThrottle(delayMs);
        return ndk::ScopedAStatus::ok();
    }
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}
} // namespace display
} // namespace pixel
} // namespace hardware
} // namespace google
} // namespace com
} // namespace aidl
