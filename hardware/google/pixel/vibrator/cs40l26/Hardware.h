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

#include "HardwareBase.h"
#include "Vibrator.h"

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

class HwApi : public Vibrator::HwApi, private HwApiBase {
  public:
    HwApi() {
        open("calibration/f0_stored", &mF0);
        open("default/f0_offset", &mF0Offset);
        open("calibration/redc_stored", &mRedc);
        open("calibration/q_stored", &mQ);
        open("default/vibe_state", &mVibeState);
        open("default/num_waves", &mEffectCount);
        open("default/owt_free_space", &mOwtFreeSpace);
        open("default/f0_comp_enable", &mF0CompEnable);
        open("default/redc_comp_enable", &mRedcCompEnable);
        open("default/delay_before_stop_playback_us", &mMinOnOffInterval);
    }

    bool setF0(std::string value) override { return set(value, &mF0); }
    bool setF0Offset(uint32_t value) override { return set(value, &mF0Offset); }
    bool setRedc(std::string value) override { return set(value, &mRedc); }
    bool setQ(std::string value) override { return set(value, &mQ); }
    bool getEffectCount(uint32_t *value) override { return get(value, &mEffectCount); }
    bool pollVibeState(uint32_t value, int32_t timeoutMs) override {
        return poll(value, &mVibeState, timeoutMs);
    }
    bool hasOwtFreeSpace() override { return has(mOwtFreeSpace); }
    bool getOwtFreeSpace(uint32_t *value) override { return get(value, &mOwtFreeSpace); }
    bool setF0CompEnable(bool value) override { return set(value, &mF0CompEnable); }
    bool setRedcCompEnable(bool value) override { return set(value, &mRedcCompEnable); }
    bool setMinOnOffInterval(uint32_t value) override { return set(value, &mMinOnOffInterval); }
    void debug(int fd) override { HwApiBase::debug(fd); }

  private:
    std::ofstream mF0;
    std::ofstream mF0Offset;
    std::ofstream mRedc;
    std::ofstream mQ;
    std::ifstream mEffectCount;
    std::ifstream mVibeState;
    std::ifstream mOwtFreeSpace;
    std::ofstream mF0CompEnable;
    std::ofstream mRedcCompEnable;
    std::ofstream mMinOnOffInterval;
};

class HwCal : public Vibrator::HwCal, private HwCalBase {
  private:
    static constexpr char VERSION[] = "version";
    static constexpr char F0_CONFIG[] = "f0_measured";
    static constexpr char REDC_CONFIG[] = "redc_measured";
    static constexpr char Q_CONFIG[] = "q_measured";
    static constexpr char TICK_VOLTAGES_CONFIG[] = "v_tick";
    static constexpr char CLICK_VOLTAGES_CONFIG[] = "v_click";
    static constexpr char LONG_VOLTAGES_CONFIG[] = "v_long";

    static constexpr uint32_t VERSION_DEFAULT = 2;
    static constexpr int32_t DEFAULT_FREQUENCY_SHIFT = 0;
    static constexpr std::array<uint32_t, 2> V_TICK_DEFAULT = {1, 100};
    static constexpr std::array<uint32_t, 2> V_CTICK_DEFAULT = {1, 100};
    static constexpr std::array<uint32_t, 2> V_LONG_DEFAULT = {1, 100};

  public:
    HwCal() {}

    bool getVersion(uint32_t *value) override {
        if (getPersist(VERSION, value)) {
            return true;
        }
        *value = VERSION_DEFAULT;
        return true;
    }
    bool getLongFrequencyShift(int32_t *value) override {
        return getProperty("long.frequency.shift", value, DEFAULT_FREQUENCY_SHIFT);
    }
    bool getF0(std::string *value) override { return getPersist(F0_CONFIG, value); }
    bool getRedc(std::string *value) override { return getPersist(REDC_CONFIG, value); }
    bool getQ(std::string *value) override { return getPersist(Q_CONFIG, value); }
    bool getTickVolLevels(std::array<uint32_t, 2> *value) override {
        if (getPersist(TICK_VOLTAGES_CONFIG, value)) {
            return true;
        }
        *value = V_TICK_DEFAULT;
        return true;
    }
    bool getClickVolLevels(std::array<uint32_t, 2> *value) override {
        if (getPersist(CLICK_VOLTAGES_CONFIG, value)) {
            return true;
        }
        *value = V_CTICK_DEFAULT;
        return true;
    }
    bool getLongVolLevels(std::array<uint32_t, 2> *value) override {
        if (getPersist(LONG_VOLTAGES_CONFIG, value)) {
            return true;
        }
        *value = V_LONG_DEFAULT;
        return true;
    }
    bool isChirpEnabled() override {
        bool value;
        getProperty("chirp.enabled", &value, false);
        return value;
    }
    bool getSupportedPrimitives(uint32_t *value) override {
        return getProperty("supported_primitives", value, (uint32_t)0);
    }
    void debug(int fd) override { HwCalBase::debug(fd); }
};

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
