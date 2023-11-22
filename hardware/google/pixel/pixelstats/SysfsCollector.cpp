/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <pixelstats/SysfsCollector.h>

#define LOG_TAG "pixelstats-vendor"

#include <android-base/file.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android/frameworks/stats/1.0/IStats.h>
#include <hardware/google/pixel/pixelstats/pixelatoms.pb.h>
#include <utils/Log.h>
#include <utils/StrongPointer.h>
#include <utils/Timers.h>

#include <sys/timerfd.h>
#include <string>

namespace android {
namespace hardware {
namespace google {
namespace pixel {

using android::sp;
using android::base::ReadFileToString;
using android::frameworks::stats::V1_0::ChargeCycles;
using android::frameworks::stats::V1_0::HardwareFailed;
using android::frameworks::stats::V1_0::IStats;
using android::frameworks::stats::V1_0::SlowIo;
using android::frameworks::stats::V1_0::SpeakerImpedance;
using android::frameworks::stats::V1_0::SpeechDspStat;
using android::frameworks::stats::V1_0::VendorAtom;
using android::hardware::google::pixel::PixelAtoms::BatteryCapacity;

SysfsCollector::SysfsCollector(const struct SysfsPaths &sysfs_paths)
    : kSlowioReadCntPath(sysfs_paths.SlowioReadCntPath),
      kSlowioWriteCntPath(sysfs_paths.SlowioWriteCntPath),
      kSlowioUnmapCntPath(sysfs_paths.SlowioUnmapCntPath),
      kSlowioSyncCntPath(sysfs_paths.SlowioSyncCntPath),
      kCycleCountBinsPath(sysfs_paths.CycleCountBinsPath),
      kImpedancePath(sysfs_paths.ImpedancePath),
      kCodecPath(sysfs_paths.CodecPath),
      kCodec1Path(sysfs_paths.Codec1Path),
      kSpeechDspPath(sysfs_paths.SpeechDspPath),
      kBatteryCapacityCC(sysfs_paths.BatteryCapacityCC),
      kBatteryCapacityVFSOC(sysfs_paths.BatteryCapacityVFSOC) {}

bool SysfsCollector::ReadFileToInt(const std::string &path, int *val) {
    return ReadFileToInt(path.c_str(), val);
}

bool SysfsCollector::ReadFileToInt(const char *const path, int *val) {
    std::string file_contents;

    if (!ReadFileToString(path, &file_contents)) {
        ALOGE("Unable to read %s - %s", path, strerror(errno));
        return false;
    } else if (sscanf(file_contents.c_str(), "%d", val) != 1) {
        ALOGE("Unable to convert %s to int - %s", path, strerror(errno));
        return false;
    }
    return true;
}

/**
 * Read the contents of kCycleCountBinsPath and report them via IStats HAL.
 * The contents are expected to be N buckets total, the nth of which indicates the
 * number of times battery %-full has been increased with the n/N% full bucket.
 */
void SysfsCollector::logBatteryChargeCycles() {
    std::string file_contents;
    int val;
    std::vector<int> charge_cycles;
    if (kCycleCountBinsPath == nullptr || strlen(kCycleCountBinsPath) == 0) {
        ALOGV("Battery charge cycle path not specified");
        return;
    }
    if (!ReadFileToString(kCycleCountBinsPath, &file_contents)) {
        ALOGE("Unable to read battery charge cycles %s - %s", kCycleCountBinsPath, strerror(errno));
        return;
    }

    std::stringstream stream(file_contents);
    while (stream >> val) {
        charge_cycles.push_back(val);
    }
    ChargeCycles cycles;
    cycles.cycleBucket = charge_cycles;

    std::replace(file_contents.begin(), file_contents.end(), ' ', ',');
    stats_->reportChargeCycles(cycles);
}

/**
 * Check the codec for failures over the past 24hr.
 */
void SysfsCollector::logCodecFailed() {
    std::string file_contents;
    if (kCodecPath == nullptr || strlen(kCodecPath) == 0) {
        ALOGV("Audio codec path not specified");
        return;
    }
    if (!ReadFileToString(kCodecPath, &file_contents)) {
        ALOGE("Unable to read codec state %s - %s", kCodecPath, strerror(errno));
        return;
    }
    if (file_contents == "0") {
        return;
    } else {
        HardwareFailed failed = {.hardwareType = HardwareFailed::HardwareType::CODEC,
                                 .hardwareLocation = 0,
                                 .errorCode = HardwareFailed::HardwareErrorCode::COMPLETE};
        stats_->reportHardwareFailed(failed);
    }
}

/**
 * Check the codec1 for failures over the past 24hr.
 */
void SysfsCollector::logCodec1Failed() {
    std::string file_contents;
    if (kCodec1Path == nullptr || strlen(kCodec1Path) == 0) {
        ALOGV("Audio codec1 path not specified");
        return;
    }
    if (!ReadFileToString(kCodec1Path, &file_contents)) {
        ALOGE("Unable to read codec1 state %s - %s", kCodec1Path, strerror(errno));
        return;
    }
    if (file_contents == "0") {
        return;
    } else {
        ALOGE("%s report hardware fail", kCodec1Path);
        HardwareFailed failed = {.hardwareType = HardwareFailed::HardwareType::CODEC,
                                 .hardwareLocation = 1,
                                 .errorCode = HardwareFailed::HardwareErrorCode::COMPLETE};
        stats_->reportHardwareFailed(failed);
    }
}

void SysfsCollector::reportSlowIoFromFile(const char *path,
                                          const SlowIo::IoOperation &operation_s) {
    std::string file_contents;
    if (path == nullptr || strlen(path) == 0) {
        ALOGV("slow_io path not specified");
        return;
    }
    if (!ReadFileToString(path, &file_contents)) {
        ALOGE("Unable to read slowio %s - %s", path, strerror(errno));
        return;
    } else {
        int32_t slow_io_count = 0;
        if (sscanf(file_contents.c_str(), "%d", &slow_io_count) != 1) {
            ALOGE("Unable to parse %s from file %s to int.", file_contents.c_str(), path);
        } else if (slow_io_count > 0) {
            SlowIo slowio = {.operation = operation_s, .count = slow_io_count};
            stats_->reportSlowIo(slowio);
        }
        // Clear the stats
        if (!android::base::WriteStringToFile("0", path, true)) {
            ALOGE("Unable to clear SlowIO entry %s - %s", path, strerror(errno));
        }
    }
}

/**
 * Check for slow IO operations.
 */
void SysfsCollector::logSlowIO() {
    reportSlowIoFromFile(kSlowioReadCntPath, SlowIo::IoOperation::READ);
    reportSlowIoFromFile(kSlowioWriteCntPath, SlowIo::IoOperation::WRITE);
    reportSlowIoFromFile(kSlowioUnmapCntPath, SlowIo::IoOperation::UNMAP);
    reportSlowIoFromFile(kSlowioSyncCntPath, SlowIo::IoOperation::SYNC);
}

/**
 * Report the last-detected impedance of left & right speakers.
 */
void SysfsCollector::logSpeakerImpedance() {
    std::string file_contents;
    if (kImpedancePath == nullptr || strlen(kImpedancePath) == 0) {
        ALOGV("Audio impedance path not specified");
        return;
    }
    if (!ReadFileToString(kImpedancePath, &file_contents)) {
        ALOGE("Unable to read impedance path %s", kImpedancePath);
        return;
    }

    float left, right;
    if (sscanf(file_contents.c_str(), "%g,%g", &left, &right) != 2) {
        ALOGE("Unable to parse speaker impedance %s", file_contents.c_str());
        return;
    }
    SpeakerImpedance left_obj = {.speakerLocation = 0,
                                 .milliOhms = static_cast<int32_t>(left * 1000)};
    SpeakerImpedance right_obj = {.speakerLocation = 1,
                                  .milliOhms = static_cast<int32_t>(right * 1000)};
    stats_->reportSpeakerImpedance(left_obj);
    stats_->reportSpeakerImpedance(right_obj);
}

/**
 * Report the Speech DSP state.
 */
void SysfsCollector::logSpeechDspStat() {
    std::string file_contents;
    if (kSpeechDspPath == nullptr || strlen(kSpeechDspPath) == 0) {
        ALOGV("Speech DSP path not specified");
        return;
    }
    if (!ReadFileToString(kSpeechDspPath, &file_contents)) {
        ALOGE("Unable to read speech dsp path %s", kSpeechDspPath);
        return;
    }

    int32_t uptime = 0, downtime = 0, crashcount = 0, recovercount = 0;
    if (sscanf(file_contents.c_str(), "%d,%d,%d,%d", &uptime, &downtime, &crashcount,
               &recovercount) != 4) {
        ALOGE("Unable to parse speech dsp stat %s", file_contents.c_str());
        return;
    }

    ALOGD("SpeechDSP uptime %d downtime %d crashcount %d recovercount %d", uptime, downtime,
          crashcount, recovercount);
    SpeechDspStat dspstat = {.totalUptimeMillis = uptime,
                             .totalDowntimeMillis = downtime,
                             .totalCrashCount = crashcount,
                             .totalRecoverCount = recovercount};

    stats_->reportSpeechDspStat(dspstat);
}

void SysfsCollector::logBatteryCapacity() {
    std::string file_contents;
    if (kBatteryCapacityCC == nullptr || strlen(kBatteryCapacityCC) == 0) {
        ALOGV("Battery Capacity CC path not specified");
        return;
    }
    if (kBatteryCapacityVFSOC == nullptr || strlen(kBatteryCapacityVFSOC) == 0) {
        ALOGV("Battery Capacity VFSOC path not specified");
        return;
    }
    int delta_cc_sum, delta_vfsoc_sum;
    if (!ReadFileToInt(kBatteryCapacityCC, &delta_cc_sum) ||
	!ReadFileToInt(kBatteryCapacityVFSOC, &delta_vfsoc_sum))
	return;

    // Load values array
    std::vector<VendorAtom::Value> values(2);
    VendorAtom::Value tmp;
    tmp.intValue(delta_cc_sum);
    values[BatteryCapacity::kDeltaCcSumFieldNumber - kVendorAtomOffset] = tmp;
    tmp.intValue(delta_vfsoc_sum);
    values[BatteryCapacity::kDeltaVfsocSumFieldNumber - kVendorAtomOffset] = tmp;

    // Send vendor atom to IStats HAL
    VendorAtom event = {.reverseDomainName = PixelAtoms::ReverseDomainNames().pixel(),
                        .atomId = PixelAtoms::Ids::BATTERY_CAPACITY,
                        .values = values};
    Return<void> ret = stats_->reportVendorAtom(event);
    if (!ret.isOk())
        ALOGE("Unable to report ChargeStats to Stats service");
}

void SysfsCollector::logAll() {
    stats_ = IStats::tryGetService();
    if (!stats_) {
        ALOGE("Unable to connect to Stats service");
        return;
    }

    logBatteryChargeCycles();
    logCodecFailed();
    logCodec1Failed();
    logSlowIO();
    logSpeakerImpedance();
    logSpeechDspStat();
    logBatteryCapacity();

    stats_.clear();
}

/**
 * Loop forever collecting stats from sysfs nodes and reporting them via
 * IStats.
 */
void SysfsCollector::collect(void) {
    int timerfd = timerfd_create(CLOCK_BOOTTIME, 0);
    if (timerfd < 0) {
        ALOGE("Unable to create timerfd - %s", strerror(errno));
        return;
    }

    // Sleep for 30 seconds on launch to allow codec driver to load.
    sleep(30);

    // Collect first set of stats on boot.
    logAll();

    // Collect stats every 24hrs after.
    struct itimerspec period;
    const int kSecondsPerDay = 60 * 60 * 24;
    period.it_interval.tv_sec = kSecondsPerDay;
    period.it_interval.tv_nsec = 0;
    period.it_value.tv_sec = kSecondsPerDay;
    period.it_value.tv_nsec = 0;

    if (timerfd_settime(timerfd, 0, &period, NULL)) {
        ALOGE("Unable to set 24hr timer - %s", strerror(errno));
        return;
    }

    while (1) {
        int readval;
        do {
            char buf[8];
            errno = 0;
            readval = read(timerfd, buf, sizeof(buf));
        } while (readval < 0 && errno == EINTR);
        if (readval < 0) {
            ALOGE("Timerfd error - %s\n", strerror(errno));
            return;
        }
        logAll();
    }
}

}  // namespace pixel
}  // namespace google
}  // namespace hardware
}  // namespace android
