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

#include <PowerStatsAidl.h>
#include <Gs101CommonDataProviders.h>
#include "AocStateResidencyDataProvider.h"
#include "DevfreqStateResidencyDataProvider.h"
#include "DvfsStateResidencyDataProvider.h"
#include "UfsStateResidencyDataProvider.h"
#include <dataproviders/GenericStateResidencyDataProvider.h>
#include <dataproviders/IioEnergyMeterDataProvider.h>
#include <dataproviders/PowerStatsEnergyConsumer.h>
#include <dataproviders/PowerStatsEnergyAttribution.h>
#include <dataproviders/PixelStateResidencyDataProvider.h>

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <log/log.h>

using aidl::android::hardware::power::stats::AocStateResidencyDataProvider;
using aidl::android::hardware::power::stats::DevfreqStateResidencyDataProvider;
using aidl::android::hardware::power::stats::DvfsStateResidencyDataProvider;
using aidl::android::hardware::power::stats::UfsStateResidencyDataProvider;
using aidl::android::hardware::power::stats::EnergyConsumerType;
using aidl::android::hardware::power::stats::GenericStateResidencyDataProvider;
using aidl::android::hardware::power::stats::IioEnergyMeterDataProvider;
using aidl::android::hardware::power::stats::PixelStateResidencyDataProvider;
using aidl::android::hardware::power::stats::PowerStatsEnergyConsumer;

constexpr char kBootHwSoCRev[] = "ro.boot.hw.soc.rev";

void addAoC(std::shared_ptr<PowerStats> p) {
    // AoC clock is synced from "libaoc.c"
    static const uint64_t AOC_CLOCK = 4096;
    std::string prefix = "/sys/devices/platform/19000000.aoc/control/";

    // Add AoC cores (a32, ff1, hf0, and hf1)
    std::vector<std::pair<std::string, std::string>> coreIds = {
            {"AoC-A32", prefix + "a32_"},
            {"AoC-FF1", prefix + "ff1_"},
            {"AoC-HF1", prefix + "hf1_"},
            {"AoC-HF0", prefix + "hf0_"},
    };
    std::vector<std::pair<std::string, std::string>> coreStates = {
            {"DWN", "off"}, {"RET", "retention"}, {"WFI", "wfi"}};
    p->addStateResidencyDataProvider(std::make_unique<AocStateResidencyDataProvider>(coreIds,
            coreStates, AOC_CLOCK));

    // Add AoC voltage stats
    std::vector<std::pair<std::string, std::string>> voltageIds = {
            {"AoC-Voltage", prefix + "voltage_"},
    };
    std::vector<std::pair<std::string, std::string>> voltageStates = {{"NOM", "nominal"},
                                                                      {"SUD", "super_underdrive"},
                                                                      {"UUD", "ultra_underdrive"},
                                                                      {"UD", "underdrive"}};
    p->addStateResidencyDataProvider(
            std::make_unique<AocStateResidencyDataProvider>(voltageIds, voltageStates, AOC_CLOCK));

    // Add AoC monitor mode
    std::vector<std::pair<std::string, std::string>> monitorIds = {
            {"AoC", prefix + "monitor_"},
    };
    std::vector<std::pair<std::string, std::string>> monitorStates = {
            {"MON", "mode"},
    };
    p->addStateResidencyDataProvider(
            std::make_unique<AocStateResidencyDataProvider>(monitorIds, monitorStates, AOC_CLOCK));

    // Add AoC restart count
    const GenericStateResidencyDataProvider::StateResidencyConfig restartCountConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "",
            .totalTimeSupported = false,
            .lastEntrySupported = false,
    };
    const std::vector<std::pair<std::string, std::string>> restartCountHeaders = {
            std::make_pair("RESTART", ""),
    };
    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    cfgs.emplace_back(
            generateGenericStateResidencyConfigs(restartCountConfig, restartCountHeaders),
            "AoC-Count", "");
    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/19000000.aoc/restart_count", cfgs));
}

