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

#define LOG_TAG "pixelstats-wlc"

#include <android-base/file.h>
#include <log/log.h>
#include <pixelstats/OrientationCollector.h>
#include <pixelstats/WlcReporter.h>

#define POWER_SUPPLY_SYSFS_PATH "/sys/class/power_supply/wireless/online"
#define POWER_SUPPLY_PTMC_PATH "/sys/class/power_supply/wireless/ptmc_id"
#define GOOGLE_PTMC_ID 72

using android::base::ReadFileToString;
using android::frameworks::stats::V1_0::IStats;
using android::frameworks::stats::V1_0::VendorAtom;

namespace android {
namespace hardware {
namespace google {
namespace pixel {

bool WlcReporter::checkAndReport(bool isWirelessChargingLast) {
    bool wireless_charging = isWlcOnline();
    if (wireless_charging && !isWirelessChargingLast) {
        doLog();
    }
    return wireless_charging;
}

bool WlcReporter::readFileToInt(const char *const path, int *val) {
    std::string file_contents;

    if (!ReadFileToString(path, &file_contents)) {
        ALOGE("Unable to read %s - %s", path, strerror(errno));
        return false;
    } else if (sscanf(file_contents.c_str(), "%d", val) != 1) {
        ALOGE("Unable to convert %s (%s) to int - %s", path, file_contents.c_str(),
              strerror(errno));
        return false;
    }
    return true;
}

int WlcReporter::readPtmcId() {
    int id = 0;
    readFileToInt(POWER_SUPPLY_PTMC_PATH, &id);
    return id;
}

/*  Reference to frameworks/native/libs/ui/include/ui/DisplayInfo.h
 *  translate orientation value from sensor to enum define in
 *  pixelatoms.proto
 */
int WlcReporter::translateDeviceOrientationToAtomValue(int orientation) {
    switch (orientation) {
        case 0:
            return PixelAtoms::DeviceOrientation::ORIENTATION_0;
        case 1:
            return PixelAtoms::DeviceOrientation::ORIENTATION_90;
        case 2:
            return PixelAtoms::DeviceOrientation::ORIENTATION_180;
        case 3:
            return PixelAtoms::DeviceOrientation::ORIENTATION_270;
        default:
            return PixelAtoms::DeviceOrientation::ORIENTATION_UNKNOWN;
    }
}

void WlcReporter::doLog() {
    sp<IStats> stats_client = IStats::tryGetService();

    if (stats_client == nullptr) {
        ALOGE("logWlc get IStats fail.");
        return;
    }
    std::vector<VendorAtom::Value> values(1);

    int vendoriCharger = (readPtmcId() == GOOGLE_PTMC_ID)
                                 ? PixelAtoms::WirelessChargingStats::VENDOR_GOOGLE
                                 : PixelAtoms::WirelessChargingStats::VENDOR_UNKNOWN;
    VendorAtom::Value tmp;
    tmp.intValue(vendoriCharger);
    values[PixelAtoms::WirelessChargingStats::kChargerVendorFieldNumber - kVendorAtomOffset] = tmp;

    // Send vendor atom to IStats HAL
    VendorAtom event = {.reverseDomainName = PixelAtoms::ReverseDomainNames().pixel(),
                        .atomId = PixelAtoms::Ids::WIRELESS_CHARGING_STATS,
                        .values = values};
    Return<void> retStat = stats_client->reportVendorAtom(event);
    if (!retStat.isOk())
        ALOGE("Unable to report WLC_STATS to Stats service");

    int orientationFromSensor;
    sp<OrientationCollector> orientationCollector;
    orientationCollector = OrientationCollector::createOrientationCollector();
    if (orientationCollector != nullptr) {
        orientationCollector->pollOrientation(&orientationFromSensor);
        VendorAtom::Value tmp;
        tmp.intValue(translateDeviceOrientationToAtomValue(orientationFromSensor));
        values[PixelAtoms::DeviceOrientation::kOrientationFieldNumber - kVendorAtomOffset] = tmp;

        VendorAtom event = {.reverseDomainName = PixelAtoms::ReverseDomainNames().pixel(),
                            .atomId = PixelAtoms::Ids::DEVICE_ORIENTATION,
                            .values = values};
        Return<void> retOrientation = stats_client->reportVendorAtom(event);
        if (!retOrientation.isOk())
            ALOGE("Unable to report Orientation to Stats service");
        orientationCollector->disableOrientationSensor();
    }
}

bool WlcReporter::isWlcSupported() {
    std::string file_contents;

    if (!ReadFileToString(POWER_SUPPLY_SYSFS_PATH, &file_contents)) {
        ALOGV("Unable to read %s - %s", POWER_SUPPLY_SYSFS_PATH, strerror(errno));
        return false;
    } else {
        return true;
    }
}

bool WlcReporter::isWlcOnline() {
    std::string file_contents;

    if (!ReadFileToString(POWER_SUPPLY_SYSFS_PATH, &file_contents)) {
        ALOGE("Unable to read %s - %s", POWER_SUPPLY_SYSFS_PATH, strerror(errno));
        return false;
    }
    ALOGV("isWlcOnline value: %s", file_contents.c_str());
    return file_contents == "1\n";
}

}  // namespace pixel
}  // namespace google
}  // namespace hardware
}  // namespace android
