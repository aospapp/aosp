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
using aidl::android::hardware::power::stats::PowerStatsEnergyConsumer;

void addDisplay(std::shared_ptr<PowerStats> p) {
    // Add display residency stats
    std::vector<std::string> states = {
        "Off",
        "LP: 1080x2400@30",
        "On: 1080x2400@60",
        "On: 1080x2400@90",
        "HBM: 1080x2400@60",
        "HBM: 1080x2400@90"};

    p->addStateResidencyDataProvider(std::make_unique<DisplayStateResidencyDataProvider>("Display",
            "/sys/class/backlight/panel0-backlight/state",
            states));

    // Add display energy consumer
    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterAndEntityConsumer(p,
            EnergyConsumerType::DISPLAY, "display", {"VSYS_PWR_DISPLAY"}, "Display",
            {{"LP: 1080x2400@30", 1},
             {"On: 1080x2400@60", 2},
             {"On: 1080x2400@90", 3}}));
}

void addGPUGs202(std::shared_ptr<PowerStats> p) {
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
        {"434000", 1931},
        {"471000", 2105},
        {"510000", 2292},
        {"572000", 2528},
        {"633000", 2811},
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

int main() {
    struct stat buffer;

    LOG(INFO) << "Pixel PowerStats HAL AIDL Service is starting.";

    // single thread
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    std::shared_ptr<PowerStats> p = ndk::SharedRefBase::make<PowerStats>();

    setEnergyMeter(p);
    addAoC(p);
    addCPUclusters(p);
    addDisplay(p);
    addSoC(p);
    addGNSS(p);
    addMobileRadio(p);
    addPCIe(p);
    addWlan(p);
    addTPU(p);
    addUfs(p);
    if (!stat("/sys/devices/platform/10970000.hsi2c/i2c-2/i2c-st21nfc/power_stats", &buffer)) {
        addNFC(p, "/sys/devices/platform/10970000.hsi2c/i2c-2/i2c-st21nfc/power_stats");
    } else if (!stat("/sys/devices/platform/10970000.hsi2c/i2c-3/i2c-st21nfc/power_stats", &buffer)) {
        addNFC(p, "/sys/devices/platform/10970000.hsi2c/i2c-3/i2c-st21nfc/power_stats");
    } else if (!stat("/sys/devices/platform/10970000.hsi2c/i2c-4/i2c-st21nfc/power_stats", &buffer)) {
        addNFC(p, "/sys/devices/platform/10970000.hsi2c/i2c-4/i2c-st21nfc/power_stats");
    } else if (!stat("/sys/devices/platform/10970000.hsi2c/i2c-5/i2c-st21nfc/power_stats", &buffer)) {
        addNFC(p, "/sys/devices/platform/10970000.hsi2c/i2c-5/i2c-st21nfc/power_stats");
    } else if (!stat("/sys/devices/platform/10970000.hsi2c/i2c-6/i2c-st21nfc/power_stats", &buffer)) {
        addNFC(p, "/sys/devices/platform/10970000.hsi2c/i2c-6/i2c-st21nfc/power_stats");
    } else if (!stat("/sys/devices/platform/10970000.hsi2c/i2c-7/i2c-st21nfc/power_stats", &buffer)) {
        addNFC(p, "/sys/devices/platform/10970000.hsi2c/i2c-7/i2c-st21nfc/power_stats");
    } else {
        addNFC(p, "/sys/devices/platform/10970000.hsi2c/i2c-8/i2c-st21nfc/power_stats");
    }
    addPowerDomains(p);
    addDevfreq(p);
    addGPUGs202(p);
    addDvfsStats(p);

    const std::string instance = std::string() + PowerStats::descriptor + "/default";
    binder_status_t status = AServiceManager_addService(p->asBinder().get(), instance.c_str());
    LOG_ALWAYS_FATAL_IF(status != STATUS_OK);

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // should not reach
}