void addDvfsStats(std::shared_ptr<PowerStats> p) {
    // A constant to represent the number of nanoseconds in one millisecond
    const int NS_TO_MS = 1000000;

    std::vector<DvfsStateResidencyDataProvider::Config> cfgs;

    cfgs.push_back({"MIF", {
        std::make_pair("3172MHz", "3172000"),
        std::make_pair("2730MHz", "2730000"),
        std::make_pair("2535MHz", "2535000"),
        std::make_pair("2288MHz", "2288000"),
        std::make_pair("2028MHz", "2028000"),
        std::make_pair("1716MHz", "1716000"),
        std::make_pair("1539MHz", "1539000"),
        std::make_pair("1352MHz", "1352000"),
        std::make_pair("1014MHz", "1014000"),
        std::make_pair("845MHz", "845000"),
        std::make_pair("676MHz", "676000"),
        std::make_pair("546MHz", "546000"),
        std::make_pair("421MHz", "421000"),
        std::make_pair("0MHz", "0"),
    }});

    cfgs.push_back({"CL1", {
        std::make_pair("2466MHz", "2466000"),
        std::make_pair("2393MHz", "2393000"),
        std::make_pair("2348MHz", "2348000"),
        std::make_pair("2253MHz", "2253000"),
        std::make_pair("2130MHz", "2130000"),
        std::make_pair("1999MHz", "1999000"),
        std::make_pair("1836MHz", "1836000"),
        std::make_pair("1663MHz", "1663000"),
        std::make_pair("1491MHz", "1491000"),
        std::make_pair("1328MHz", "1328000"),
        std::make_pair("1197MHz", "1197000"),
        std::make_pair("1024MHz", "1024000"),
        std::make_pair("910MHz", "910000"),
        std::make_pair("799MHz", "799000"),
        std::make_pair("696MHz", "696000"),
        std::make_pair("533MHz", "533000"),
        std::make_pair("400MHz", "400000"),
        std::make_pair("0MHz", "0"),
    }});

    // B0/B1 chips have different DVFS operating points than A0/A1 SoC
    const int socRev = android::base::GetIntProperty(kBootHwSoCRev, 0);
    if (socRev >= 2) {
        cfgs.push_back({"CL0", {
            std::make_pair("2196MHz", "2196000"),
            std::make_pair("2098MHz", "2098000"),
            std::make_pair("2024MHz", "2024000"),
            std::make_pair("1950MHz", "1950000"),
            std::make_pair("1803MHz", "1803000"),
            std::make_pair("1704MHz", "1704000"),
            std::make_pair("1598MHz", "1598000"),
            std::make_pair("1401MHz", "1401000"),
            std::make_pair("1328MHz", "1328000"),
            std::make_pair("1197MHz", "1197000"),
            std::make_pair("1098MHz", "1098000"),
            std::make_pair("930MHz", "930000"),
            std::make_pair("738MHz", "738000"),
            std::make_pair("574MHz", "574000"),
            std::make_pair("300MHz", "300000"),
            std::make_pair("0MHz", "0"),
        }});

        cfgs.push_back({"CL2", {
            std::make_pair("3195MHz", "3195000"),
            std::make_pair("3097MHz", "3097000"),
            std::make_pair("2950MHz", "2950000"),
            std::make_pair("2850MHz", "2850000"),
            std::make_pair("2802MHz", "2802000"),
            std::make_pair("2704MHz", "2704000"),
            std::make_pair("2630MHz", "2630000"),
            std::make_pair("2507MHz", "2507000"),
            std::make_pair("2401MHz", "2401000"),
            std::make_pair("2252MHz", "2252000"),
            std::make_pair("2188MHz", "2188000"),
            std::make_pair("2048MHz", "2048000"),
            std::make_pair("1826MHz", "1826000"),
            std::make_pair("1745MHz", "1745000"),
            std::make_pair("1582MHz", "1582000"),
            std::make_pair("1426MHz", "1426000"),
            std::make_pair("1277MHz", "1277000"),
            std::make_pair("1106MHz", "1106000"),
            std::make_pair("984MHz", "984000"),
            std::make_pair("851MHz", "851000"),
            std::make_pair("500MHz", "500000"),
            std::make_pair("0MHz", "0"),
        }});

        cfgs.push_back({"TPU", {
            std::make_pair("1230MHz", "1230000"),
            std::make_pair("1066MHz", "1066000"),
            std::make_pair("800MHz", "800000"),
            std::make_pair("500MHz", "500000"),
            std::make_pair("226MHz", "226000"),
            std::make_pair("RET_SLOW", "6"),
            std::make_pair("S_OFF", "5"),
            std::make_pair("S_SLOW", "4"),
            std::make_pair("DS_FAST", "3"),
            std::make_pair("DS_SLOW", "2"),
            std::make_pair("DS_OFF", "1"),
            std::make_pair("OFF", "0"),
        }});
    } else {
        cfgs.push_back({"CL0", {
            std::make_pair("2196MHz", "2196000"),
            std::make_pair("2098MHz", "2098000"),
            std::make_pair("2024MHz", "2024000"),
            std::make_pair("1950MHz", "1950000"),
            std::make_pair("1868MHz", "1868000"),
            std::make_pair("1745MHz", "1745000"),
            std::make_pair("1598MHz", "1598000"),
            std::make_pair("1459MHz", "1459000"),
            std::make_pair("1328MHz", "1328000"),
            std::make_pair("1197MHz", "1197000"),
            std::make_pair("1098MHz", "1098000"),
            std::make_pair("889MHz", "889000"),
            std::make_pair("738MHz", "738000"),
            std::make_pair("574MHz", "574000"),
            std::make_pair("300MHz", "300000"),
            std::make_pair("0MHz", "0"),
        }});

        cfgs.push_back({"CL2", {
            std::make_pair("3195MHz", "3195000"),
            std::make_pair("3097MHz", "3097000"),
            std::make_pair("2999MHz", "2999000"),
            std::make_pair("2900MHz", "2900000"),
            std::make_pair("2802MHz", "2802000"),
            std::make_pair("2704MHz", "2704000"),
            std::make_pair("2630MHz", "2630000"),
            std::make_pair("2507MHz", "2507000"),
            std::make_pair("2302MHz", "2302000"),
            std::make_pair("2188MHz", "2188000"),
            std::make_pair("2048MHz", "2048000"),
            std::make_pair("1901MHz", "1901000"),
            std::make_pair("1745MHz", "1745000"),
            std::make_pair("1582MHz", "1582000"),
            std::make_pair("1426MHz", "1426000"),
            std::make_pair("1237MHz", "1237000"),
            std::make_pair("1106MHz", "1106000"),
            std::make_pair("984MHz", "984000"),
            std::make_pair("848MHz", "848000"),
            std::make_pair("500MHz", "500000"),
            std::make_pair("0MHz", "0"),
        }});

        cfgs.push_back({"TPU", {
            std::make_pair("1393MHz", "1393000"),
            std::make_pair("1180MHz", "1180000"),
            std::make_pair("1049MHz", "1049000"),
            std::make_pair("967MHz", "967000"),
            std::make_pair("721MHz", "721000"),
            std::make_pair("648MHz", "648000"),
            std::make_pair("455MHz", "455000"),
            std::make_pair("250MHz", "250000"),
            std::make_pair("RET_SLOW", "6"),
            std::make_pair("S_OFF", "5"),
            std::make_pair("S_SLOW", "4"),
            std::make_pair("DS_FAST", "3"),
            std::make_pair("DS_SLOW", "2"),
            std::make_pair("DS_OFF", "1"),
            std::make_pair("OFF", "0"),
        }});
    }

    p->addStateResidencyDataProvider(std::make_unique<DvfsStateResidencyDataProvider>(
            "/sys/devices/platform/acpm_stats/fvp_stats", NS_TO_MS, cfgs));
}

