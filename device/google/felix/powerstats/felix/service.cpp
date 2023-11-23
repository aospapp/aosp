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

#define LOG_TAG "android.hardware.power.stats-service.pixel"

#include <dataproviders/DisplayStateResidencyDataProvider.h>
#include <dataproviders/GenericStateResidencyDataProvider.h>
#include <dataproviders/PowerStatsEnergyConsumer.h>
#include <DevfreqStateResidencyDataProvider.h>
#include <Gs201CommonDataProviders.h>
#include <PowerStatsAidl.h>

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <log/log.h>
#include <sys/stat.h>

using aidl::android::hardware::power::stats::DevfreqStateResidencyDataProvider;
using aidl::android::hardware::power::stats::DisplayStateResidencyDataProvider;
using aidl::android::hardware::power::stats::EnergyConsumerType;
using aidl::android::hardware::power::stats::GenericStateResidencyDataProvider;
using aidl::android::hardware::power::stats::PowerStatsEnergyConsumer;

void addDisplay(std::shared_ptr<PowerStats> p) {
    // Add display residency stats for inner display
    std::vector<std::string> inner_states = {
        "Off",
        "LP: 1840x2208@1",
        "LP: 1840x2208@30",
        "On: 1840x2208@1",
        "On: 1840x2208@10",
        "On: 1840x2208@60",
        "On: 1840x2208@120",
        "HBM: 1840x2208@60",
        "HBM: 1840x2208@120"};

    p->addStateResidencyDataProvider(std::make_unique<DisplayStateResidencyDataProvider>(
            "Inner Display",
            "/sys/class/backlight/panel0-backlight/state",
            inner_states));

    // Add display residency stats for outer display
    std::vector<std::string> outer_states = {
        "Off",
        "LP: 1080x2092@30",
        "On: 1080x2092@10",
        "On: 1080x2092@60",
        "On: 1080x2092@120",
        "HBM: 1080x2092@60",
        "HBM: 1080x2092@120"};

    p->addStateResidencyDataProvider(std::make_unique<DisplayStateResidencyDataProvider>(
            "Outer Display",
            "/sys/class/backlight/panel1-backlight/state",
            outer_states));

    // Add display energy consumer
    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterConsumer(
            p,
            EnergyConsumerType::DISPLAY,
            "Display",
            {"VSYS_PWR_DISPLAY"}));// VSYS_PWR_DISPLAY = inner + outer
}

void addUwb(std::shared_ptr<PowerStats> p) {
    // A constant to represent the number of nanoseconds in one millisecond.
    const int NS_TO_MS = 1000000;

    // ACPM stats are reported in nanoseconds. The transform function
    // converts nanoseconds to milliseconds.
    std::function<uint64_t(uint64_t)> uwbNsToMs = [](uint64_t a) { return a / NS_TO_MS; };
    const GenericStateResidencyDataProvider::StateResidencyConfig stateConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "count:",
            .totalTimeSupported = true,
            .totalTimePrefix = "dur ns:",
            .totalTimeTransform = uwbNsToMs,
            .lastEntrySupported = false,
    };

    const std::vector<std::pair<std::string, std::string>> stateHeaders = {
            std::make_pair("Off", "Off state:"),
            std::make_pair("Deep sleep", "Deep sleep state:"),
            std::make_pair("Run", "Run state:"),
            std::make_pair("Idle", "Idle state:"),
            std::make_pair("Tx", "Tx state:"),
            std::make_pair("Rx", "Rx state:"),
    };

    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    cfgs.emplace_back(generateGenericStateResidencyConfigs(stateConfig, stateHeaders),
            "UWB", "");

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/10db0000.spi/spi_master/spi16/spi16.0/uwb/power_stats", cfgs));
}

void addGPU(std::shared_ptr<PowerStats> p) {
    std::map<std::string, int32_t> stateCoeffs;

    // Add GPU state residency
    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "GPU",
            "/sys/devices/platform/28000000.mali"));

    // Add GPU energy consumer
    stateCoeffs = {
        {"202000",  890},
        {"251000", 1102},
        {"302000", 1308},
        {"351000", 1522},
        {"400000", 1772},
        {"471000", 2105},
        {"510000", 2292},
        {"572000", 2528},
        {"701000", 3127},
        {"762000", 3452},
        {"848000", 4044}};

    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterAndAttrConsumer(
            p,
            EnergyConsumerType::OTHER,
            "GPU",
            {"S2S_VDD_G3D", "S8S_VDD_G3D_L2"},
            {{UID_TIME_IN_STATE, "/sys/devices/platform/28000000.mali/uid_time_in_state"}},
            stateCoeffs));
}

std::string getNfcPath() {
    struct stat buffer;
    int size = 128;
    char path[size];
    for (int i = 0; i < 10; i++) {
        std::snprintf(path, size,
                "/sys/devices/platform/10970000.hsi2c/i2c-%d/i2c-st21nfc/power_stats", i);
        if (!stat(path, &buffer)) break;
    }
    return std::string(path);
}

int main() {
    LOG(INFO) << "Pixel PowerStats HAL AIDL Service is starting.";

    // single thread
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    std::shared_ptr<PowerStats> p = ndk::SharedRefBase::make<PowerStats>();

    setEnergyMeter(p);
    addAoC(p);
    addPixelStateResidencyDataProvider(p);
    addCPUclusters(p);
    addDisplay(p);
    addSoC(p);
    addGNSS(p);
    addMobileRadio(p);
    addPCIe(p);
    addWifi(p);
    addTPU(p);
    addUfs(p);
    addNFC(p, getNfcPath());
    addUwb(p);
    addPowerDomains(p);
    addDevfreq(p);
    addGPU(p);
    addDvfsStats(p);

    const std::string instance = std::string() + PowerStats::descriptor + "/default";
    binder_status_t status = AServiceManager_addService(p->asBinder().get(), instance.c_str());
    LOG_ALWAYS_FATAL_IF(status != STATUS_OK);

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // should not reach
}
