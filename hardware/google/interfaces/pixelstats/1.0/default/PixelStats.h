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

#ifndef HARDWARE_GOOGLE_PIXELSTATS_V1_0_PIXELSTATS_H
#define HARDWARE_GOOGLE_PIXELSTATS_V1_0_PIXELSTATS_H

#include <hardware/google/pixelstats/1.0/IPixelStats.h>
#include <hidl/MQDescriptor.h>
#include <hidl/Status.h>

#include "RateLimiter.h"

namespace hardware {
namespace google {
namespace pixelstats {
namespace V1_0 {
namespace implementation {

using ::android::sp;
using ::android::hardware::hidl_array;
using ::android::hardware::hidl_memory;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;


struct PixelStats : public IPixelStats {
    PixelStats();

    // Methods from ::hardware::google::pixelstats::V1_0::IPixelStats follow.
    Return<void> reportUsbConnectorConnected() override;
    Return<void> reportUsbConnectorDisconnected(int32_t duration_millis) override;
    Return<void> reportUsbAudioConnected(int32_t vid, int32_t pid) override;
    Return<void> reportUsbAudioDisconnected(int32_t vid, int32_t pid,
                                            int32_t duration_millis) override;
    Return<void> reportSpeakerImpedance(int32_t speakerLocation, int32_t milliOhms) override;
    Return<void> reportHardwareFailed(HardwareType hardwareType, int32_t hardwareLocation,
                                      HardwareErrorCode errorCode) override;
    Return<void> reportPhysicalDropDetected(int32_t confidence, int32_t accelPeak,
                                            int32_t freefallDurationMs) override;
    Return<void> reportChargeCycles(const hidl_string& buckets) override;
    Return<void> reportBatteryHealthSnapshot(const BatteryHealthSnapshotArgs& args) override;
    Return<void> reportSlowIo(IoOperation operation, int32_t count) override;
    Return<void> reportBatteryCausedShutdown(int32_t voltageMicroV) override;

  private:
    // At most 150 events per day by default.
    static constexpr int32_t kDailyRatelimit = 150;

    // each action provides its own per-limit daily rate limit.
    bool rateLimit(int action, int actionLimit);

    RateLimiter limiter_;
};

}  // namespace implementation
}  // namespace V1_0
}  // namespace pixelstats
}  // namespace google
}  // namespace hardware

#endif  // HARDWARE_GOOGLE_PIXELSTATS_V1_0_PIXELSTATS_H