void addSoC(std::shared_ptr<PowerStats> p) {
    // A constant to represent the number of nanoseconds in one millisecond.
    const int NS_TO_MS = 1000000;

    // ACPM stats are reported in nanoseconds. The transform function
    // converts nanoseconds to milliseconds.
    std::function<uint64_t(uint64_t)> acpmNsToMs = [](uint64_t a) { return a / NS_TO_MS; };
    const GenericStateResidencyDataProvider::StateResidencyConfig lpmStateConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "success_count:",
            .totalTimeSupported = true,
            .totalTimePrefix = "total_time_ns:",
            .totalTimeTransform = acpmNsToMs,
            .lastEntrySupported = true,
            .lastEntryPrefix = "last_entry_time_ns:",
            .lastEntryTransform = acpmNsToMs,
    };
    const GenericStateResidencyDataProvider::StateResidencyConfig downStateConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "down_count:",
            .totalTimeSupported = true,
            .totalTimePrefix = "total_down_time_ns:",
            .totalTimeTransform = acpmNsToMs,
            .lastEntrySupported = true,
            .lastEntryPrefix = "last_down_time_ns:",
            .lastEntryTransform = acpmNsToMs,
    };
    const GenericStateResidencyDataProvider::StateResidencyConfig reqStateConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "req_up_count:",
            .totalTimeSupported = true,
            .totalTimePrefix = "total_req_up_time_ns:",
            .totalTimeTransform = acpmNsToMs,
            .lastEntrySupported = true,
            .lastEntryPrefix = "last_req_up_time_ns:",
            .lastEntryTransform = acpmNsToMs,

    };
    const std::vector<std::pair<std::string, std::string>> powerStateHeaders = {
            std::make_pair("SICD", "SICD"),
            std::make_pair("SLEEP", "SLEEP"),
            std::make_pair("SLEEP_SLCMON", "SLEEP_SLCMON"),
            std::make_pair("STOP", "STOP"),
    };
    const std::vector<std::pair<std::string, std::string>> mifReqStateHeaders = {
            std::make_pair("AOC", "AOC"),
            std::make_pair("GSA", "GSA"),
    };
    const std::vector<std::pair<std::string, std::string>> slcReqStateHeaders = {
            std::make_pair("AOC", "AOC"),
    };

    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    cfgs.emplace_back(generateGenericStateResidencyConfigs(lpmStateConfig, powerStateHeaders),
            "LPM", "LPM:");
    cfgs.emplace_back(generateGenericStateResidencyConfigs(downStateConfig, powerStateHeaders),
            "MIF", "MIF:");
    cfgs.emplace_back(generateGenericStateResidencyConfigs(reqStateConfig, mifReqStateHeaders),
            "MIF-REQ", "MIF_REQ:");
    cfgs.emplace_back(generateGenericStateResidencyConfigs(downStateConfig, powerStateHeaders),
            "SLC", "SLC:");
    cfgs.emplace_back(generateGenericStateResidencyConfigs(reqStateConfig, slcReqStateHeaders),
            "SLC-REQ", "SLC_REQ:");

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/acpm_stats/soc_stats", cfgs));
}

