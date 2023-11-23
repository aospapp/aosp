/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "VibratorService"

#include <log/log.h>

#include <cutils/properties.h>
#include <hardware/hardware.h>
#include <hardware/vibrator.h>

#include "Vibrator.h"

#include <cinttypes>
#include <cmath>
#include <fstream>
#include <iostream>

namespace android {
namespace hardware {
namespace vibrator {
namespace V1_2 {
namespace implementation {

static constexpr int8_t MAX_RTP_INPUT = 127;
static constexpr int8_t MIN_RTP_INPUT = 0;

static constexpr char RTP_MODE[] = "rtp";
static constexpr char WAVEFORM_MODE[] = "waveform";

static constexpr uint32_t LOOP_MODE_OPEN = 1;
static constexpr uint32_t SINE_WAVE = 1;
static constexpr uint32_t SQUARE_WAVE = 0;

// Default max voltage 2.15V
static constexpr uint32_t VOLTAGE_MAX = 107;

// Use effect #1 in the waveform library for CLICK effect
static constexpr char WAVEFORM_CLICK_EFFECT_SEQ[] = "1 0";
static constexpr int32_t WAVEFORM_CLICK_EFFECT_MS = 6;

// Use effect #2 in the waveform library for TICK effect
static constexpr char WAVEFORM_TICK_EFFECT_SEQ[] = "2 0";
static constexpr int32_t WAVEFORM_TICK_EFFECT_MS = 2;

// Use effect #3 in the waveform library for DOUBLE_CLICK effect
static constexpr char WAVEFORM_DOUBLE_CLICK_EFFECT_SEQ[] = "3 0";
static constexpr uint32_t WAVEFORM_DOUBLE_CLICK_EFFECT_MS = 182;

// Use effect #4 in the waveform library for HEAVY_CLICK effect
static constexpr char WAVEFORM_HEAVY_CLICK_EFFECT_SEQ[] = "4 0";
static constexpr uint32_t WAVEFORM_HEAVY_CLICK_EFFECT_MS = 8;

using Status = ::android::hardware::vibrator::V1_0::Status;
using EffectStrength = ::android::hardware::vibrator::V1_0::EffectStrength;

Vibrator::Vibrator(HwApi &&hwapi, std::uint32_t short_lra_period, std::uint32_t long_lra_period)
    : mHwApi(std::move(hwapi)), mShortLraPeriod(short_lra_period), mLongLraPeriod(long_lra_period) {
    mClickDuration = property_get_int32("ro.vibrator.hal.click.duration", WAVEFORM_CLICK_EFFECT_MS);
    mTickDuration = property_get_int32("ro.vibrator.hal.tick.duration", WAVEFORM_TICK_EFFECT_MS);
    mHeavyClickDuration =
        property_get_int32("ro.vibrator.hal.heavyclick.duration", WAVEFORM_HEAVY_CLICK_EFFECT_MS);
    mShortVoltageMax = property_get_int32("ro.vibrator.hal.short.voltage", VOLTAGE_MAX);
    mLongVoltageMax = property_get_int32("ro.vibrator.hal.long.voltage", VOLTAGE_MAX);

    // This enables effect #1 from the waveform library to be triggered by SLPI
    // while the AP is in suspend mode
    mHwApi.lpTriggerEffect << 1 << std::endl;
    if (!mHwApi.lpTriggerEffect) {
        ALOGW("Failed to set LP trigger mode (%d): %s", errno, strerror(errno));
    }
}

Return<Status> Vibrator::on(uint32_t timeoutMs, bool isWaveform) {
    // Bonito / Sargo only support open-loop mode
    mHwApi.ctrlLoop << LOOP_MODE_OPEN << std::endl;
    mHwApi.duration << timeoutMs << std::endl;
    if (!mHwApi.duration) {
        ALOGE("Failed to set duration (%d): %s", errno, strerror(errno));
        return Status::UNKNOWN_ERROR;
    }

    if (isWaveform) {
        mHwApi.mode << WAVEFORM_MODE << std::endl;
        mHwApi.lraWaveShape << SINE_WAVE << std::endl;
        mHwApi.odClamp << mShortVoltageMax << std::endl;
        mHwApi.olLraPeriod << mShortLraPeriod << std::endl;
    } else {
        mHwApi.mode << RTP_MODE << std::endl;
        mHwApi.lraWaveShape << SQUARE_WAVE << std::endl;
        mHwApi.odClamp << mLongVoltageMax << std::endl;
        mHwApi.olLraPeriod << mLongLraPeriod << std::endl;
    }

    mHwApi.activate << 1 << std::endl;
    if (!mHwApi.activate) {
        ALOGE("Failed to activate (%d): %s", errno, strerror(errno));
        return Status::UNKNOWN_ERROR;
    }

    return Status::OK;
}

// Methods from ::android::hardware::vibrator::V1_2::IVibrator follow.
Return<Status> Vibrator::on(uint32_t timeoutMs) {
    return on(timeoutMs, false /* isWaveform */);
}

Return<Status> Vibrator::off()  {
    mHwApi.activate << 0 << std::endl;
    if (!mHwApi.activate) {
        ALOGE("Failed to turn vibrator off (%d): %s", errno, strerror(errno));
        return Status::UNKNOWN_ERROR;
    }
    return Status::OK;
}

Return<bool> Vibrator::supportsAmplitudeControl() {
    return (mHwApi.rtpInput ? true : false);
}

Return<Status> Vibrator::setAmplitude(uint8_t amplitude) {
    if (amplitude == 0) {
        return Status::BAD_VALUE;
    }

    int32_t rtp_input =
        std::round((amplitude - 1) / 254.0 * (MAX_RTP_INPUT - MIN_RTP_INPUT) + MIN_RTP_INPUT);

    mHwApi.rtpInput << rtp_input << std::endl;
    if (!mHwApi.rtpInput) {
        ALOGE("Failed to set amplitude (%d): %s", errno, strerror(errno));
        return Status::UNKNOWN_ERROR;
    }

    return Status::OK;
}

static uint8_t convertEffectStrength(EffectStrength strength) {
    uint8_t scale;

    switch (strength) {
        case EffectStrength::LIGHT:
            scale = 2;  // 50%
            break;
        case EffectStrength::MEDIUM:
        case EffectStrength::STRONG:
            scale = 0;  // 100%
            break;
    }

    return scale;
}

Return<void> Vibrator::perform(V1_0::Effect effect, EffectStrength strength, perform_cb _hidl_cb) {
    return performWrapper(effect, strength, _hidl_cb);
}

Return<void> Vibrator::perform_1_1(V1_1::Effect_1_1 effect, EffectStrength strength,
                                   perform_cb _hidl_cb) {
    return performWrapper(effect, strength, _hidl_cb);
}

Return<void> Vibrator::perform_1_2(Effect effect, EffectStrength strength, perform_cb _hidl_cb) {
    return performWrapper(effect, strength, _hidl_cb);
}

template <typename T>
Return<void> Vibrator::performWrapper(T effect, EffectStrength strength, perform_cb _hidl_cb) {
    auto validEffectRange = hidl_enum_range<T>();
    if (effect < *validEffectRange.begin() || effect > *std::prev(validEffectRange.end())) {
        _hidl_cb(Status::UNSUPPORTED_OPERATION, 0);
        return Void();
    }
    auto validStrengthRange = hidl_enum_range<EffectStrength>();
    if (strength < *validStrengthRange.begin() || strength > *std::prev(validStrengthRange.end())) {
        _hidl_cb(Status::UNSUPPORTED_OPERATION, 0);
        return Void();
    }
    return performEffect(static_cast<Effect>(effect), strength, _hidl_cb);
}

Return<void> Vibrator::performEffect(Effect effect, EffectStrength strength, perform_cb _hidl_cb) {
    Status status = Status::OK;
    uint32_t timeMS;

    switch (effect) {
        case Effect::CLICK:
            mHwApi.sequencer << WAVEFORM_CLICK_EFFECT_SEQ << std::endl;
            timeMS = mClickDuration;
            break;
        case Effect::DOUBLE_CLICK:
            mHwApi.sequencer << WAVEFORM_DOUBLE_CLICK_EFFECT_SEQ << std::endl;
            timeMS = WAVEFORM_DOUBLE_CLICK_EFFECT_MS;
            break;
        case Effect::TICK:
            mHwApi.sequencer << WAVEFORM_TICK_EFFECT_SEQ << std::endl;
            timeMS = mTickDuration;
            break;
        case Effect::HEAVY_CLICK:
            mHwApi.sequencer << WAVEFORM_HEAVY_CLICK_EFFECT_SEQ << std::endl;
            timeMS = mHeavyClickDuration;
            break;
        default:
            _hidl_cb(Status::UNSUPPORTED_OPERATION, 0);
            return Void();
    }
    mHwApi.scale << convertEffectStrength(strength) << std::endl;
    on(timeMS, true /* isWaveform */);
    _hidl_cb(status, timeMS);
    return Void();
}

}  // namespace implementation
}  // namespace V1_2
}  // namespace vibrator
}  // namespace hardware
}  // namespace android
