/*
 * Copyright (C) 2022 The Android Open Source Project
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

#pragma once

#ifndef MITIGATION_THERMAL_MANAGER_H_
#define MITIGATION_THERMAL_MANAGER_H_

#include <aidl/android/hardware/thermal/BnThermalChangedCallback.h>
#include <aidl/android/hardware/thermal/IThermal.h>
#include <android-base/chrono_utils.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android/binder_auto_utils.h>
#include <unistd.h>
#include <utils/Mutex.h>

#include <fstream>
#include <iostream>
#include <regex>

#include "MitigationConfig.h"

namespace android {
namespace hardware {
namespace google {
namespace pixel {

using ::aidl::android::hardware::thermal::BnThermalChangedCallback;
using ::aidl::android::hardware::thermal::IThermal;
using ::aidl::android::hardware::thermal::Temperature;
using ::aidl::android::hardware::thermal::TemperatureType;
using ::aidl::android::hardware::thermal::ThrottlingSeverity;
using android::hardware::google::pixel::MitigationConfig;

class MitigationThermalManager {
  public:
    static MitigationThermalManager &getInstance();

    // delete copy and move constructors and assign operators
    MitigationThermalManager(MitigationThermalManager const &) = delete;
    MitigationThermalManager(MitigationThermalManager &&) = delete;
    MitigationThermalManager &operator=(MitigationThermalManager const &) = delete;
    MitigationThermalManager &operator=(MitigationThermalManager &&) = delete;

  private:
    // ThermalCallback implements the HIDL thermal changed callback
    // interface, IThermalChangedCallback.
    void thermalCb(const Temperature &temperature);
    android::base::boot_clock::time_point lastCapturedTime;

    class ThermalCallback : public BnThermalChangedCallback {
      public:
        ThermalCallback(std::function<void(const Temperature &)> notify_function)
            : notifyFunction(notify_function) {}

        // Callback function. thermal service will call this.
        ndk::ScopedAStatus notifyThrottling(const Temperature &temperature) override {
            if ((temperature.type == TemperatureType::BCL_VOLTAGE) ||
                (temperature.type == TemperatureType::BCL_CURRENT)) {
                notifyFunction(temperature);
            }
            return ndk::ScopedAStatus::ok();
        }

      private:
        std::function<void(const Temperature &)> notifyFunction;
    };

    static void onThermalAidlBinderDied(void *) {
        LOG(ERROR) << "Thermal AIDL service died, trying to reconnect";
        MitigationThermalManager::getInstance().connectThermalHal();
    }

  public:
    MitigationThermalManager();
    ~MitigationThermalManager();
    bool connectThermalHal();
    bool isMitigationTemperature(const Temperature &temperature);
    bool registerCallback();
    void remove();
    void updateConfig(const struct MitigationConfig::Config &cfg);


  private:
    std::mutex thermal_callback_mutex_;
    std::mutex thermal_hal_mutex_;
    // Thermal hal interface.
    std::shared_ptr<IThermal> thermal;
    // Thermal hal callback object.
    std::shared_ptr<ThermalCallback> callback;
    // Receiver when AIDL thermal hal restart.
    ndk::ScopedAIBinder_DeathRecipient aidlDeathRecipient;
    std::vector<std::string> kSystemPath;
    std::vector<std::string> kFilteredZones;
    std::vector<std::string> kSystemName;
    std::string kLogFilePath;
    std::string kTimestampFormat;
};

}  // namespace pixel
}  // namespace google
}  // namespace hardware
}  // namespace android
#endif