void setEnergyMeter(std::shared_ptr<PowerStats> p) {
    std::vector<const std::string> deviceNames { "s2mpg10-odpm", "s2mpg11-odpm" };
    p->setEnergyMeterDataProvider(std::make_unique<IioEnergyMeterDataProvider>(deviceNames, true));
}

void addCPUclusters(std::shared_ptr<PowerStats> p) {
    // A constant to represent the number of nanoseconds in one millisecond.
    const int NS_TO_MS = 1000000;

    std::function<uint64_t(uint64_t)> acpmNsToMs = [](uint64_t a) { return a / NS_TO_MS; };
    const GenericStateResidencyDataProvider::StateResidencyConfig cpuStateConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "down_count:",
            .totalTimeSupported = true,
            .totalTimePrefix = "total_down_time_ns:",
            .totalTimeTransform = acpmNsToMs,
            .lastEntrySupported = true,
            .lastEntryPrefix = "last_down_time_ns:",
            .lastEntryTransform = acpmNsToMs,
    };

    const std::vector<std::pair<std::string, std::string>> cpuStateHeaders = {
            std::make_pair("DOWN", ""),
    };

    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    for (std::string name : {"CORE00", "CORE01", "CORE02", "CORE03", "CORE10", "CORE11",
                                "CORE20", "CORE21", "CLUSTER0", "CLUSTER1", "CLUSTER2"}) {
        cfgs.emplace_back(generateGenericStateResidencyConfigs(cpuStateConfig, cpuStateHeaders),
            name, name);
    }

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/acpm_stats/core_stats", cfgs));

    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterConsumer(p,
            EnergyConsumerType::CPU_CLUSTER, "CPUCL0", {"S4M_VDD_CPUCL0"}));
    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterConsumer(p,
            EnergyConsumerType::CPU_CLUSTER, "CPUCL1", {"S3M_VDD_CPUCL1"}));
    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterConsumer(p,
            EnergyConsumerType::CPU_CLUSTER, "CPUCL2", {"S2M_VDD_CPUCL2"}));
}

