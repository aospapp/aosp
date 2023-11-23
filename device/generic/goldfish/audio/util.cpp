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

#include <android_audio_policy_configuration_V7_0-enums.h>
#include <log/log.h>
//#include <cutils/bitops.h>
#include <cutils/sched_policy.h>
#include <system/audio.h>
#include <sys/resource.h>
#include <pthread.h>
#include "util.h"
#include "debug.h"

namespace xsd {
using namespace ::android::audio::policy::configuration::V7_0;
}

namespace android {
namespace hardware {
namespace audio {
namespace V7_0 {
namespace implementation {
namespace util {

namespace {

const std::array<uint32_t, 8> kSupportedRatesHz = {
    8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000
};

bool checkSampleRateHz(uint32_t value, uint32_t &suggested) {
    for (const uint32_t supported : kSupportedRatesHz) {
        if (value <= supported) {
            suggested = supported;
            return value == supported;
        }
    }

    suggested = kSupportedRatesHz.back();
    return FAILURE(false);
}

bool checkChannelMask(const bool isOut,
                      const AudioChannelMask &value,
                      AudioChannelMask &suggested) {
    switch (xsd::stringToAudioChannelMask(value)) {
    case xsd::AudioChannelMask::AUDIO_CHANNEL_OUT_MONO:
    case xsd::AudioChannelMask::AUDIO_CHANNEL_OUT_STEREO:
    case xsd::AudioChannelMask::AUDIO_CHANNEL_IN_MONO:
    case xsd::AudioChannelMask::AUDIO_CHANNEL_IN_STEREO:
        suggested = value;
        return true;

    default:
        suggested = toString(isOut ?
            xsd::AudioChannelMask::AUDIO_CHANNEL_OUT_STEREO :
            xsd::AudioChannelMask::AUDIO_CHANNEL_IN_MONO);
        return FAILURE(false);
    }
}

bool checkFormat(const AudioFormat &value, AudioFormat &suggested) {
    switch (xsd::stringToAudioFormat(value)) {
    case xsd::AudioFormat::AUDIO_FORMAT_PCM_16_BIT:
        suggested = value;
        return true;

    default:
        suggested = toString(xsd::AudioFormat::AUDIO_FORMAT_PCM_16_BIT);
        return FAILURE(false);
    }
}

size_t align(size_t v, size_t a) {
    return (v + a - 1) / a * a;
}

size_t getBufferSizeFrames(size_t duration_ms, uint32_t sample_rate) {
    // AudioFlinger requires the buffer to be aligned by 16 frames
    return align(sample_rate * duration_ms / 1000, 16);
}

}  // namespace

MicrophoneInfo getMicrophoneInfo() {
    MicrophoneInfo mic;

    mic.deviceId = "mic_goldfish";
    mic.group = 0;
    mic.indexInTheGroup = 0;
    mic.sensitivity = AUDIO_MICROPHONE_SENSITIVITY_UNKNOWN;
    mic.maxSpl = AUDIO_MICROPHONE_SENSITIVITY_UNKNOWN;
    mic.minSpl = AUDIO_MICROPHONE_SENSITIVITY_UNKNOWN;
    mic.directionality = AudioMicrophoneDirectionality::UNKNOWN;
    mic.position.x = AUDIO_MICROPHONE_COORDINATE_UNKNOWN;
    mic.position.y = AUDIO_MICROPHONE_COORDINATE_UNKNOWN;
    mic.position.z = AUDIO_MICROPHONE_COORDINATE_UNKNOWN;
    mic.orientation.x = AUDIO_MICROPHONE_COORDINATE_UNKNOWN;
    mic.orientation.y = AUDIO_MICROPHONE_COORDINATE_UNKNOWN;
    mic.orientation.z = AUDIO_MICROPHONE_COORDINATE_UNKNOWN;

    return mic;
}

size_t countChannels(const AudioChannelMask &mask) {
    return xsd::getChannelCount(mask);
}

size_t getBytesPerSample(const AudioFormat &format) {
    if (format == "AUDIO_FORMAT_PCM_16_BIT") {
        return 2;
    } else {
        ALOGE("util::%s:%d unknown format, '%s'", __func__, __LINE__, format.c_str());
        return 0;
    }
}

bool checkAudioConfig(const bool isOut,
                      size_t duration_ms,
                      const AudioConfig &src,
                      AudioConfig &suggested) {
    bool result = true;
    suggested = src;

    if (!checkSampleRateHz(src.base.sampleRateHz, suggested.base.sampleRateHz)) {
        result = false;
    }

    if (!checkChannelMask(isOut, src.base.channelMask, suggested.base.channelMask)) {
        result = false;
    }

    if (!checkFormat(src.base.format, suggested.base.format)) {
        result = false;
    }

    if (src.frameCount == 0) {
        suggested.frameCount = getBufferSizeFrames(duration_ms, src.base.sampleRateHz);
    }

    return result;
}

TimeSpec nsecs2TimeSpec(nsecs_t ns) {
    TimeSpec ts;
    ts.tvSec = ns2s(ns);
    ts.tvNSec = ns - s2ns(ts.tvSec);
    return ts;
}

void setThreadPriority(int prio) {
    setpriority(PRIO_PROCESS, 0, prio);
    set_sched_policy(0, SP_FOREGROUND);
}

}  // namespace util
}  // namespace implementation
}  // namespace V7_0
}  // namespace audio
}  // namespace hardware
}  // namespace android
