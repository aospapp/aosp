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

#include <android/hardware/audio/common/6.0/types.h>
#include <android/hardware/automotive/audiocontrol/2.0/IAudioControl.h>
#include <android/hardware/automotive/audiocontrol/2.0/ICloseHandle.h>
#include <hidl/MQDescriptor.h>
#include <hidl/Status.h>

#include "AudioControlServer.h"

using android::hardware::audio::common::V6_0::AudioUsage;

namespace android::hardware::automotive::audiocontrol::V2_0::implementation {

class AudioControl : public IAudioControl {
  public:
    explicit AudioControl(const std::string& audio_control_server_addr);

    bool isHealthy();

    void ServerStart();

    void ServerJoin();

    // Methods from ::android::hardware::automotive::audiocontrol::V2_0::IAudioControl follow.
    Return<sp<ICloseHandle>> registerFocusListener(const sp<IFocusListener>& listener);
    Return<void> onAudioFocusChange(hidl_bitfield<AudioUsage> usage, int zoneId,
                                    hidl_bitfield<AudioFocusChange> focusChange);
    Return<void> setBalanceTowardRight(float value) override;
    Return<void> setFadeTowardFront(float value) override;
    Return<void> debug(const hidl_handle& fd, const hidl_vec<hidl_string>& options) override;

  private:
    std::unique_ptr<AudioControlServer> mAudioControlServer;
};

}  // namespace android::hardware::automotive::audiocontrol::V2_0::implementation