void addGPU(std::shared_ptr<PowerStats> p) {
    // Add gpu energy consumer
    std::map<std::string, int32_t> stateCoeffs;
    const int socRev = android::base::GetIntProperty(kBootHwSoCRev, 0);

    // B0/B1 chips have different GPU DVFS operating points than A0/A1 SoC
    if (socRev >= 2) {
        stateCoeffs = {
            {"151000",  642},
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
    } else {
        stateCoeffs = {
            {"151000",  843},
            {"302000", 1529},
            {"455000", 2298},
            {"572000", 2866},
            {"670000", 3191}};
    }

    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterAndAttrConsumer(p,
            EnergyConsumerType::OTHER, "GPU", {"S2S_VDD_G3D"},
            {{UID_TIME_IN_STATE, "/sys/devices/platform/1c500000.mali/uid_time_in_state"}},
            stateCoeffs));

    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>("GPU",
            "/sys/bus/platform/devices/1c500000.mali"));
}

void addMobileRadio(std::shared_ptr<PowerStats> p)
{
    // A constant to represent the number of microseconds in one millisecond.
    const int US_TO_MS = 1000;

    // modem power_stats are reported in microseconds. The transform function
    // converts microseconds to milliseconds.
    std::function<uint64_t(uint64_t)> modemUsToMs = [](uint64_t a) { return a / US_TO_MS; };
    const GenericStateResidencyDataProvider::StateResidencyConfig powerStateConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "count:",
            .totalTimeSupported = true,
            .totalTimePrefix = "duration_usec:",
            .totalTimeTransform = modemUsToMs,
            .lastEntrySupported = true,
            .lastEntryPrefix = "last_entry_timestamp_usec:",
            .lastEntryTransform = modemUsToMs,
    };
    const std::vector<std::pair<std::string, std::string>> powerStateHeaders = {
            std::make_pair("SLEEP", "SLEEP:"),
    };

    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    cfgs.emplace_back(generateGenericStateResidencyConfigs(powerStateConfig, powerStateHeaders),
            "MODEM", "");

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/cpif/modem/power_stats", cfgs));

    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterConsumer(p,
            EnergyConsumerType::MOBILE_RADIO, "MODEM", {"VSYS_PWR_MODEM", "VSYS_PWR_RFFE"}));
}

void addGNSS(std::shared_ptr<PowerStats> p)
{
    // A constant to represent the number of microseconds in one millisecond.
    const int US_TO_MS = 1000;

    // gnss power_stats are reported in microseconds. The transform function
    // converts microseconds to milliseconds.
    std::function<uint64_t(uint64_t)> gnssUsToMs = [](uint64_t a) { return a / US_TO_MS; };

    const GenericStateResidencyDataProvider::StateResidencyConfig gnssStateConfig = {
        .entryCountSupported = true,
        .entryCountPrefix = "count:",
        .totalTimeSupported = true,
        .totalTimePrefix = "duration_usec:",
        .totalTimeTransform = gnssUsToMs,
        .lastEntrySupported = true,
        .lastEntryPrefix = "last_entry_timestamp_usec:",
        .lastEntryTransform = gnssUsToMs,
    };

    const std::vector<std::pair<std::string, std::string>> gnssStateHeaders = {
        std::make_pair("ON", "GPS_ON:"),
        std::make_pair("OFF", "GPS_OFF:"),
    };

    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    cfgs.emplace_back(generateGenericStateResidencyConfigs(gnssStateConfig, gnssStateHeaders),
            "GPS", "");

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/dev/bbd_pwrstat", cfgs));

    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterConsumer(p,
            EnergyConsumerType::GNSS, "GPS", {"L9S_GNSS_CORE"}));
}

