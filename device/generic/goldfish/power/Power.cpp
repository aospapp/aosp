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

#define LOG_TAG "android.hardware.power@1.1-service.marlin"

#include <android/log.h>
#include <utils/Log.h>

#include <android-base/properties.h>

#include "Power.h"

enum subsystem_type {
    SUBSYSTEM_WLAN,

    //Don't add any lines after that line
    SUBSYSTEM_COUNT
};

enum wlan_param_id {
    CUMULATIVE_SLEEP_TIME_MS,
    CUMULATIVE_TOTAL_ON_TIME_MS,
    DEEP_SLEEP_ENTER_COUNTER,
    LAST_DEEP_SLEEP_ENTER_TSTAMP_MS,

    //Don't add any lines after that line
    WLAN_PARAM_COUNT
};

enum wlan_state_id {
    WLAN_STATE_ACTIVE = 0,
    WLAN_STATE_DEEP_SLEEP,

    //Don't add any lines after that line
    WLAN_STATE_COUNT
};

namespace android {
namespace hardware {
namespace power {
namespace V1_1 {
namespace implementation {

using ::android::hardware::power::V1_0::Feature;
using ::android::hardware::power::V1_0::PowerHint;
using ::android::hardware::power::V1_0::PowerStatePlatformSleepState;
using ::android::hardware::power::V1_0::Status;
using ::android::hardware::power::V1_1::PowerStateSubsystem;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;

Power::Power() {
}

// Methods from ::android::hardware::power::V1_0::IPower follow.
Return<void> Power::setInteractive(bool __attribute__((__unused__)) interactive)  {
    return Void();
}

Return<void> Power::powerHint(PowerHint __attribute__((__unused__)) hint, int32_t __attribute__((__unused__)) data) {
    return Void();
}

Return<void> Power::setFeature(Feature /*feature*/, bool /*activate*/)  {
    return Void();
}

Return<void> Power::getPlatformLowPowerStats(getPlatformLowPowerStats_cb _hidl_cb) {

    hidl_vec<PowerStatePlatformSleepState> states;

    states.resize(0);
    _hidl_cb(states, Status::SUCCESS);
    return Void();
}

static int get_wlan_low_power_stats(struct PowerStateSubsystem &subsystem) {

    struct PowerStateSubsystemSleepState *state;

    subsystem.name = "wlan";
    subsystem.states.resize(WLAN_STATE_COUNT);

    /* Update statistics for Active State */
    state = &subsystem.states[WLAN_STATE_ACTIVE];
    state->name = "Active";
    state->residencyInMsecSinceBoot = 1000;
    state->totalTransitions = 1;
    state->lastEntryTimestampMs = 0;
    state->supportedOnlyInSuspend = false;

    /* Update statistics for Deep-Sleep state */
    state = &subsystem.states[WLAN_STATE_DEEP_SLEEP];
    state->name = "Deep-Sleep";
    state->residencyInMsecSinceBoot = 0;
    state->totalTransitions = 0;
    state->lastEntryTimestampMs = 0;
    state->supportedOnlyInSuspend = false;

    return 0;
}

Return<void> Power::getSubsystemLowPowerStats(getSubsystemLowPowerStats_cb _hidl_cb) {

    hidl_vec<PowerStateSubsystem> subsystems;
    int ret;

    subsystems.resize(subsystem_type::SUBSYSTEM_COUNT);

    //We currently have only one Subsystem for WLAN
    ret = get_wlan_low_power_stats(subsystems[subsystem_type::SUBSYSTEM_WLAN]);
    if (ret != 0) {
        goto done;
    }

    //Add query for other subsystems here

done:
    _hidl_cb(subsystems, Status::SUCCESS);
    return Void();
}

Return<void> Power::powerHintAsync(PowerHint hint, int32_t data) {
    // just call the normal power hint in this oneway function
    return powerHint(hint, data);
}

}  // namespace implementation
}  // namespace V1_1
}  // namespace power
}  // namespace hardware
}  // namespace android
