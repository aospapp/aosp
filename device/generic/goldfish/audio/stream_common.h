/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include <android/hardware/audio/7.0/IStream.h>

namespace android {
namespace hardware {
namespace audio {
namespace V7_0 {
namespace implementation {

using ::android::sp;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using namespace ::android::hardware::audio::common::V7_0;
using namespace ::android::hardware::audio::V7_0;

struct StreamCommon {
    StreamCommon(int32_t ioHandle,
                 const DeviceAddress& device,
                 const AudioConfig& config,
                 hidl_vec<AudioInOutFlag> flags);

    uint64_t getFrameSize() const;
    uint64_t getFrameCount() const;
    uint64_t getBufferSize() const;
    uint32_t getSampleRate() const;
    void getSupportedProfiles(const IStream::getSupportedProfiles_cb &_hidl_cb) const;
    AudioFormat getFormat() const;
    Result setFormat(AudioFormat format) const;
    void getAudioProperties(const IStream::getAudioProperties_cb &_hidl_cb) const;
    void getDevices(const IStream::getDevices_cb &_hidl_cb) const;
    Result setDevices(const hidl_vec<DeviceAddress>& devices) const;

    const int32_t m_ioHandle;
    const DeviceAddress m_device;
    const AudioConfig m_config;
    const hidl_vec<AudioInOutFlag> m_flags;
};

}  // namespace implementation
}  // namespace V7_0
}  // namespace audio
}  // namespace hardware
}  // namespace android