void addPCIe(std::shared_ptr<PowerStats> p) {
    // Add PCIe power entities for Modem and WiFi
    const GenericStateResidencyDataProvider::StateResidencyConfig pcieStateConfig = {
        .entryCountSupported = true,
        .entryCountPrefix = "Cumulative count:",
        .totalTimeSupported = true,
        .totalTimePrefix = "Cumulative duration msec:",
        .lastEntrySupported = true,
        .lastEntryPrefix = "Last entry timestamp msec:",
    };
    const std::vector<std::pair<std::string, std::string>> pcieStateHeaders = {
        std::make_pair("UP", "Link up:"),
        std::make_pair("DOWN", "Link down:"),
    };

    // Add PCIe - Modem
    const std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> pcieModemCfgs = {
        {generateGenericStateResidencyConfigs(pcieStateConfig, pcieStateHeaders), "PCIe-Modem",
                "Version: 1"}
    };

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/11920000.pcie/power_stats", pcieModemCfgs));

    // Add PCIe - WiFi
    const std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> pcieWifiCfgs = {
        {generateGenericStateResidencyConfigs(pcieStateConfig, pcieStateHeaders),
            "PCIe-WiFi", "Version: 1"}
    };

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/14520000.pcie/power_stats", pcieWifiCfgs));
}

void addWifi(std::shared_ptr<PowerStats> p) {
    // The transform function converts microseconds to milliseconds.
    std::function<uint64_t(uint64_t)> usecToMs = [](uint64_t a) { return a / 1000; };
    const GenericStateResidencyDataProvider::StateResidencyConfig stateConfig = {
        .entryCountSupported = true,
        .entryCountPrefix = "count:",
        .totalTimeSupported = true,
        .totalTimePrefix = "duration_usec:",
        .totalTimeTransform = usecToMs,
        .lastEntrySupported = true,
        .lastEntryPrefix = "last_entry_timestamp_usec:",
        .lastEntryTransform = usecToMs,
    };
    const GenericStateResidencyDataProvider::StateResidencyConfig pcieStateConfig = {
        .entryCountSupported = true,
        .entryCountPrefix = "count:",
        .totalTimeSupported = true,
        .totalTimePrefix = "duration_usec:",
        .totalTimeTransform = usecToMs,
        .lastEntrySupported = false,
    };

    const std::vector<std::pair<std::string, std::string>> stateHeaders = {
        std::make_pair("AWAKE", "AWAKE:"),
        std::make_pair("ASLEEP", "ASLEEP:"),

    };
    const std::vector<std::pair<std::string, std::string>> pcieStateHeaders = {
        std::make_pair("L0", "L0:"),
        std::make_pair("L1", "L1:"),
        std::make_pair("L1_1", "L1_1:"),
        std::make_pair("L1_2", "L1_2:"),
        std::make_pair("L2", "L2:"),
    };

    const std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs = {
        {generateGenericStateResidencyConfigs(stateConfig, stateHeaders), "WIFI", "WIFI"},
        {generateGenericStateResidencyConfigs(pcieStateConfig, pcieStateHeaders), "WIFI-PCIE",
                "WIFI-PCIE"}
    };

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>("/sys/wifi/power_stats",
            cfgs));
}

void addUfs(std::shared_ptr<PowerStats> p) {
    p->addStateResidencyDataProvider(std::make_unique<UfsStateResidencyDataProvider>("/sys/bus/platform/devices/14700000.ufs/ufs_stats/"));
}

void addPowerDomains(std::shared_ptr<PowerStats> p) {
    // A constant to represent the number of nanoseconds in one millisecond.
    const int NS_TO_MS = 1000000;

    std::function<uint64_t(uint64_t)> acpmNsToMs = [](uint64_t a) { return a / NS_TO_MS; };
    const GenericStateResidencyDataProvider::StateResidencyConfig cpuStateConfig = {
            .entryCountSupported = true,
            .entryCountPrefix = "on_count:",
            .totalTimeSupported = true,
            .totalTimePrefix = "total_on_time_ns:",
            .totalTimeTransform = acpmNsToMs,
            .lastEntrySupported = true,
            .lastEntryPrefix = "last_on_time_ns:",
            .lastEntryTransform = acpmNsToMs,
    };

    const std::vector<std::pair<std::string, std::string>> cpuStateHeaders = {
            std::make_pair("ON", ""),
    };

    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    for (std::string name : {"pd-tpu", "pd-bo", "pd-tnr", "pd-gdc", "pd-mcsc", "pd-ipp",
                                "pd-g3aa", "pd-dns", "pd-itp", "pd-pdp", "pd-csis",
                                "pd-mfc", "pd-g2d", "pd-dpu", "pd-disp", "pd-hsi0",
                                "pd-embedded_g3d", "pd-g3d", "pd-eh"}) {
        cfgs.emplace_back(generateGenericStateResidencyConfigs(cpuStateConfig, cpuStateHeaders),
            name, name + ":");
    }

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            "/sys/devices/platform/acpm_stats/pd_stats", cfgs));
}

