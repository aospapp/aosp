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

#include "Vibrator.h"

#include <glob.h>
#include <hardware/hardware.h>
#include <hardware/vibrator.h>
#include <linux/input.h>
#include <log/log.h>
#include <stdio.h>
#include <utils/Trace.h>

#include <cinttypes>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>

#ifndef ARRAY_SIZE
#define ARRAY_SIZE(x) (sizeof((x)) / sizeof((x)[0]))
#endif

#define PROC_SND_PCM "/proc/asound/pcm"
#define HAPTIC_PCM_DEVICE_SYMBOL "haptic nohost playback"

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {
static constexpr uint8_t FF_CUSTOM_DATA_LEN = 2;
static constexpr uint16_t FF_CUSTOM_DATA_LEN_MAX_COMP = 2044;  // (COMPOSE_SIZE_MAX + 1) * 8 + 4

static constexpr uint32_t WAVEFORM_DOUBLE_CLICK_SILENCE_MS = 100;

static constexpr uint32_t WAVEFORM_LONG_VIBRATION_THRESHOLD_MS = 50;

static constexpr uint8_t VOLTAGE_SCALE_MAX = 100;

static constexpr int8_t MAX_COLD_START_LATENCY_MS = 6;  // I2C Transaction + DSP Return-From-Standby
static constexpr int8_t MAX_PAUSE_TIMING_ERROR_MS = 1;  // ALERT Irq Handling
static constexpr uint32_t MAX_TIME_MS = UINT16_MAX;

static constexpr auto ASYNC_COMPLETION_TIMEOUT = std::chrono::milliseconds(100);
static constexpr auto POLLING_TIMEOUT = 20;
static constexpr int32_t COMPOSE_DELAY_MAX_MS = 10000;

/* nsections is 8 bits. Need to preserve 1 section for the first delay before the first effect. */
static constexpr int32_t COMPOSE_SIZE_MAX = 254;
static constexpr int32_t COMPOSE_PWLE_SIZE_MAX_DEFAULT = 127;

// Measured resonant frequency, f0_measured, is represented by Q10.14 fixed
// point format on cs40l26 devices. The expression to calculate f0 is:
//   f0 = f0_measured / 2^Q14_BIT_SHIFT
// See the LRA Calibration Support documentation for more details.
static constexpr int32_t Q14_BIT_SHIFT = 14;

// Measured Q factor, q_measured, is represented by Q8.16 fixed
// point format on cs40l26 devices. The expression to calculate q is:
//   q = q_measured / 2^Q16_BIT_SHIFT
// See the LRA Calibration Support documentation for more details.
static constexpr int32_t Q16_BIT_SHIFT = 16;

static constexpr int32_t COMPOSE_PWLE_PRIMITIVE_DURATION_MAX_MS = 16383;

static constexpr float PWLE_LEVEL_MIN = 0.0;
static constexpr float PWLE_LEVEL_MAX = 1.0;
static constexpr float PWLE_FREQUENCY_RESOLUTION_HZ = 0.25;
static constexpr float PWLE_FREQUENCY_MIN_HZ = 0.25;
static constexpr float PWLE_FREQUENCY_MAX_HZ = 1023.75;
static constexpr float PWLE_BW_MAP_SIZE =
        1 + ((PWLE_FREQUENCY_MAX_HZ - PWLE_FREQUENCY_MIN_HZ) / PWLE_FREQUENCY_RESOLUTION_HZ);

static struct pcm_config haptic_nohost_config = {
        .channels = 1,
        .rate = 48000,
        .period_size = 80,
        .period_count = 2,
        .format = PCM_FORMAT_S16_LE,
};

static uint16_t amplitudeToScale(float amplitude, float maximum) {
    float ratio = 100; /* Unit: % */
    if (maximum != 0)
        ratio = amplitude / maximum * 100;

    if (maximum == 0 || ratio > 100)
        ratio = 100;

    return std::round(ratio);
}

enum class AlwaysOnId : uint32_t {
    GPIO_RISE,
    GPIO_FALL,
};

enum WaveformBankID : uint8_t {
    RAM_WVFRM_BANK,
    ROM_WVFRM_BANK,
    OWT_WVFRM_BANK,
};

enum WaveformIndex : uint16_t {
    /* Physical waveform */
    WAVEFORM_LONG_VIBRATION_EFFECT_INDEX = 0,
    WAVEFORM_RESERVED_INDEX_1 = 1,
    WAVEFORM_CLICK_INDEX = 2,
    WAVEFORM_SHORT_VIBRATION_EFFECT_INDEX = 3,
    WAVEFORM_THUD_INDEX = 4,
    WAVEFORM_SPIN_INDEX = 5,
    WAVEFORM_QUICK_RISE_INDEX = 6,
    WAVEFORM_SLOW_RISE_INDEX = 7,
    WAVEFORM_QUICK_FALL_INDEX = 8,
    WAVEFORM_LIGHT_TICK_INDEX = 9,
    WAVEFORM_LOW_TICK_INDEX = 10,
    WAVEFORM_RESERVED_MFG_1,
    WAVEFORM_RESERVED_MFG_2,
    WAVEFORM_RESERVED_MFG_3,
    WAVEFORM_MAX_PHYSICAL_INDEX,
    /* OWT waveform */
    WAVEFORM_COMPOSE = WAVEFORM_MAX_PHYSICAL_INDEX,
    WAVEFORM_PWLE,
    /*
     * Refer to <linux/input.h>, the WAVEFORM_MAX_INDEX must not exceed 96.
     * #define FF_GAIN		0x60  // 96 in decimal
     * #define FF_MAX_EFFECTS	FF_GAIN
     */
    WAVEFORM_MAX_INDEX,
};

std::vector<CompositePrimitive> defaultSupportedPrimitives = {
        ndk::enum_range<CompositePrimitive>().begin(), ndk::enum_range<CompositePrimitive>().end()};

static int min(int x, int y) {
    return x < y ? x : y;
}

struct dspmem_chunk {
    uint8_t *head;
    uint8_t *current;
    uint8_t *max;
    int bytes;

    uint32_t cache;
    int cachebits;
};

static dspmem_chunk *dspmem_chunk_create(void *data, int size) {
    auto ch = new dspmem_chunk{
            .head = reinterpret_cast<uint8_t *>(data),
            .current = reinterpret_cast<uint8_t *>(data),
            .max = reinterpret_cast<uint8_t *>(data) + size,
    };

    return ch;
}

static bool dspmem_chunk_end(struct dspmem_chunk *ch) {
    return ch->current == ch->max;
}

static int dspmem_chunk_bytes(struct dspmem_chunk *ch) {
    return ch->bytes;
}

static int dspmem_chunk_write(struct dspmem_chunk *ch, int nbits, uint32_t val) {
    int nwrite, i;

    nwrite = min(24 - ch->cachebits, nbits);
    ch->cache <<= nwrite;
    ch->cache |= val >> (nbits - nwrite);
    ch->cachebits += nwrite;
    nbits -= nwrite;

    if (ch->cachebits == 24) {
        if (dspmem_chunk_end(ch))
            return -ENOSPC;

        ch->cache &= 0xFFFFFF;
        for (i = 0; i < sizeof(ch->cache); i++, ch->cache <<= 8)
            *ch->current++ = (ch->cache & 0xFF000000) >> 24;

        ch->bytes += sizeof(ch->cache);
        ch->cachebits = 0;
    }

    if (nbits)
        return dspmem_chunk_write(ch, nbits, val);

    return 0;
}

static int dspmem_chunk_flush(struct dspmem_chunk *ch) {
    if (!ch->cachebits)
        return 0;

    return dspmem_chunk_write(ch, 24 - ch->cachebits, 0);
}

std::vector<struct ff_effect> mFfEffects;

Vibrator::Vibrator(std::unique_ptr<HwApi> hwapi, std::unique_ptr<HwCal> hwcal)
    : mHwApi(std::move(hwapi)), mHwCal(std::move(hwcal)), mAsyncHandle(std::async([] {})) {
    int32_t longFrequencyShift;
    std::string caldata{8, '0'};
    uint32_t calVer;

    glob_t inputEventPaths;
    if (glob("/dev/input/event*", 0, nullptr, &inputEventPaths)) {
        ALOGE("Fail to get input event paths (%d): %s", errno, strerror(errno));
        return;
    }

    int fd = -1;
    uint32_t val = 0;
    char str[20] = {0x00};
    const char *inputEventName = std::getenv("INPUT_EVENT_NAME");
    for (uint8_t retry = 0; retry < 3; retry++) {
        for (int i = 0; i < inputEventPaths.gl_pathc; i++) {
            fd = TEMP_FAILURE_RETRY(open(inputEventPaths.gl_pathv[i], O_RDWR));
            if (fd > 0) {
                if (ioctl(fd, EVIOCGBIT(0, sizeof(val)), &val) > 0 && (val & (1 << EV_FF)) &&
                    ioctl(fd, EVIOCGNAME(sizeof(str)), &str) > 0 &&
                    strcmp(str, inputEventName) == 0) {
                    mInputFd.reset(fd);
                    ALOGI("Control %s through %s", inputEventName, inputEventPaths.gl_pathv[i]);
                    break;
                }
                close(fd);
            }
        }
        if (mInputFd.ok()) {
            break;
        }

        sleep(1);
        ALOGW("Retry to search the input");
    }
    globfree(&inputEventPaths);
    if (!mInputFd.ok()) {
        ALOGE("Fail to get an input event with name %s", inputEventName);
        return;
    }

    /*
     * Create custom effects for all physical waveforms.
     * 1. Set the initial duration for the corresponding RAM waveform.
     * 2. Write to the force feedback driver. If the waveform firmware is not loaded,
     *    retry at most 10 times and then abort the constructor.
     * 3. Store the effect ID.
     */
    uint8_t retry = 0;
    mFfEffects.resize(WAVEFORM_MAX_INDEX);
    mEffectDurations.resize(WAVEFORM_MAX_INDEX);
    mEffectDurations = {
            1000, 100, 30, 1000, 300, 130, 150, 500, 100, 15, 20, 1000, 1000, 1000,
    }; /* 11+3 waveforms. The duration must < UINT16_MAX */

    uint8_t effectIndex = 0;

    for (effectIndex = 0; effectIndex < WAVEFORM_MAX_PHYSICAL_INDEX; effectIndex++) {
        mFfEffects[effectIndex] = {
                .type = FF_PERIODIC,
                .id = -1,
                .replay.length = static_cast<uint16_t>(mEffectDurations[effectIndex]),
                .u.periodic.waveform = FF_CUSTOM,
                .u.periodic.custom_data = new int16_t[2]{RAM_WVFRM_BANK, effectIndex},
                .u.periodic.custom_len = FF_CUSTOM_DATA_LEN,
        };

        while (true) {
            if (ioctl(mInputFd, EVIOCSFF, &mFfEffects[effectIndex]) < 0) {
                ALOGE("Failed upload effect %d (%d): %s", effectIndex, errno, strerror(errno));

                if (retry < 10) {
                    sleep(1);
                    ALOGW("Retry #%u", ++retry);
                    continue;
                } else {
                    ALOGE("Retried but the initialization was failed!");
                    return;
                }
            }
            mFfEffects[effectIndex].id = effectIndex;
            break;
        }
    }
    if (effectIndex != WAVEFORM_MAX_PHYSICAL_INDEX) {
        ALOGE("Incomplete effect initialization!");
        return;
    }

    /* Initiate placeholders for OWT effects. */
    for (effectIndex = WAVEFORM_MAX_PHYSICAL_INDEX; effectIndex < WAVEFORM_MAX_INDEX;
         effectIndex++) {
        mFfEffects[effectIndex] = {
                .type = FF_PERIODIC,
                .id = -1,
                .replay.length = 0,
                .u.periodic.waveform = FF_CUSTOM,
                .u.periodic.custom_data = nullptr,
                .u.periodic.custom_len = 0,
        };
    }

    if (mHwCal->getF0(&caldata)) {
        mHwApi->setF0(caldata);
    }
    if (mHwCal->getRedc(&caldata)) {
        mHwApi->setRedc(caldata);
    }
    if (mHwCal->getQ(&caldata)) {
        mHwApi->setQ(caldata);
    }

    mHwCal->getLongFrequencyShift(&longFrequencyShift);
    if (longFrequencyShift > 0) {
        mF0Offset = longFrequencyShift * std::pow(2, 14);
    } else if (longFrequencyShift < 0) {
        mF0Offset = std::pow(2, 24) - std::abs(longFrequencyShift) * std::pow(2, 14);
    } else {
        mF0Offset = 0;
    }

    mHwCal->getVersion(&calVer);
    if (calVer == 2) {
        mHwCal->getTickVolLevels(&mTickEffectVol);
        mHwCal->getClickVolLevels(&mClickEffectVol);
        mHwCal->getLongVolLevels(&mLongEffectVol);
    } else {
        ALOGW("Unsupported calibration version!");
    }

    mIsUnderExternalControl = false;

    mIsChirpEnabled = mHwCal->isChirpEnabled();

    mHwCal->getSupportedPrimitives(&mSupportedPrimitivesBits);
    if (mSupportedPrimitivesBits > 0) {
        for (auto e : defaultSupportedPrimitives) {
            if (mSupportedPrimitivesBits & (1 << uint32_t(e))) {
                mSupportedPrimitives.emplace_back(e);
            }
        }
    } else {
        for (auto e : defaultSupportedPrimitives) {
            mSupportedPrimitivesBits |= (1 << uint32_t(e));
        }
        mSupportedPrimitives = defaultSupportedPrimitives;
    }
}

ndk::ScopedAStatus Vibrator::getCapabilities(int32_t *_aidl_return) {
    ATRACE_NAME("Vibrator::getCapabilities");

    int32_t ret = IVibrator::CAP_ON_CALLBACK | IVibrator::CAP_PERFORM_CALLBACK |
                  IVibrator::CAP_AMPLITUDE_CONTROL | IVibrator::CAP_ALWAYS_ON_CONTROL |
                  IVibrator::CAP_GET_RESONANT_FREQUENCY | IVibrator::CAP_GET_Q_FACTOR;
    if (hasHapticAlsaDevice()) {
        ret |= IVibrator::CAP_EXTERNAL_CONTROL;
    }
    if (mHwApi->hasOwtFreeSpace()) {
        ret |= IVibrator::CAP_COMPOSE_EFFECTS;

        if (mIsChirpEnabled) {
            ret |= IVibrator::CAP_FREQUENCY_CONTROL | IVibrator::CAP_COMPOSE_PWLE_EFFECTS;
        }
    }
    *_aidl_return = ret;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::off() {
    ATRACE_NAME("Vibrator::off");
    if (mActiveId < 0) {
        return ndk::ScopedAStatus::ok();
    }

    struct input_event play = {
            .type = EV_FF,
            .code = static_cast<uint16_t>(mActiveId),
            .value = 0,
    };

    if (write(mInputFd, (const void *)&play, sizeof(play)) != sizeof(play)) {
        ALOGE("Failed to stop effect %d (%d): %s", play.code, errno, strerror(errno));
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    mActiveId = -1;
    setGlobalAmplitude(false);
    mHwApi->setF0Offset(0);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::on(int32_t timeoutMs,
                                const std::shared_ptr<IVibratorCallback> &callback) {
    ATRACE_NAME("Vibrator::on");
    if (timeoutMs > MAX_TIME_MS) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    const uint16_t index = (timeoutMs < WAVEFORM_LONG_VIBRATION_THRESHOLD_MS)
                                   ? WAVEFORM_SHORT_VIBRATION_EFFECT_INDEX
                                   : WAVEFORM_LONG_VIBRATION_EFFECT_INDEX;
    if (MAX_COLD_START_LATENCY_MS <= MAX_TIME_MS - timeoutMs) {
        timeoutMs += MAX_COLD_START_LATENCY_MS;
    }
    setGlobalAmplitude(true);
    mHwApi->setF0Offset(mF0Offset);
    return on(timeoutMs, index, callback);
}

ndk::ScopedAStatus Vibrator::perform(Effect effect, EffectStrength strength,
                                     const std::shared_ptr<IVibratorCallback> &callback,
                                     int32_t *_aidl_return) {
    ATRACE_NAME("Vibrator::perform");
    return performEffect(effect, strength, callback, _aidl_return);
}

ndk::ScopedAStatus Vibrator::getSupportedEffects(std::vector<Effect> *_aidl_return) {
    *_aidl_return = {Effect::TEXTURE_TICK, Effect::TICK, Effect::CLICK, Effect::HEAVY_CLICK,
                     Effect::DOUBLE_CLICK};
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setAmplitude(float amplitude) {
    ATRACE_NAME("Vibrator::setAmplitude");
    if (amplitude <= 0.0f || amplitude > 1.0f) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    mLongEffectScale = amplitude;
    if (!isUnderExternalControl()) {
        return setGlobalAmplitude(true);
    } else {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ndk::ScopedAStatus Vibrator::setExternalControl(bool enabled) {
    ATRACE_NAME("Vibrator::setExternalControl");
    setGlobalAmplitude(enabled);

    if (mHasHapticAlsaDevice) {
        if (!enableHapticPcmAmp(&mHapticPcm, enabled, mCard, mDevice)) {
            ALOGE("Failed to %s haptic pcm device: %d", (enabled ? "enable" : "disable"), mDevice);
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
        }
    }

    mIsUnderExternalControl = enabled;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t *maxDelayMs) {
    ATRACE_NAME("Vibrator::getCompositionDelayMax");
    *maxDelayMs = COMPOSE_DELAY_MAX_MS;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t *maxSize) {
    ATRACE_NAME("Vibrator::getCompositionSizeMax");
    *maxSize = COMPOSE_SIZE_MAX;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedPrimitives(std::vector<CompositePrimitive> *supported) {
    *supported = mSupportedPrimitives;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive primitive,
                                                  int32_t *durationMs) {
    ndk::ScopedAStatus status;
    uint32_t effectIndex;
    if (primitive != CompositePrimitive::NOOP) {
        status = getPrimitiveDetails(primitive, &effectIndex);
        if (!status.isOk()) {
            return status;
        }

        *durationMs = mEffectDurations[effectIndex];
    } else {
        *durationMs = 0;
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::compose(const std::vector<CompositeEffect> &composite,
                                     const std::shared_ptr<IVibratorCallback> &callback) {
    ATRACE_NAME("Vibrator::compose");
    uint16_t size;
    uint16_t nextEffectDelay;

    auto ch = dspmem_chunk_create(new uint8_t[FF_CUSTOM_DATA_LEN_MAX_COMP]{0x00},
                                  FF_CUSTOM_DATA_LEN_MAX_COMP);

    if (composite.size() > COMPOSE_SIZE_MAX || composite.empty()) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    /* Check if there is a wait before the first effect. */
    nextEffectDelay = composite.front().delayMs;
    if (nextEffectDelay > COMPOSE_DELAY_MAX_MS || nextEffectDelay < 0) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    } else if (nextEffectDelay > 0) {
        size = composite.size() + 1;
    } else {
        size = composite.size();
    }

    dspmem_chunk_write(ch, 8, 0);                      /* Padding */
    dspmem_chunk_write(ch, 8, (uint8_t)(0xFF & size)); /* nsections */
    dspmem_chunk_write(ch, 8, 0);                      /* repeat */
    uint8_t header_count = ch->bytes;

    /* Insert 1 section for a wait before the first effect. */
    if (nextEffectDelay) {
        dspmem_chunk_write(ch, 32, 0); /* amplitude, index, repeat & flags */
        dspmem_chunk_write(ch, 16, (uint16_t)(0xFFFF & nextEffectDelay)); /* delay */
    }

    for (uint32_t i_curr = 0, i_next = 1; i_curr < composite.size(); i_curr++, i_next++) {
        auto &e_curr = composite[i_curr];
        uint32_t effectIndex = 0;
        uint32_t effectVolLevel = 0;
        if (e_curr.scale < 0.0f || e_curr.scale > 1.0f) {
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        }

        if (e_curr.primitive != CompositePrimitive::NOOP) {
            ndk::ScopedAStatus status;
            status = getPrimitiveDetails(e_curr.primitive, &effectIndex);
            if (!status.isOk()) {
                return status;
            }
            effectVolLevel = intensityToVolLevel(e_curr.scale, effectIndex);
        }

        /* Fetch the next composite effect delay and fill into the current section */
        nextEffectDelay = 0;
        if (i_next < composite.size()) {
            auto &e_next = composite[i_next];
            int32_t delay = e_next.delayMs;

            if (delay > COMPOSE_DELAY_MAX_MS || delay < 0) {
                return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
            }
            nextEffectDelay = delay;
        }

        if (effectIndex == 0 && nextEffectDelay == 0) {
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        }

        dspmem_chunk_write(ch, 8, (uint8_t)(0xFF & effectVolLevel));      /* amplitude */
        dspmem_chunk_write(ch, 8, (uint8_t)(0xFF & effectIndex));         /* index */
        dspmem_chunk_write(ch, 8, 0);                                     /* repeat */
        dspmem_chunk_write(ch, 8, 0);                                     /* flags */
        dspmem_chunk_write(ch, 16, (uint16_t)(0xFFFF & nextEffectDelay)); /* delay */
    }
    dspmem_chunk_flush(ch);
    if (header_count == ch->bytes) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    } else {
        return performEffect(0 /*ignored*/, 0 /*ignored*/, ch, callback);
    }
}

ndk::ScopedAStatus Vibrator::on(uint32_t timeoutMs, uint32_t effectIndex,
                                const std::shared_ptr<IVibratorCallback> &callback) {
    if (effectIndex > WAVEFORM_MAX_INDEX) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    if (mAsyncHandle.wait_for(ASYNC_COMPLETION_TIMEOUT) != std::future_status::ready) {
        ALOGE("Previous vibration pending.");
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    /* Update duration for long/short vibration. */
    if (effectIndex == WAVEFORM_SHORT_VIBRATION_EFFECT_INDEX ||
        effectIndex == WAVEFORM_LONG_VIBRATION_EFFECT_INDEX) {
        mFfEffects[effectIndex].replay.length = static_cast<uint16_t>(timeoutMs);
        if (ioctl(mInputFd, EVIOCSFF, &mFfEffects[effectIndex]) < 0) {
            ALOGE("Failed to edit effect %d (%d): %s", effectIndex, errno, strerror(errno));
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
        }
    }

    /* Play the event now. */
    struct input_event play = {
            .type = EV_FF,
            .code = static_cast<uint16_t>(effectIndex),
            .value = 1,
    };
    if (write(mInputFd, (const void *)&play, sizeof(play)) != sizeof(play)) {
        ALOGE("Failed to play effect %d (%d): %s", play.code, errno, strerror(errno));
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    mActiveId = play.code;

    mAsyncHandle = std::async(&Vibrator::waitForComplete, this, callback);

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setEffectAmplitude(float amplitude, float maximum) {
    uint16_t scale = amplitudeToScale(amplitude, maximum);
    struct input_event gain = {
            .type = EV_FF,
            .code = FF_GAIN,
            .value = scale,
    };
    if (write(mInputFd, (const void *)&gain, sizeof(gain)) != sizeof(gain)) {
        ALOGE("Failed to set the gain to %u (%d): %s", scale, errno, strerror(errno));
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setGlobalAmplitude(bool set) {
    uint8_t amplitude = set ? roundf(mLongEffectScale * mLongEffectVol[1]) : VOLTAGE_SCALE_MAX;
    if (!set) {
        mLongEffectScale = 1.0;  // Reset the scale for the later new effect.
    }
    return setEffectAmplitude(amplitude, VOLTAGE_SCALE_MAX);
}

ndk::ScopedAStatus Vibrator::getSupportedAlwaysOnEffects(std::vector<Effect> *_aidl_return) {
    *_aidl_return = {Effect::TEXTURE_TICK, Effect::TICK, Effect::CLICK, Effect::HEAVY_CLICK};
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::alwaysOnEnable(int32_t id, Effect effect, EffectStrength strength) {
    ndk::ScopedAStatus status;
    uint32_t effectIndex;
    uint32_t timeMs;
    uint32_t volLevel;
    uint16_t scale;
    status = getSimpleDetails(effect, strength, &effectIndex, &timeMs, &volLevel);
    if (!status.isOk()) {
        return status;
    }

    scale = amplitudeToScale(volLevel, VOLTAGE_SCALE_MAX);

    switch (static_cast<AlwaysOnId>(id)) {
        case AlwaysOnId::GPIO_RISE:
            // mHwApi->setGpioRiseIndex(effectIndex);
            // mHwApi->setGpioRiseScale(scale);
            return ndk::ScopedAStatus::ok();
        case AlwaysOnId::GPIO_FALL:
            // mHwApi->setGpioFallIndex(effectIndex);
            // mHwApi->setGpioFallScale(scale);
            return ndk::ScopedAStatus::ok();
    }

    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}
ndk::ScopedAStatus Vibrator::alwaysOnDisable(int32_t id) {
    switch (static_cast<AlwaysOnId>(id)) {
        case AlwaysOnId::GPIO_RISE:
            // mHwApi->setGpioRiseIndex(0);
            return ndk::ScopedAStatus::ok();
        case AlwaysOnId::GPIO_FALL:
            // mHwApi->setGpioFallIndex(0);
            return ndk::ScopedAStatus::ok();
    }

    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Vibrator::getResonantFrequency(float *resonantFreqHz) {
    std::string caldata{8, '0'};
    if (!mHwCal->getF0(&caldata)) {
        ALOGE("Failed to get resonant frequency (%d): %s", errno, strerror(errno));
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    *resonantFreqHz = static_cast<float>(std::stoul(caldata, nullptr, 16)) / (1 << Q14_BIT_SHIFT);

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getQFactor(float *qFactor) {
    std::string caldata{8, '0'};
    if (!mHwCal->getQ(&caldata)) {
        ALOGE("Failed to get q factor (%d): %s", errno, strerror(errno));
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    *qFactor = static_cast<float>(std::stoul(caldata, nullptr, 16)) / (1 << Q16_BIT_SHIFT);

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getFrequencyResolution(float *freqResolutionHz) {
    int32_t capabilities;
    Vibrator::getCapabilities(&capabilities);
    if (capabilities & IVibrator::CAP_FREQUENCY_CONTROL) {
        *freqResolutionHz = PWLE_FREQUENCY_RESOLUTION_HZ;
        return ndk::ScopedAStatus::ok();
    } else {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ndk::ScopedAStatus Vibrator::getFrequencyMinimum(float *freqMinimumHz) {
    int32_t capabilities;
    Vibrator::getCapabilities(&capabilities);
    if (capabilities & IVibrator::CAP_FREQUENCY_CONTROL) {
        *freqMinimumHz = PWLE_FREQUENCY_MIN_HZ;
        return ndk::ScopedAStatus::ok();
    } else {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ndk::ScopedAStatus Vibrator::getBandwidthAmplitudeMap(std::vector<float> *_aidl_return) {
    // TODO(b/170919640): complete implementation
    int32_t capabilities;
    Vibrator::getCapabilities(&capabilities);
    if (capabilities & IVibrator::CAP_FREQUENCY_CONTROL) {
        std::vector<float> bandwidthAmplitudeMap(PWLE_BW_MAP_SIZE, 1.0);
        *_aidl_return = bandwidthAmplitudeMap;
        return ndk::ScopedAStatus::ok();
    } else {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ndk::ScopedAStatus Vibrator::getPwlePrimitiveDurationMax(int32_t *durationMs) {
    int32_t capabilities;
    Vibrator::getCapabilities(&capabilities);
    if (capabilities & IVibrator::CAP_COMPOSE_PWLE_EFFECTS) {
        *durationMs = COMPOSE_PWLE_PRIMITIVE_DURATION_MAX_MS;
        return ndk::ScopedAStatus::ok();
    } else {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ndk::ScopedAStatus Vibrator::getPwleCompositionSizeMax(int32_t *maxSize) {
    int32_t capabilities;
    Vibrator::getCapabilities(&capabilities);
    if (capabilities & IVibrator::CAP_COMPOSE_PWLE_EFFECTS) {
        *maxSize = COMPOSE_PWLE_SIZE_MAX_DEFAULT;
        return ndk::ScopedAStatus::ok();
    } else {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ndk::ScopedAStatus Vibrator::getSupportedBraking(std::vector<Braking> *supported) {
    int32_t capabilities;
    Vibrator::getCapabilities(&capabilities);
    if (capabilities & IVibrator::CAP_COMPOSE_PWLE_EFFECTS) {
        *supported = {
                Braking::NONE,
                Braking::CLAB,
        };
        return ndk::ScopedAStatus::ok();
    } else {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ndk::ScopedAStatus Vibrator::setPwle(const std::string &pwleQueue) {
    if (!mHwApi->setPwle(pwleQueue)) {
        ALOGE("Failed to write \"%s\" to pwle (%d): %s", pwleQueue.c_str(), errno, strerror(errno));
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    return ndk::ScopedAStatus::ok();
}

static void resetPreviousEndAmplitudeEndFrequency(float *prevEndAmplitude,
                                                  float *prevEndFrequency) {
    const float reset = -1.0;
    *prevEndAmplitude = reset;
    *prevEndFrequency = reset;
}

static void incrementIndex(int *index) {
    *index += 1;
}

static void constructActiveDefaults(std::ostringstream &pwleBuilder, const int &segmentIdx) {
    pwleBuilder << ",C" << segmentIdx << ":1";
    pwleBuilder << ",B" << segmentIdx << ":0";
    pwleBuilder << ",AR" << segmentIdx << ":0";
    pwleBuilder << ",V" << segmentIdx << ":0";
}

static void constructActiveSegment(std::ostringstream &pwleBuilder, const int &segmentIdx,
                                   int duration, float amplitude, float frequency) {
    pwleBuilder << ",T" << segmentIdx << ":" << duration;
    pwleBuilder << ",L" << segmentIdx << ":" << amplitude;
    pwleBuilder << ",F" << segmentIdx << ":" << frequency;
    constructActiveDefaults(pwleBuilder, segmentIdx);
}

static void constructBrakingSegment(std::ostringstream &pwleBuilder, const int &segmentIdx,
                                    int duration, Braking brakingType) {
    pwleBuilder << ",T" << segmentIdx << ":" << duration;
    pwleBuilder << ",L" << segmentIdx << ":" << 0;
    pwleBuilder << ",F" << segmentIdx << ":" << PWLE_FREQUENCY_MIN_HZ;
    pwleBuilder << ",C" << segmentIdx << ":0";
    pwleBuilder << ",B" << segmentIdx << ":"
                << static_cast<std::underlying_type<Braking>::type>(brakingType);
    pwleBuilder << ",AR" << segmentIdx << ":0";
    pwleBuilder << ",V" << segmentIdx << ":0";
}

ndk::ScopedAStatus Vibrator::composePwle(const std::vector<PrimitivePwle> &composite,
                                         const std::shared_ptr<IVibratorCallback> &callback) {
    ATRACE_NAME("Vibrator::composePwle");
    std::ostringstream pwleBuilder;
    std::string pwleQueue;
    int32_t capabilities;
    Vibrator::getCapabilities(&capabilities);
    if ((capabilities & IVibrator::CAP_COMPOSE_PWLE_EFFECTS) == 0) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    if (composite.size() <= 0 || composite.size() > compositionSizeMax) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    float prevEndAmplitude;
    float prevEndFrequency;
    resetPreviousEndAmplitudeEndFrequency(&prevEndAmplitude, &prevEndFrequency);

    int segmentIdx = 0;
    uint32_t totalDuration = 0;

    pwleBuilder << "S:0,WF:4,RP:0,WT:0";

    for (auto &e : composite) {
        switch (e.getTag()) {
            case PrimitivePwle::active: {
                auto active = e.get<PrimitivePwle::active>();
                if (active.duration < 0 ||
                    active.duration > COMPOSE_PWLE_PRIMITIVE_DURATION_MAX_MS) {
                    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
                }
                if (active.startAmplitude < PWLE_LEVEL_MIN ||
                    active.startAmplitude > PWLE_LEVEL_MAX ||
                    active.endAmplitude < PWLE_LEVEL_MIN || active.endAmplitude > PWLE_LEVEL_MAX) {
                    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
                }
                if (active.startFrequency < PWLE_FREQUENCY_MIN_HZ ||
                    active.startFrequency > PWLE_FREQUENCY_MAX_HZ ||
                    active.endFrequency < PWLE_FREQUENCY_MIN_HZ ||
                    active.endFrequency > PWLE_FREQUENCY_MAX_HZ) {
                    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
                }

                if (!((active.startAmplitude == prevEndAmplitude) &&
                      (active.startFrequency == prevEndFrequency))) {
                    constructActiveSegment(pwleBuilder, segmentIdx, 0, active.startAmplitude,
                                           active.startFrequency);
                    incrementIndex(&segmentIdx);
                }

                constructActiveSegment(pwleBuilder, segmentIdx, active.duration,
                                       active.endAmplitude, active.endFrequency);
                incrementIndex(&segmentIdx);

                prevEndAmplitude = active.endAmplitude;
                prevEndFrequency = active.endFrequency;
                totalDuration += active.duration;
                break;
            }
            case PrimitivePwle::braking: {
                auto braking = e.get<PrimitivePwle::braking>();
                if (braking.braking > Braking::CLAB) {
                    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
                }
                if (braking.duration > COMPOSE_PWLE_PRIMITIVE_DURATION_MAX_MS) {
                    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
                }

                constructBrakingSegment(pwleBuilder, segmentIdx, 0, braking.braking);
                incrementIndex(&segmentIdx);

                constructBrakingSegment(pwleBuilder, segmentIdx, braking.duration, braking.braking);
                incrementIndex(&segmentIdx);

                resetPreviousEndAmplitudeEndFrequency(&prevEndAmplitude, &prevEndFrequency);
                totalDuration += braking.duration;
                break;
            }
        }
    }

    pwleQueue = pwleBuilder.str();
    ALOGD("composePwle queue: (%s)", pwleQueue.c_str());

    ndk::ScopedAStatus status = setPwle(pwleQueue);
    if (!status.isOk()) {
        ALOGE("Failed to write pwle queue");
        return status;
    }

    // mHwApi->setEffectIndex(WAVEFORM_UNSAVED_TRIGGER_QUEUE_INDEX);

    totalDuration += MAX_COLD_START_LATENCY_MS;
    // mHwApi->setDuration(MAX_TIME_MS);

    // mHwApi->setActivate(1);

    mAsyncHandle = std::async(&Vibrator::waitForComplete, this, callback);

    return ndk::ScopedAStatus::ok();
}

bool Vibrator::isUnderExternalControl() {
    return mIsUnderExternalControl;
}

binder_status_t Vibrator::dump(int fd, const char **args, uint32_t numArgs) {
    if (fd < 0) {
        ALOGE("Called debug() with invalid fd.");
        return STATUS_OK;
    }

    (void)args;
    (void)numArgs;

    dprintf(fd, "AIDL:\n");

    dprintf(fd, "  F0 Offset: %" PRIu32 "\n", mF0Offset);

    dprintf(fd, "  Voltage Levels:\n");
    dprintf(fd, "    Tick Effect Min: %" PRIu32 " Max: %" PRIu32 "\n", mTickEffectVol[0],
            mTickEffectVol[1]);
    dprintf(fd, "    Click Effect Min: %" PRIu32 " Max: %" PRIu32 "\n", mClickEffectVol[0],
            mClickEffectVol[1]);
    dprintf(fd, "    Long Effect Min: %" PRIu32 " Max: %" PRIu32 "\n", mLongEffectVol[0],
            mLongEffectVol[1]);

    dprintf(fd, "  FF effect:\n");
    dprintf(fd, "    Physical waveform:\n");
    dprintf(fd, "\tId\tIndex\tt   ->\tt'\n");
    for (uint8_t effectId = 0; effectId < WAVEFORM_MAX_PHYSICAL_INDEX; effectId++) {
        dprintf(fd, "\t%d\t%d\t%d\t%d\n", mFfEffects[effectId].id,
                mFfEffects[effectId].u.periodic.custom_data[1], mEffectDurations[effectId],
                mFfEffects[effectId].replay.length);
    }
    dprintf(fd, "    OWT waveform:\n");
    dprintf(fd, "\tId\tBytes\tData\n");
    for (uint8_t effectId = WAVEFORM_MAX_PHYSICAL_INDEX; effectId < WAVEFORM_MAX_INDEX;
         effectId++) {
        uint32_t numBytes = mFfEffects[effectId].u.periodic.custom_len * 2;
        std::stringstream ss;
        ss << " ";
        for (int i = 0; i < numBytes; i++) {
            ss << std::uppercase << std::setfill('0') << std::setw(2) << std::hex
               << (uint16_t)(*(
                          reinterpret_cast<uint8_t *>(mFfEffects[effectId].u.periodic.custom_data) +
                          i))
               << " ";
        }
        dprintf(fd, "\t%d\t%d\t{%s}\n", mFfEffects[effectId].id, numBytes, ss.str().c_str());
    }

    dprintf(fd, "\n");
    dprintf(fd, "\n");

    mHwApi->debug(fd);

    dprintf(fd, "\n");

    mHwCal->debug(fd);

    fsync(fd);
    return STATUS_OK;
}

bool Vibrator::findHapticAlsaDevice(int *card, int *device) {
    std::string line;
    std::ifstream myfile(PROC_SND_PCM);
    if (myfile.is_open()) {
        while (getline(myfile, line)) {
            if (line.find(HAPTIC_PCM_DEVICE_SYMBOL) != std::string::npos) {
                std::stringstream ss(line);
                std::string currentToken;
                std::getline(ss, currentToken, ':');
                sscanf(currentToken.c_str(), "%d-%d", card, device);
                return true;
            }
        }
        myfile.close();
    } else {
        ALOGE("Failed to read file: %s", PROC_SND_PCM);
    }
    return false;
}

bool Vibrator::hasHapticAlsaDevice() {
    // We need to call findHapticAlsaDevice once only. Calling in the
    // constructor is too early in the boot process and the pcm file contents
    // are empty. Hence we make the call here once only right before we need to.
    static bool configHapticAlsaDeviceDone = false;
    if (!configHapticAlsaDeviceDone) {
        if (findHapticAlsaDevice(&mCard, &mDevice)) {
            mHasHapticAlsaDevice = true;
            configHapticAlsaDeviceDone = true;
        } else {
            ALOGE("Haptic ALSA device not supported");
        }
    }
    return mHasHapticAlsaDevice;
}

bool Vibrator::enableHapticPcmAmp(struct pcm **haptic_pcm, bool enable, int card, int device) {
    int ret = 0;

    if (enable) {
        *haptic_pcm = pcm_open(card, device, PCM_OUT, &haptic_nohost_config);
        if (!pcm_is_ready(*haptic_pcm)) {
            ALOGE("cannot open pcm_out driver: %s", pcm_get_error(*haptic_pcm));
            goto fail;
        }

        ret = pcm_prepare(*haptic_pcm);
        if (ret < 0) {
            ALOGE("cannot prepare haptic_pcm: %s", pcm_get_error(*haptic_pcm));
            goto fail;
        }

        ret = pcm_start(*haptic_pcm);
        if (ret < 0) {
            ALOGE("cannot start haptic_pcm: %s", pcm_get_error(*haptic_pcm));
            goto fail;
        }

        return true;
    } else {
        if (*haptic_pcm) {
            pcm_close(*haptic_pcm);
            *haptic_pcm = NULL;
        }
        return true;
    }

fail:
    pcm_close(*haptic_pcm);
    *haptic_pcm = NULL;
    return false;
}

ndk::ScopedAStatus Vibrator::getSimpleDetails(Effect effect, EffectStrength strength,
                                              uint32_t *outEffectIndex, uint32_t *outTimeMs,
                                              uint32_t *outVolLevel) {
    uint32_t effectIndex;
    uint32_t timeMs;
    float intensity;
    uint32_t volLevel;
    switch (strength) {
        case EffectStrength::LIGHT:
            intensity = 0.5f;
            break;
        case EffectStrength::MEDIUM:
            intensity = 0.7f;
            break;
        case EffectStrength::STRONG:
            intensity = 1.0f;
            break;
        default:
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    switch (effect) {
        case Effect::TEXTURE_TICK:
            effectIndex = WAVEFORM_LIGHT_TICK_INDEX;
            intensity *= 0.5f;
            break;
        case Effect::TICK:
            effectIndex = WAVEFORM_CLICK_INDEX;
            intensity *= 0.5f;
            break;
        case Effect::CLICK:
            effectIndex = WAVEFORM_CLICK_INDEX;
            intensity *= 0.7f;
            break;
        case Effect::HEAVY_CLICK:
            effectIndex = WAVEFORM_CLICK_INDEX;
            intensity *= 1.0f;
            break;
        default:
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    volLevel = intensityToVolLevel(intensity, effectIndex);
    timeMs = mEffectDurations[effectIndex] + MAX_COLD_START_LATENCY_MS;

    *outEffectIndex = effectIndex;
    *outTimeMs = timeMs;
    *outVolLevel = volLevel;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompoundDetails(Effect effect, EffectStrength strength,
                                                uint32_t *outTimeMs, dspmem_chunk *outCh) {
    ndk::ScopedAStatus status;
    uint32_t timeMs = 0;
    uint32_t thisEffectIndex;
    uint32_t thisTimeMs;
    uint32_t thisVolLevel;
    switch (effect) {
        case Effect::DOUBLE_CLICK:
            dspmem_chunk_write(outCh, 8, 0); /* Padding */
            dspmem_chunk_write(outCh, 8, 2); /* nsections */
            dspmem_chunk_write(outCh, 8, 0); /* repeat */

            status = getSimpleDetails(Effect::CLICK, strength, &thisEffectIndex, &thisTimeMs,
                                      &thisVolLevel);
            if (!status.isOk()) {
                return status;
            }
            timeMs += thisTimeMs;

            dspmem_chunk_write(outCh, 8, (uint8_t)(0xFF & thisVolLevel));    /* amplitude */
            dspmem_chunk_write(outCh, 8, (uint8_t)(0xFF & thisEffectIndex)); /* index */
            dspmem_chunk_write(outCh, 8, 0);                                 /* repeat */
            dspmem_chunk_write(outCh, 8, 0);                                 /* flags */
            dspmem_chunk_write(outCh, 16,
                               (uint16_t)(0xFFFF & WAVEFORM_DOUBLE_CLICK_SILENCE_MS)); /* delay */

            timeMs += WAVEFORM_DOUBLE_CLICK_SILENCE_MS + MAX_PAUSE_TIMING_ERROR_MS;

            status = getSimpleDetails(Effect::HEAVY_CLICK, strength, &thisEffectIndex, &thisTimeMs,
                                      &thisVolLevel);
            if (!status.isOk()) {
                return status;
            }
            timeMs += thisTimeMs;

            dspmem_chunk_write(outCh, 8, (uint8_t)(0xFF & thisVolLevel));    /* amplitude */
            dspmem_chunk_write(outCh, 8, (uint8_t)(0xFF & thisEffectIndex)); /* index */
            dspmem_chunk_write(outCh, 8, 0);                                 /* repeat */
            dspmem_chunk_write(outCh, 8, 0);                                 /* flags */
            dspmem_chunk_write(outCh, 16, 0);                                /* delay */
            dspmem_chunk_flush(outCh);

            break;
        default:
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    *outTimeMs = timeMs;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDetails(CompositePrimitive primitive,
                                                 uint32_t *outEffectIndex) {
    uint32_t effectIndex;
    uint32_t primitiveBit = 1 << int32_t(primitive);
    if ((primitiveBit & mSupportedPrimitivesBits) == 0x0) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    switch (primitive) {
        case CompositePrimitive::NOOP:
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        case CompositePrimitive::CLICK:
            effectIndex = WAVEFORM_CLICK_INDEX;
            break;
        case CompositePrimitive::THUD:
            effectIndex = WAVEFORM_THUD_INDEX;
            break;
        case CompositePrimitive::SPIN:
            effectIndex = WAVEFORM_SPIN_INDEX;
            break;
        case CompositePrimitive::QUICK_RISE:
            effectIndex = WAVEFORM_QUICK_RISE_INDEX;
            break;
        case CompositePrimitive::SLOW_RISE:
            effectIndex = WAVEFORM_SLOW_RISE_INDEX;
            break;
        case CompositePrimitive::QUICK_FALL:
            effectIndex = WAVEFORM_QUICK_FALL_INDEX;
            break;
        case CompositePrimitive::LIGHT_TICK:
            effectIndex = WAVEFORM_LIGHT_TICK_INDEX;
            break;
        case CompositePrimitive::LOW_TICK:
            effectIndex = WAVEFORM_LOW_TICK_INDEX;
            break;
        default:
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    *outEffectIndex = effectIndex;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::uploadOwtEffect(uint8_t *owtData, uint32_t numBytes,
                                             uint32_t *outEffectIndex) {
    if (owtData == nullptr) {
        ALOGE("Invalid waveform bank");
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    bool isPwle = (*reinterpret_cast<uint16_t *>(owtData) != 0x0000);
    WaveformIndex targetId = isPwle ? WAVEFORM_PWLE : WAVEFORM_COMPOSE;

    /* Erase the created OWT waveform. */
    bool isCreated = (mFfEffects[targetId].id != -1);
    if (isCreated && ioctl(mInputFd, EVIOCRMFF, mFfEffects[targetId].id) < 0) {
        ALOGW("Failed to erase effect %d(%d) (%d): %s", targetId, mFfEffects[targetId].id, errno,
              strerror(errno));
        mFfEffects[targetId].id = -1;
    }

    uint32_t freeBytes;
    mHwApi->getOwtFreeSpace(&freeBytes);
    if (numBytes > freeBytes) {
        ALOGE("Effect %d length: %d > %d!", targetId, numBytes, freeBytes);
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    /* Create a new OWT waveform to update the PWLE or composite effect. */
    mFfEffects[targetId].id = -1;                              /* New Effect */
    mFfEffects[targetId].u.periodic.custom_len = numBytes / 2; /* # of 16-bit elements */
    delete[](mFfEffects[targetId].u.periodic.custom_data);
    mFfEffects[targetId].u.periodic.custom_data =
            new int16_t[mFfEffects[targetId].u.periodic.custom_len]{0x0000};
    if (mFfEffects[targetId].u.periodic.custom_data == nullptr) {
        ALOGE("Failed to allocate memory for custom data\n");
        return ndk::ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    }
    memcpy(mFfEffects[targetId].u.periodic.custom_data, owtData, numBytes);

    if (ioctl(mInputFd, EVIOCSFF, &mFfEffects[targetId]) < 0) {
        ALOGE("Failed to upload effect %d (%d): %s", targetId, errno, strerror(errno));
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    *outEffectIndex = mFfEffects[targetId].id;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::performEffect(Effect effect, EffectStrength strength,
                                           const std::shared_ptr<IVibratorCallback> &callback,
                                           int32_t *outTimeMs) {
    ndk::ScopedAStatus status;
    uint32_t effectIndex;
    uint32_t timeMs = 0;
    uint32_t volLevel;
    dspmem_chunk *ch = nullptr;

    switch (effect) {
        case Effect::TEXTURE_TICK:
            // fall-through
        case Effect::TICK:
            // fall-through
        case Effect::CLICK:
            // fall-through
        case Effect::HEAVY_CLICK:
            status = getSimpleDetails(effect, strength, &effectIndex, &timeMs, &volLevel);
            break;
        case Effect::DOUBLE_CLICK:
            ch = dspmem_chunk_create(new uint8_t[FF_CUSTOM_DATA_LEN_MAX_COMP]{0x00},
                                     FF_CUSTOM_DATA_LEN_MAX_COMP);
            status = getCompoundDetails(effect, strength, &timeMs, ch);
            break;
        default:
            status = ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
            break;
    }
    if (!status.isOk()) {
        goto exit;
    }

    status = performEffect(static_cast<uint16_t>(effectIndex), volLevel, ch, callback);

exit:
    *outTimeMs = timeMs;
    return status;
}

ndk::ScopedAStatus Vibrator::performEffect(uint32_t effectIndex, uint32_t volLevel,
                                           dspmem_chunk *ch,
                                           const std::shared_ptr<IVibratorCallback> &callback) {
    if (ch) {
        ndk::ScopedAStatus status = uploadOwtEffect(ch->head, dspmem_chunk_bytes(ch), &effectIndex);
        delete ch;
        if (!status.isOk()) {
            return status;
        }
        setEffectAmplitude(VOLTAGE_SCALE_MAX, VOLTAGE_SCALE_MAX);
    } else {
        setEffectAmplitude(volLevel, VOLTAGE_SCALE_MAX);
    }

    return on(MAX_TIME_MS, effectIndex, callback);
}

void Vibrator::waitForComplete(std::shared_ptr<IVibratorCallback> &&callback) {
    if (!mHwApi->pollVibeState("Vibe state: Haptic\n", POLLING_TIMEOUT)) {
        ALOGE("Fail to get state \"Haptic\"");
    }
    mHwApi->pollVibeState("Vibe state: Stopped\n");

    if (callback) {
        auto ret = callback->onComplete();
        if (!ret.isOk()) {
            ALOGE("Failed completion callback: %d", ret.getExceptionCode());
        }
    }
}

uint32_t Vibrator::intensityToVolLevel(float intensity, uint32_t effectIndex) {
    uint32_t volLevel;
    auto calc = [](float intst, std::array<uint32_t, 2> v) -> uint32_t {
        return std::lround(intst * (v[1] - v[0])) + v[0];
    };

    switch (effectIndex) {
        case WAVEFORM_LIGHT_TICK_INDEX:
            volLevel = calc(intensity, mTickEffectVol);
            break;
        case WAVEFORM_QUICK_RISE_INDEX:
            // fall-through
        case WAVEFORM_QUICK_FALL_INDEX:
            volLevel = calc(intensity, mLongEffectVol);
            break;
        case WAVEFORM_CLICK_INDEX:
            // fall-through
        case WAVEFORM_THUD_INDEX:
            // fall-through
        case WAVEFORM_SPIN_INDEX:
            // fall-through
        case WAVEFORM_SLOW_RISE_INDEX:
            // fall-through
        default:
            volLevel = calc(intensity, mClickEffectVol);
            break;
    }
    return volLevel;
}

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
