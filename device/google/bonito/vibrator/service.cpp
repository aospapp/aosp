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
#define LOG_TAG "android.hardware.vibrator@1.2-service.bonito"

#include <android/hardware/vibrator/1.2/IVibrator.h>
#include <cutils/properties.h>
#include <hidl/HidlSupport.h>
#include <hidl/HidlTransportSupport.h>
#include <utils/Errors.h>
#include <utils/StrongPointer.h>

#include "Vibrator.h"

using android::hardware::configureRpcThreadpool;
using android::hardware::joinRpcThreadpool;
using android::hardware::vibrator::V1_2::IVibrator;
using android::hardware::vibrator::V1_2::implementation::Vibrator;
using namespace android;

// Refer to Documentation/ABI/testing/sysfs-class-led-driver-drv2624
// kernel documentation on the detail usages for ABIs below
static constexpr char ACTIVATE_PATH[] = "/sys/class/leds/vibrator/activate";
static constexpr char DURATION_PATH[] = "/sys/class/leds/vibrator/duration";
static constexpr char STATE_PATH[] = "/sys/class/leds/vibrator/state";
static constexpr char RTP_INPUT_PATH[] = "/sys/class/leds/vibrator/device/rtp_input";
static constexpr char MODE_PATH[] = "/sys/class/leds/vibrator/device/mode";
static constexpr char SEQUENCER_PATH[] = "/sys/class/leds/vibrator/device/set_sequencer";
static constexpr char SCALE_PATH[] = "/sys/class/leds/vibrator/device/scale";
static constexpr char CTRL_LOOP_PATH[] = "/sys/class/leds/vibrator/device/ctrl_loop";
static constexpr char LP_TRIGGER_PATH[] = "/sys/class/leds/vibrator/device/lp_trigger_effect";
static constexpr char LRA_WAVE_SHAPE_PATH[] = "/sys/class/leds/vibrator/device/lra_wave_shape";
static constexpr char OD_CLAMP_PATH[] = "/sys/class/leds/vibrator/device/od_clamp";

// File path to the calibration file
static constexpr char CALIBRATION_FILEPATH[] = "/persist/haptics/drv2624.cal";

// Kernel ABIs for updating the calibration data
static constexpr char AUTOCAL_CONFIG[] = "autocal";
static constexpr char LRA_PERIOD_CONFIG[] = "lra_period";
static constexpr char AUTOCAL_FILEPATH[] = "/sys/class/leds/vibrator/device/autocal";
static constexpr char OL_LRA_PERIOD_FILEPATH[] = "/sys/class/leds/vibrator/device/ol_lra_period";

// Set a default lra period in case there is no calibration file
static constexpr uint32_t DEFAULT_LRA_PERIOD = 262;
static constexpr uint32_t DEFAULT_FREQUENCY_SHIFT = 10;

static std::uint32_t freqPeriodFormula(std::uint32_t in) {
    return 1000000000 / (24615 * in);
}

static std::string trim(const std::string &str, const std::string &whitespace = " \t") {
    const auto str_begin = str.find_first_not_of(whitespace);
    if (str_begin == std::string::npos) {
        return "";
    }

    const auto str_end = str.find_last_not_of(whitespace);
    const auto str_range = str_end - str_begin + 1;

    return str.substr(str_begin, str_range);
}

static bool loadCalibrationData(std::uint32_t &short_lra_period, std::uint32_t &long_lra_period) {
    std::map<std::string, std::string> config_data;

    std::ofstream autocal{AUTOCAL_FILEPATH};
    if (!autocal) {
        int error = errno;
        ALOGE("Failed to open %s (%d): %s", AUTOCAL_FILEPATH, error, strerror(error));
        return false;
    }

    std::ofstream ol_lra_period{OL_LRA_PERIOD_FILEPATH};
    if (!ol_lra_period) {
        int error = errno;
        ALOGE("Failed to open %s (%d): %s", OL_LRA_PERIOD_FILEPATH, error, strerror(error));
        return false;
    }

    std::ifstream cal_data{CALIBRATION_FILEPATH};
    if (!cal_data) {
        int error = errno;
        ALOGE("Failed to open %s (%d): %s", CALIBRATION_FILEPATH, error, strerror(error));
        return false;
    }

    std::string line;

    while (std::getline(cal_data, line)) {
        if (line.empty() || line[0] == '#') {
            continue;
        }
        std::istringstream is_line(line);
        std::string key;
        if (std::getline(is_line, key, ':')) {
            std::string value;

            if (std::getline(is_line, value)) {
                config_data[trim(key)] = trim(value);
            }
        }
    }

    if (config_data.find(AUTOCAL_CONFIG) != config_data.end()) {
        autocal << config_data[AUTOCAL_CONFIG] << std::endl;
    }

    if (config_data.find(LRA_PERIOD_CONFIG) != config_data.end()) {
        uint32_t thisFrequency;
        uint32_t thisPeriod;
        ol_lra_period << config_data[LRA_PERIOD_CONFIG] << std::endl;
        thisPeriod = std::stoul(config_data[LRA_PERIOD_CONFIG]);
        short_lra_period = thisPeriod;
        // 1. Change long lra period to frequency
        // 2. Get frequency': subtract the frequency shift from the frequency
        // 3. Get final long lra period after put the frequency' to formula
        thisFrequency =
            freqPeriodFormula(thisPeriod) -
            property_get_int32("ro.vibrator.hal.long.frequency.shift", DEFAULT_FREQUENCY_SHIFT);
        long_lra_period = freqPeriodFormula(thisFrequency);
    }

    return true;
}