void addDevfreq(std::shared_ptr<PowerStats> p) {
    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "INT",
            "/sys/devices/platform/17000020.devfreq_int/devfreq/17000020.devfreq_int"));

    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "INTCAM",
            "/sys/devices/platform/17000030.devfreq_intcam/devfreq/17000030.devfreq_intcam"));

    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "DISP",
            "/sys/devices/platform/17000040.devfreq_disp/devfreq/17000040.devfreq_disp"));

    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "CAM",
            "/sys/devices/platform/17000050.devfreq_cam/devfreq/17000050.devfreq_cam"));

    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "TNR",
            "/sys/devices/platform/17000060.devfreq_tnr/devfreq/17000060.devfreq_tnr"));

    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "MFC",
            "/sys/devices/platform/17000070.devfreq_mfc/devfreq/17000070.devfreq_mfc"));

    p->addStateResidencyDataProvider(std::make_unique<DevfreqStateResidencyDataProvider>(
            "BO",
            "/sys/devices/platform/17000080.devfreq_bo/devfreq/17000080.devfreq_bo"));
}

void addTPU(std::shared_ptr<PowerStats> p) {
    std::map<std::string, int32_t> stateCoeffs;

    stateCoeffs = {
        {"226000",  49.25},
        {"500000",  73.80},
        {"800000",  86.99},
        {"1066000", 103.93},
        {"1230000", 108.10}};

    p->addEnergyConsumer(PowerStatsEnergyConsumer::createMeterAndAttrConsumer(p,
            EnergyConsumerType::OTHER, "TPU", {"S10M_VDD_TPU"},
            {{UID_TIME_IN_STATE, "/sys/class/edgetpu/edgetpu-soc/device/tpu_usage"}},
            stateCoeffs));
}

/**
 * Unlike other data providers, which source power entity state residency data from the kernel,
 * this data provider acts as a general-purpose channel for state residency data providers
 * that live in user space. Entities are defined here and user space clients of this provider's
 * vendor service register callbacks to provide state residency data for their given pwoer entity.
 */
void addPixelStateResidencyDataProvider(std::shared_ptr<PowerStats> p) {

    auto pixelSdp = std::make_unique<PixelStateResidencyDataProvider>();

    pixelSdp->addEntity("Bluetooth", {{0, "Idle"}, {1, "Active"}, {2, "Tx"}, {3, "Rx"}});

    pixelSdp->start();

    p->addStateResidencyDataProvider(std::move(pixelSdp));
}

void addGs101CommonDataProviders(std::shared_ptr<PowerStats> p) {
    setEnergyMeter(p);

    addPixelStateResidencyDataProvider(p);
    addAoC(p);
    addDvfsStats(p);
    addSoC(p);
    addCPUclusters(p);
    addGPU(p);
    addMobileRadio(p);
    addGNSS(p);
    addPCIe(p);
    addWifi(p);
    addUfs(p);
    addPowerDomains(p);
    addDevfreq(p);
    addTPU(p);
}

void addNFC(std::shared_ptr<PowerStats> p, const std::string& path) {
    const GenericStateResidencyDataProvider::StateResidencyConfig nfcStateConfig = {
        .entryCountSupported = true,
        .entryCountPrefix = "Cumulative count:",
        .totalTimeSupported = true,
        .totalTimePrefix = "Cumulative duration msec:",
        .lastEntrySupported = true,
        .lastEntryPrefix = "Last entry timestamp msec:",
    };
    const std::vector<std::pair<std::string, std::string>> nfcStateHeaders = {
        std::make_pair("IDLE", "Idle mode:"),
        std::make_pair("ACTIVE", "Active mode:"),
        std::make_pair("ACTIVE-RW", "Active Reader/Writer mode:"),
    };

    std::vector<GenericStateResidencyDataProvider::PowerEntityConfig> cfgs;
    cfgs.emplace_back(generateGenericStateResidencyConfigs(nfcStateConfig, nfcStateHeaders),
            "NFC", "NFC subsystem");

    p->addStateResidencyDataProvider(std::make_unique<GenericStateResidencyDataProvider>(
            path, cfgs));
}
