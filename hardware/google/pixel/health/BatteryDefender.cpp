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

#define LOG_TAG "BatteryDefender"

#include <pixelhealth/BatteryDefender.h>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parsebool.h>
#include <android-base/parseint.h>
#include <android-base/properties.h>

#include <time.h>
#include <utils/Timers.h>

#include <cutils/klog.h>

namespace hardware {
namespace google {
namespace pixel {
namespace health {

BatteryDefender::BatteryDefender(const char *pathChargeLevelStart, const char *pathChargeLevelStop,
                                 const int32_t timeToActivateSecs,
                                 const int32_t timeToClearTimerSecs)
    : kPathChargeLevelStart(pathChargeLevelStart),
      kPathChargeLevelStop(pathChargeLevelStop),
      kTimeToActivateSecs(timeToActivateSecs),
      kTimeToClearTimerSecs(timeToClearTimerSecs) {
    mTimePreviousSecs = getTime();
}

void BatteryDefender::clearStateData(void) {
    mHasReachedHighCapacityLevel = false;
    mTimeActiveSecs = 0;
    mTimeChargerNotPresentSecs = 0;
    mTimeChargerPresentSecs = 0;
}

void BatteryDefender::loadPersistentStorage(void) {
    if (mIsPowerAvailable) {
        // Load accumulated time from persisted storage
        mTimeChargerPresentSecs = readFileToInt(kPathPersistChargerPresentTime);
        mTimeActiveSecs = readFileToInt(kPathPersistDefenderActiveTime);
    }
}

int64_t BatteryDefender::getTime(void) {
    return nanoseconds_to_seconds(systemTime(SYSTEM_TIME_BOOTTIME));
}

int64_t BatteryDefender::getDeltaTimeSeconds(int64_t *timeStartSecs) {
    const int64_t timeCurrentSecs = getTime();
    const int64_t timePreviousSecs = *timeStartSecs;
    *timeStartSecs = timeCurrentSecs;
    return timeCurrentSecs - timePreviousSecs;
}

void BatteryDefender::removeLineEndings(std::string *str) {
    str->erase(std::remove(str->begin(), str->end(), '\n'), str->end());
    str->erase(std::remove(str->begin(), str->end(), '\r'), str->end());
}

int BatteryDefender::readFileToInt(const char *path) {
    std::string buffer;
    int value = 0;  // default
    if (!android::base::ReadFileToString(path, &buffer)) {
        LOG(ERROR) << "Failed to read " << path;
    } else {
        removeLineEndings(&buffer);
        if (!android::base::ParseInt(buffer.c_str(), &value)) {
            LOG(ERROR) << "Failed to parse " << path;
        }
    }

    return value;
}

bool BatteryDefender::writeIntToFile(const char *path, const int value) {
    bool success = android::base::WriteStringToFile(std::to_string(value), path);
    if (!success) {
        LOG(ERROR) << "Failed to write " << path;
    }

    return success;
}

void BatteryDefender::writeTimeToFile(const char *path, const int value, int64_t *previous) {
    // 30 second delay before repeated writes
    const bool hasTimeChangedSignificantly = ((value == 0) || (*previous == -1) ||
                                              (value > *previous + 30) || (value < *previous - 30));
    if ((value != *previous) && hasTimeChangedSignificantly) {
        writeIntToFile(path, value);
        *previous = value;
    }
}

void BatteryDefender::writeChargeLevelsToFile(const int vendorStart, const int vendorStop) {
    int chargeLevelStart = vendorStart;
    int chargeLevelStop = vendorStop;
    if (mCurrentState == STATE_ACTIVE) {
        chargeLevelStart = kChargeLevelDefenderStart;
        chargeLevelStop = kChargeLevelDefenderStop;
    }

    // Disable battery defender effects in charger mode until
    // b/149598262 is resolved
    if (android::base::GetProperty(kPropBootmode, "undefined") != "charger") {
        if (chargeLevelStart != mChargeLevelStartPrevious) {
            if (writeIntToFile(kPathChargeLevelStart, chargeLevelStart)) {
                mChargeLevelStartPrevious = chargeLevelStart;
            }
        }
        if (chargeLevelStop != mChargeLevelStopPrevious) {
            if (writeIntToFile(kPathChargeLevelStop, chargeLevelStop)) {
                mChargeLevelStopPrevious = chargeLevelStop;
            }
        }
    }
}

bool BatteryDefender::isChargePowerAvailable(void) {
    // USB presence is an indicator of connectivity
    const bool chargerPresentWired = readFileToInt(kPathWiredChargerPresent) != 0;

    // Wireless online is an indicator of a device having charge power
    const bool chargerOnlineWireless = readFileToInt(kPathWirelessChargerOnline) != 0;

    return chargerPresentWired || chargerOnlineWireless;
}

bool BatteryDefender::isDefaultChargeLevel(const int start, const int stop) {
    return ((start == kChargeLevelDefaultStart) && (stop == kChargeLevelDefaultStop));
}

bool BatteryDefender::isBatteryDefenderDisabled(const int vendorStart, const int vendorStop) {
    const bool isDefaultVendorChargeLevel = isDefaultChargeLevel(vendorStart, vendorStop);
    const bool isExplicitlyDisabled =
            android::base::GetBoolProperty(kPropBatteryDefenderDisable, false);
    const bool isDebuggable = android::base::GetBoolProperty(kPropDebuggable, false);

    return isExplicitlyDisabled || (isDefaultVendorChargeLevel == false) || (isDebuggable == false);
}

void BatteryDefender::addTimeToChargeTimers(void) {
    if (mIsPowerAvailable) {
        if (mHasReachedHighCapacityLevel) {
            mTimeChargerPresentSecs += mTimeBetweenUpdateCalls;
        }
        mTimeChargerNotPresentSecs = 0;
    } else {
        mTimeChargerNotPresentSecs += mTimeBetweenUpdateCalls;
    }
}

int32_t BatteryDefender::getTimeToActivate(void) {
    // Use the default constructor value if the modified property is not between 60 and INT_MAX
    // (seconds)
    return android::base::GetIntProperty(kPropBatteryDefenderThreshold, kTimeToActivateSecs,
                                         (int32_t)ONE_MIN_IN_SECONDS, INT32_MAX);
}

void BatteryDefender::stateMachine_runAction(const state_E state) {
    switch (state) {
        case STATE_INIT:
            loadPersistentStorage();
            break;

        case STATE_DISABLED:
        case STATE_DISCONNECTED:
            clearStateData();
            break;

        case STATE_CONNECTED:
            addTimeToChargeTimers();
            if (readFileToInt(kPathBatteryCapacity) == kChargeHighCapacityLevel) {
                mHasReachedHighCapacityLevel = true;
            }
            break;

        case STATE_ACTIVE:
            addTimeToChargeTimers();
            mTimeActiveSecs += mTimeBetweenUpdateCalls;
            break;

        default:
            break;
    }

    // Must be loaded after init has set the property
    mTimeToActivateSecsModified = getTimeToActivate();
}

BatteryDefender::state_E BatteryDefender::stateMachine_getNextState(const state_E state) {
    state_E nextState = state;

    if (mIsDefenderDisabled) {
        nextState = STATE_DISABLED;
    } else {
        switch (state) {
            case STATE_INIT:
                if (mIsPowerAvailable) {
                    if (mTimeChargerPresentSecs > mTimeToActivateSecsModified) {
                        nextState = STATE_ACTIVE;
                    } else {
                        nextState = STATE_CONNECTED;
                    }
                } else {
                    nextState = STATE_DISCONNECTED;
                }
                break;

            case STATE_DISABLED:
                nextState = STATE_DISCONNECTED;
                break;

            case STATE_DISCONNECTED:
                if (mIsPowerAvailable) {
                    nextState = STATE_CONNECTED;
                }
                break;

            case STATE_CONNECTED:
                if (mTimeChargerPresentSecs > mTimeToActivateSecsModified) {
                    nextState = STATE_ACTIVE;
                } else if (mTimeChargerNotPresentSecs > kTimeToClearTimerSecs) {
                    nextState = STATE_DISCONNECTED;
                }
                break;

            case STATE_ACTIVE:
                // Latch unless disabled or unless the health module has restarted (ie. reboot)
            default:
                break;
        }
    }

    return nextState;
}

// This will run once at the rising edge of a new state transition,
// in addition to runAction()
void BatteryDefender::stateMachine_firstAction(const state_E state) {
    switch (state) {
        case STATE_DISABLED:
            clearStateData();
            LOG(INFO) << "Disabled!";
            break;

        case STATE_DISCONNECTED:
            clearStateData();
            break;

        case STATE_CONNECTED:
            // Time already accumulated on state transition implies that there has
            // already been a full charge cycle (this could happen on boot).
            if (mTimeChargerPresentSecs > 0) {
                mHasReachedHighCapacityLevel = true;
            }
            break;

        case STATE_ACTIVE:
            mHasReachedHighCapacityLevel = true;
            LOG(INFO) << "Started with " << mTimeChargerPresentSecs
                      << " seconds of power availability!";
            break;

        case STATE_INIT:
        default:
            // No actions
            break;
    }
}

void BatteryDefender::update(void) {
    // Update module inputs
    const int chargeLevelVendorStart =
            android::base::GetIntProperty(kPropChargeLevelVendorStart, kChargeLevelDefaultStart);
    const int chargeLevelVendorStop =
            android::base::GetIntProperty(kPropChargeLevelVendorStop, kChargeLevelDefaultStop);
    mIsDefenderDisabled = isBatteryDefenderDisabled(chargeLevelVendorStart, chargeLevelVendorStop);
    mIsPowerAvailable = isChargePowerAvailable();
    mTimeBetweenUpdateCalls = getDeltaTimeSeconds(&mTimePreviousSecs);

    // Run state machine
    stateMachine_runAction(mCurrentState);
    const state_E nextState = stateMachine_getNextState(mCurrentState);
    if (nextState != mCurrentState) {
        stateMachine_firstAction(nextState);
    }
    mCurrentState = nextState;

    // Store outputs
    writeTimeToFile(kPathPersistChargerPresentTime, mTimeChargerPresentSecs,
                    &mTimeChargerPresentSecsPrevious);
    writeTimeToFile(kPathPersistDefenderActiveTime, mTimeActiveSecs, &mTimeActiveSecsPrevious);
    writeChargeLevelsToFile(chargeLevelVendorStart, chargeLevelVendorStop);
    android::base::SetProperty(kPropBatteryDefenderState, stateStringMap[mCurrentState]);
}

}  // namespace health
}  // namespace pixel
}  // namespace google
}  // namespace hardware
