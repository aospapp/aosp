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

#include "chre/platform/platform_gnss.h"

#include <cinttypes>

#include "chre/core/event_loop_manager.h"
#include "chre/platform/log.h"
#include "chre/platform/shared/pal_system_api.h"

namespace chre {

const chrePalGnssCallbacks PlatformGnssBase::sGnssCallbacks = {
    PlatformGnssBase::requestStateResyncCallback,
    PlatformGnssBase::locationStatusChangeCallback,
    PlatformGnssBase::locationEventCallback,
    PlatformGnssBase::measurementStatusChangeCallback,
    PlatformGnssBase::measurementEventCallback,
};

PlatformGnss::~PlatformGnss() {
  if (mGnssApi != nullptr) {
    LOGD("Platform GNSS closing");
    prePalApiCall(PalType::GNSS);
    mGnssApi->close();
    LOGD("Platform GNSS closed");
  }
}

void PlatformGnss::init() {
  prePalApiCall(PalType::GNSS);
  mGnssApi = chrePalGnssGetApi(CHRE_PAL_GNSS_API_CURRENT_VERSION);
  if (mGnssApi != nullptr) {
    if (!mGnssApi->open(&gChrePalSystemApi, &sGnssCallbacks)) {
      LOGE("GNSS PAL open returned false");

#ifdef CHRE_TELEMETRY_SUPPORT_ENABLED
      EventLoopManagerSingleton::get()->getTelemetryManager().onPalOpenFailure(
          TelemetryManager::PalType::GNSS);
#endif  // CHRE_TELEMETRY_SUPPORT_ENABLED

      mGnssApi = nullptr;
    } else {
      LOGD("Opened GNSS PAL version 0x%08" PRIx32, mGnssApi->moduleVersion);
    }
  } else {
    LOGW("Requested GNSS PAL (version 0x%08" PRIx32 ") not found",
         CHRE_PAL_GNSS_API_CURRENT_VERSION);
  }
}

uint32_t PlatformGnss::getCapabilities() {
  if (mGnssApi != nullptr) {
    prePalApiCall(PalType::GNSS);
    return mGnssApi->getCapabilities();
  } else {
    return CHRE_GNSS_CAPABILITIES_NONE;
  }
}

bool PlatformGnss::controlLocationSession(bool enable, Milliseconds minInterval,
                                          Milliseconds minTimeToNextFix) {
  if (mGnssApi != nullptr) {
    prePalApiCall(PalType::GNSS);
    return mGnssApi->controlLocationSession(
        enable, static_cast<uint32_t>(minInterval.getMilliseconds()),
        static_cast<uint32_t>(minTimeToNextFix.getMilliseconds()));
  } else {
    return false;
  }
}

void PlatformGnss::releaseLocationEvent(chreGnssLocationEvent *event) {
  if (mGnssApi != nullptr) {
    prePalApiCall(PalType::GNSS);
    mGnssApi->releaseLocationEvent(event);
  }
}

void PlatformGnssBase::requestStateResyncCallback() {
  EventLoopManagerSingleton::get()
      ->getGnssManager()
      .handleRequestStateResyncCallback();
}

void PlatformGnssBase::locationStatusChangeCallback(bool enabled,
                                                    uint8_t errorCode) {
  EventLoopManagerSingleton::get()
      ->getGnssManager()
      .getLocationSession()
      .handleStatusChange(enabled, errorCode);
}

void PlatformGnssBase::locationEventCallback(
    struct chreGnssLocationEvent *event) {
  EventLoopManagerSingleton::get()
      ->getGnssManager()
      .getLocationSession()
      .handleReportEvent(event);
}

bool PlatformGnss::controlMeasurementSession(bool enable,
                                             Milliseconds minInterval) {
  if (mGnssApi != nullptr) {
    prePalApiCall(PalType::GNSS);
    return mGnssApi->controlMeasurementSession(
        enable, static_cast<uint32_t>(minInterval.getMilliseconds()));
  } else {
    return false;
  }
}

void PlatformGnss::releaseMeasurementDataEvent(chreGnssDataEvent *event) {
  if (mGnssApi != nullptr) {
    prePalApiCall(PalType::GNSS);
    mGnssApi->releaseMeasurementDataEvent(event);
  }
}

bool PlatformGnss::configurePassiveLocationListener(bool enable) {
  bool success = false;
  if (mGnssApi != nullptr &&
      mGnssApi->moduleVersion >= CHRE_PAL_GNSS_API_V1_2) {
    prePalApiCall(PalType::GNSS);
    success = mGnssApi->configurePassiveLocationListener(enable);
  }
  return success;
}

void PlatformGnssBase::measurementStatusChangeCallback(bool enabled,
                                                       uint8_t errorCode) {
  EventLoopManagerSingleton::get()
      ->getGnssManager()
      .getMeasurementSession()
      .handleStatusChange(enabled, errorCode);
}

void PlatformGnssBase::measurementEventCallback(
    struct chreGnssDataEvent *event) {
  EventLoopManagerSingleton::get()
      ->getGnssManager()
      .getMeasurementSession()
      .handleReportEvent(event);
}

}  // namespace chre
