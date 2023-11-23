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

#define LOG_TAG "trout.audiocontrol@2.0"

#include "AudioControl.h"

#include <android-base/logging.h>
#include <hidl/HidlTransportSupport.h>

#include "CloseHandle.h"

namespace android::hardware::automotive::audiocontrol::V2_0::implementation {

using ::android::hardware::hidl_handle;
using ::android::hardware::hidl_string;

AudioControl::AudioControl(const std::string& audio_control_server_addr)
    : mAudioControlServer(MakeAudioControlServer(audio_control_server_addr)) {}

Return<sp<ICloseHandle>> AudioControl::registerFocusListener(const sp<IFocusListener>& listener) {
    LOG(DEBUG) << "registering focus listener";
    sp<ICloseHandle> closeHandle(nullptr);

    if (listener) {
        closeHandle = new CloseHandle(mAudioControlServer->RegisterFocusListener(listener));
    } else {
        LOG(ERROR) << "Unexpected nullptr for listener resulting in no-op.";
    }

    return closeHandle;
}

Return<void> AudioControl::setBalanceTowardRight(float) {
    return Void();
}

Return<void> AudioControl::setFadeTowardFront(float) {
    return Void();
}

Return<void> AudioControl::onAudioFocusChange(hidl_bitfield<AudioUsage> usage, int zoneId,
                                              hidl_bitfield<AudioFocusChange> focusChange) {
    LOG(INFO) << "Focus changed: " << toString(static_cast<AudioUsage>(focusChange))
              << " for usage " << toString(static_cast<AudioFocusChange>(usage)) << " in zone "
              << zoneId;
    return Void();
}

Return<void> AudioControl::debug(const hidl_handle&, const hidl_vec<hidl_string>&) {
    return Void();
}

bool AudioControl::isHealthy() {
    // TODO(egranata, chenhaosjtuacm): fill this in with a real check
    // e.g. add a heartbeat message to remote side
    return true;
}

void AudioControl::ServerStart() {
    mAudioControlServer->Start();
}

void AudioControl::ServerJoin() {
    mAudioControlServer->Join();
}

}  // namespace android::hardware::automotive::audiocontrol::V2_0::implementation
