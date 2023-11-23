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
#define LOG_TAG "mitigation-logger"

#include <android-base/chrono_utils.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <battery_mitigation/MitigationThermalManager.h>
#include <errno.h>
#include <utils/Log.h>

#include <chrono>
#include <ctime>
#include <iomanip>
#include <iostream>
#include <string>
#include <thread>

#define NUM_OF_SAMPLES     20
#define CAPTURE_INTERVAL_S 2     /* 2 seconds between new capture */

namespace android {
namespace hardware {
namespace google {
namespace pixel {

MitigationThermalManager &MitigationThermalManager::getInstance() {
    static MitigationThermalManager mitigationThermalManager;
    return mitigationThermalManager;
}

void MitigationThermalManager::remove() {
    const std::lock_guard<std::mutex> lock(thermal_hal_mutex_);
    if (!thermal) {
        return;
    }
    if (callback) {
        auto ret = thermal->unregisterThermalChangedCallback(callback);
        if (!ret.isOk()) {
            LOG(ERROR) << "Failed to release thermal callback! " << ret.getMessage();
        }
    }
}

MitigationThermalManager::MitigationThermalManager() {
    if (!ABinderProcess_isThreadPoolStarted()) {
        // if no thread pool then the thermal callback will not work, so abort
        LOG(ERROR) << "The user of MitigationThermalManager did not start a thread pool!";
        return;
    }
    if (!connectThermalHal()) {
        remove();
    }
}

MitigationThermalManager::~MitigationThermalManager() {
    remove();
}

void MitigationThermalManager::updateConfig(const struct MitigationConfig::Config &cfg) {
    kLogFilePath = std::string(cfg.LogFilePath);
    kSystemPath = cfg.SystemPath;
    kSystemName = cfg.SystemName;
    kFilteredZones = cfg.FilteredZones;
    kTimestampFormat = cfg.TimestampFormat;
}

bool MitigationThermalManager::connectThermalHal() {
    const std::string thermal_instance_name =
            std::string(::aidl::android::hardware::thermal::IThermal::descriptor) + "/default";
    std::shared_ptr<aidl::android::hardware::thermal::IThermal> thermal_aidl_service;
    const std::lock_guard<std::mutex> lock(thermal_hal_mutex_);
    if (AServiceManager_isDeclared(thermal_instance_name.c_str())) {
        thermal = ::aidl::android::hardware::thermal::IThermal::fromBinder(
                ndk::SpAIBinder(AServiceManager_waitForService(thermal_instance_name.c_str())));
        lastCapturedTime = ::android::base::boot_clock::now();
        return registerCallback();
    } else {
        LOG(ERROR) << "Thermal AIDL service is not declared";
    }
    return false;
}

bool MitigationThermalManager::isMitigationTemperature(const Temperature &temperature) {
    if (std::find(kFilteredZones.begin(), kFilteredZones.end(), temperature.name) !=
            kFilteredZones.end()) {
        return true;
    }
    return false;
}

void MitigationThermalManager::thermalCb(const Temperature &temperature) {
    if ((temperature.throttlingStatus == ThrottlingSeverity::NONE) ||
          (!isMitigationTemperature(temperature))) {
        return;
    }
    auto currentTime = ::android::base::boot_clock::now();
    auto delta =
            std::chrono::duration_cast<std::chrono::seconds>(currentTime - lastCapturedTime);
    if (delta.count() < CAPTURE_INTERVAL_S) {
        /* Do not log if delta is within 2 seconds */
        return;
    }
    int flag = O_WRONLY | O_CREAT | O_NOFOLLOW | O_CLOEXEC | O_APPEND | O_TRUNC;
    android::base::unique_fd fd(TEMP_FAILURE_RETRY(open(kLogFilePath.c_str(), flag, 0644)));
    lastCapturedTime = currentTime;
    std::stringstream oss;
    oss << temperature.name << " triggered at " << temperature.value << std::endl << std::flush;
    android::base::WriteStringToFd(oss.str(), fd);
    fsync(fd);

    for (int i = 0; i < NUM_OF_SAMPLES; i++) {
        auto now = std::chrono::system_clock::now();
        auto time_sec = std::chrono::system_clock::to_time_t(now);
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()) -
                std::chrono::duration_cast<std::chrono::seconds>(now.time_since_epoch());
        struct tm now_tm;
        localtime_r(&time_sec, &now_tm);
        oss << std::put_time(&now_tm, kTimestampFormat.c_str()) << "." << std::setw(3)
            << std::setfill('0') << ms.count() << std::endl
            << std::flush;
        android::base::WriteStringToFd(oss.str(), fd);
        fsync(fd);
        oss.str("");
        /* log System info */
        for (int j = 0; j < kSystemName.size(); j++) {
            std::string value;
            bool result = android::base::ReadFileToString(kSystemPath[j], &value);
            if (!result) {
                LOG(ERROR) << "Could not read: " << kSystemName[j];
            }
            android::base::WriteStringToFd(kSystemName[j] + ":" + value, fd);
        }
    }
    fsync(fd);
}

bool MitigationThermalManager::registerCallback() {
    if (!thermal) {
        LOG(ERROR) << "Unable to connect Thermal AIDL service";
        return false;
    }
    // Create thermal callback recipient object.
    if (callback == nullptr) {
        callback =
                ndk::SharedRefBase::make<ThermalCallback>([this](const Temperature &temperature) {
                    std::lock_guard api_lock(thermal_callback_mutex_);
                    thermalCb(temperature);
                });
    }
    // Register thermal callback to thermal hal to cover all.  Cannot register twice.
    auto ret_callback = thermal->registerThermalChangedCallback(callback);
    if (!ret_callback.isOk()) {
        LOG(ERROR) << "Failed to register thermal callback! " << ret_callback.getMessage();
        return false;
    }

    // Create AIDL thermal death recipient object.
    if (aidlDeathRecipient.get() == nullptr) {
        aidlDeathRecipient = ndk::ScopedAIBinder_DeathRecipient(
                AIBinder_DeathRecipient_new(onThermalAidlBinderDied));
    }
    auto linked = AIBinder_linkToDeath(thermal->asBinder().get(), aidlDeathRecipient.get(), this);
    if (linked != STATUS_OK) {
        // should we continue if the death recipient is not registered?
        LOG(ERROR) << "Failed to register AIDL thermal death notification";
    }
    return true;
}

}  // namespace pixel
}  // namespace google
}  // namespace hardware
}  // namespace android
