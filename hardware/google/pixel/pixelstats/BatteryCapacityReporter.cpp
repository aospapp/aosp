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

#define LOG_TAG "pixelstats-uevent: BatteryCapacityFG"

#include <log/log.h>
#include <time.h>
#include <utils/Timers.h>
#include <cmath>

#include <android-base/file.h>

#include <android/frameworks/stats/1.0/IStats.h>
#include <pixelstats/BatteryCapacityReporter.h>

#include <hardware/google/pixel/pixelstats/pixelatoms.pb.h>

namespace android {
namespace hardware {
namespace google {
namespace pixel {

using android::base::ReadFileToString;
using android::frameworks::stats::V1_0::IStats;
using android::frameworks::stats::V1_0::VendorAtom;
using android::hardware::google::pixel::PixelAtoms::BatteryCapacityFG;

BatteryCapacityReporter::BatteryCapacityReporter() {
    // Remove the need for a translation function/table, while removing the dependency on the
    // generated <pixelatoms.pb.h> in BatteryCapacityReporter.h.
    static_assert(static_cast<int>(BatteryCapacityReporter::LOG_REASON_UNKNOWN) ==
                  static_cast<int>(BatteryCapacityFG::LOG_REASON_UNKNOWN));
    static_assert(static_cast<int>(BatteryCapacityReporter::LOG_REASON_CONNECTED) ==
                  static_cast<int>(BatteryCapacityFG::LOG_REASON_CONNECTED));
    static_assert(static_cast<int>(BatteryCapacityReporter::LOG_REASON_DISCONNECTED) ==
                  static_cast<int>(BatteryCapacityFG::LOG_REASON_DISCONNECTED));
    static_assert(static_cast<int>(BatteryCapacityReporter::LOG_REASON_FULL_CHARGE) ==
                  static_cast<int>(BatteryCapacityFG::LOG_REASON_FULL_CHARGE));
    static_assert(static_cast<int>(BatteryCapacityReporter::LOG_REASON_PERCENT_SKIP) ==
                  static_cast<int>(BatteryCapacityFG::LOG_REASON_PERCENT_SKIP));
    static_assert(static_cast<int>(BatteryCapacityReporter::LOG_REASON_DIVERGING_FG) ==
                  static_cast<int>(BatteryCapacityFG::LOG_REASON_DIVERGING_FG));
}

void BatteryCapacityReporter::checkAndReport(const std::string &path) {
    if (parse(path)) {
        if (check()) {
            report();
        }
    }
}

int64_t BatteryCapacityReporter::getTimeSecs(void) {
    return nanoseconds_to_seconds(systemTime(SYSTEM_TIME_BOOTTIME));
}

bool BatteryCapacityReporter::parse(const std::string &path) {
    std::string batterySSOCContents;
    if (!ReadFileToString(path, &batterySSOCContents)) {
        ALOGE("Unable to read ssoc_details path: %s - %s", path.c_str(), strerror(errno));
        return false;
    }

    // Parse file. Example format:
    // soc: l=97% gdf=97.72 uic=97.72 rl=97.72
    // curve:[15.00 15.00][97.87 97.87][100.00 100.00]
    // status: ct=1 rl=0 s=1
    if (sscanf(batterySSOCContents.c_str(),
               "soc: %*s gdf=%f %*s rl=%f\n"
               "curve:[%*f %*f][%f %f][%*f %*f]\n"
               "status: %*s %*s s=%d",
               &gdf_, &ssoc_, &gdf_curve_, &ssoc_curve_, &status_) != 5) {
        ALOGE("Unable to parse ssoc_details [%s] from file %s to int.", batterySSOCContents.c_str(),
              path.c_str());
        return false;
    }

    return true;
}

bool BatteryCapacityReporter::check(void) {
    if (unexpected_event_timer_active_) {
        // A 30 minute timer with a boolean gate helps prevent uninitialized timers and potential
        // overflows.
        // - Active when the timer is less than 30 minutes, thus continues checking the elapsed
        //   time.
        // - Once expired (> 30 min), active becomes false and the timer no longer needs to check
        //   the elapsed time.
        unexpected_event_timer_active_ =
                (getTimeSecs() - unexpected_event_timer_secs_) <= (30 * 60);
    }

    LogReason log_reason = LOG_REASON_UNKNOWN;
    if (status_previous_ != status_) {
        // Handle nominal events

        if (status_ == SOC_STATUS_CONNECTED) {
            log_reason = LOG_REASON_CONNECTED;

        } else if (status_ == SOC_STATUS_DISCONNECTED) {
            log_reason = LOG_REASON_DISCONNECTED;

        } else if (status_ == SOC_STATUS_FULL) {
            log_reason = LOG_REASON_FULL_CHARGE;
        }

        status_previous_ = status_;

    } else if (unexpected_event_timer_active_ == false) {
        // Handle abnormal events at a minimum period

        const float diff = fabsf(ssoc_ - gdf_);

        if (fabsf(ssoc_ - ssoc_previous_) >= 2.0f) {
            unexpected_event_timer_secs_ = getTimeSecs();
            unexpected_event_timer_active_ = true;
            log_reason = LOG_REASON_PERCENT_SKIP;

            // Every +- 1% when above a 4% SOC difference (w/ timer)
        } else if (static_cast<int>(round(ssoc_gdf_diff_previous_)) !=
                           static_cast<int>(round(diff)) &&
                   diff >= 4.0f) {
            unexpected_event_timer_secs_ = getTimeSecs();
            unexpected_event_timer_active_ = true;
            log_reason = LOG_REASON_DIVERGING_FG;

            ssoc_gdf_diff_previous_ = diff;
        }
    }
    ssoc_previous_ = ssoc_;

    log_reason_ = log_reason;
    return (log_reason != LOG_REASON_UNKNOWN);
}

void BatteryCapacityReporter::report(void) {
    sp<IStats> stats_client = IStats::tryGetService();
    if (!stats_client) {
        ALOGD("Couldn't connect to IStats service");
        return;
    }

    // Load values array
    std::vector<VendorAtom::Value> values(5);
    VendorAtom::Value tmp;
    tmp.intValue(log_reason_);
    values[BatteryCapacityFG::kCapacityLogReasonFieldNumber - kVendorAtomOffset] = tmp;
    tmp.floatValue(gdf_);
    values[BatteryCapacityFG::kCapacityGdfFieldNumber - kVendorAtomOffset] = tmp;
    tmp.floatValue(ssoc_);
    values[BatteryCapacityFG::kCapacitySsocFieldNumber - kVendorAtomOffset] = tmp;
    tmp.floatValue(gdf_curve_);
    values[BatteryCapacityFG::kCapacityGdfCurveFieldNumber - kVendorAtomOffset] = tmp;
    tmp.floatValue(ssoc_curve_);
    values[BatteryCapacityFG::kCapacitySsocCurveFieldNumber - kVendorAtomOffset] = tmp;

    // Send vendor atom to IStats HAL
    VendorAtom event = {.reverseDomainName = PixelAtoms::ReverseDomainNames().pixel(),
                        .atomId = PixelAtoms::Ids::FG_CAPACITY,
                        .values = values};
    Return<void> ret = stats_client->reportVendorAtom(event);
    if (!ret.isOk())
        ALOGE("Unable to report to IStats service");
}

}  // namespace pixel
}  // namespace google
}  // namespace hardware
}  // namespace android