status_t registerVibratorService() {
    // Calibration data: lra period 262(i.e. 155Hz)
    std::uint32_t short_lra_period(DEFAULT_LRA_PERIOD);
    std::uint32_t long_lra_period(DEFAULT_LRA_PERIOD);
    Vibrator::HwApi hwapi;

    // ostreams below are required
    hwapi.activate.open(ACTIVATE_PATH);
    if (!hwapi.activate) {
        int error = errno;
        ALOGE("Failed to open %s (%d): %s", ACTIVATE_PATH, error, strerror(error));
        return -error;
    }

    hwapi.duration.open(DURATION_PATH);
    if (!hwapi.duration) {
        int error = errno;
        ALOGE("Failed to open %s (%d): %s", DURATION_PATH, error, strerror(error));
        return -error;
    }

    hwapi.state.open(STATE_PATH);
    if (!hwapi.state) {
        int error = errno;
        ALOGE("Failed to open %s (%d): %s", STATE_PATH, error, strerror(error));
        return -error;
    }

    hwapi.state << 1 << std::endl;
    if (!hwapi.state) {
        int error = errno;
        ALOGE("Failed to set state (%d): %s", errno, strerror(errno));
        return -error;
    }

    // ostreams below are optional
    hwapi.rtpInput.open(RTP_INPUT_PATH);
    if (!hwapi.rtpInput) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", RTP_INPUT_PATH, error, strerror(error));
    }

    hwapi.mode.open(MODE_PATH);
    if (!hwapi.mode) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", MODE_PATH, error, strerror(error));
    }

    hwapi.sequencer.open(SEQUENCER_PATH);
    if (!hwapi.sequencer) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", SEQUENCER_PATH, error, strerror(error));
    }

    hwapi.scale.open(SCALE_PATH);
    if (!hwapi.scale) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", SCALE_PATH, error, strerror(error));
    }

    hwapi.ctrlLoop.open(CTRL_LOOP_PATH);
    if (!hwapi.ctrlLoop) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", CTRL_LOOP_PATH, error, strerror(error));
    }

    hwapi.lpTriggerEffect.open(LP_TRIGGER_PATH);
    if (!hwapi.lpTriggerEffect) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", LP_TRIGGER_PATH, error, strerror(error));
    }

    hwapi.lraWaveShape.open(LRA_WAVE_SHAPE_PATH);
    if (!hwapi.lraWaveShape) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", LRA_WAVE_SHAPE_PATH, error, strerror(error));
    }

    hwapi.odClamp.open(OD_CLAMP_PATH);
    if (!hwapi.odClamp) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", OD_CLAMP_PATH, error, strerror(error));
    }

    hwapi.olLraPeriod.open(OL_LRA_PERIOD_FILEPATH);
    if (!hwapi.olLraPeriod) {
        int error = errno;
        ALOGW("Failed to open %s (%d): %s", OL_LRA_PERIOD_FILEPATH, error, strerror(error));
    }

    if (!loadCalibrationData(short_lra_period, long_lra_period)) {
        ALOGW("Failed load calibration data");
    }

    sp<IVibrator> vibrator = new Vibrator(std::move(hwapi), short_lra_period, long_lra_period);

    return vibrator->registerAsService();
}

int main() {
    configureRpcThreadpool(1, true);
    status_t status = registerVibratorService();

    if (status != OK) {
        return status;
    }

    joinRpcThreadpool();
}
