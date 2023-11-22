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

#include <cinttypes>

#include <chre.h>
#include "chre/util/macros.h"
#include "chre/util/nanoapp/log.h"
#include "chre/util/nanoapp/sensor.h"
#include "chre/util/time.h"
#include "nano_calibration.h"

#define LOG_TAG "[ImuCal]"

#ifdef CHRE_NANOAPP_INTERNAL
namespace chre {
namespace {
#endif  // CHRE_NANOAPP_INTERNAL

namespace {

struct SensorState {
  uint32_t handle;
  const uint8_t type;
  bool isInitialized;
  bool enable;
  // Sample interval/latency defined in nanoseconds
  uint64_t interval;
  uint64_t highPerformanceLatency;
  uint64_t standByLatency;
};

// Dynamic sensor latency settings.
constexpr uint64_t kDefaultHighPerformanceLatency =
    Milliseconds(500).toRawNanoseconds();

constexpr uint64_t kDefaultStandByLatency = Seconds(1).toRawNanoseconds();

// Tracks the ON/OFF state of the gyro.
bool gGyroEnabled = false;

// Defines the indices for the following sensor array definition.
enum SensorIndex {
  SENSOR_INDEX_TEMP = 0,
  SENSOR_INDEX_ACCEL = 1,
  SENSOR_INDEX_GYRO = 2,
  SENSOR_INDEX_MAG = 3,
};

SensorState sensors[] = {
  [SENSOR_INDEX_TEMP] = {
    .type = CHRE_SENSOR_TYPE_ACCELEROMETER_TEMPERATURE,
    .enable = true,
    .interval = Milliseconds(500).toRawNanoseconds(),
    .highPerformanceLatency = 0,
    // TODO(b/63908396): this sensor should be disabled in stand-by mode
    .standByLatency = Seconds(60).toRawNanoseconds(),
  },
  [SENSOR_INDEX_ACCEL] = {
    .type = CHRE_SENSOR_TYPE_UNCALIBRATED_ACCELEROMETER,
    .enable = true,
    .interval = Milliseconds(10).toRawNanoseconds(),
    .highPerformanceLatency = kDefaultHighPerformanceLatency,
    .standByLatency = kDefaultStandByLatency,
  },
  [SENSOR_INDEX_GYRO] = {
    .type = CHRE_SENSOR_TYPE_UNCALIBRATED_GYROSCOPE,
    .enable = true,
    .interval = Milliseconds(10).toRawNanoseconds(),
    .highPerformanceLatency = kDefaultHighPerformanceLatency,
    .standByLatency = kDefaultHighPerformanceLatency,
  },
  [SENSOR_INDEX_MAG] = {
    .type = CHRE_SENSOR_TYPE_UNCALIBRATED_GEOMAGNETIC_FIELD,
    .enable = true,
    .interval = Milliseconds(20).toRawNanoseconds(),
    .highPerformanceLatency = kDefaultHighPerformanceLatency,
    .standByLatency = kDefaultStandByLatency,
  },
};

// Container for all runtime calibration algorithms.
nano_calibration::NanoSensorCal nanoCal;

// Configures the Nanoapp's sensors with special adjustment of accel/gyro/mag
// sensor latency based on whether high-performance mode is requested.
void nanoappDynamicConfigure(bool highPerformance) {
  LOGD("Dynamic sensor configuration: %s.",
       (highPerformance) ? "high-performance" : "stand-by");

  // Configures all sensors.
  for (size_t i = 0; i < ARRAY_SIZE(sensors); i++) {
    SensorState &sensor = sensors[i];
    if (!sensor.enable) {
      // Only configure enabled sensors.
      continue;
    }

    // Update the requested latency according to the requested mode.
    uint64_t latency = (highPerformance)
        ? sensor.highPerformanceLatency : sensor.standByLatency;

    bool configStatus = chreSensorConfigure(
        sensor.handle, CHRE_SENSOR_CONFIGURE_MODE_PASSIVE_CONTINUOUS,
        sensor.interval, latency);

    if (!configStatus) {
      LOGE("Requested config. failed: handle %" PRIu32 ", interval %" PRIu64
           " nanos, latency %" PRIu64 " nanos",
           sensor.handle, sensor.interval, latency);
    }
  }
}
}  // namespace

bool nanoappStart() {
  LOGI("App started on platform ID %" PRIx64, chreGetPlatformId());

  // Initialize all sensors to populate their handles.
  for (size_t i = 0; i < ARRAY_SIZE(sensors); i++) {
    SensorState &sensor = sensors[i];
    sensor.isInitialized = chreSensorFindDefault(sensor.type, &sensor.handle);

    // TODO: Handle error condition.
    if (!sensor.isInitialized) {
      LOGE("Sensor handle %" PRIu32 " failed to initialize.", sensor.handle);
    }
  }

  // Determine initial gyro state
  struct chreSensorSamplingStatus status;
  if (chreGetSensorSamplingStatus(sensors[SENSOR_INDEX_GYRO].handle, &status)) {
    gGyroEnabled = status.enabled;
  } else {
    LOGE("Failed to get gyro sampling status.");
  }

  // Configure the Nanoapp's sensors.
  nanoappDynamicConfigure(gGyroEnabled);

  // Checks to see if the accelerometer and magnetometer were initialized.
  bool accelIsInitialized = sensors[SENSOR_INDEX_ACCEL].isInitialized;
  bool magIsInitialized = sensors[SENSOR_INDEX_MAG].isInitialized;

  // Checks for the minimimal conditions for a nanoCal to have an active
  // calibration algorithm running.
  //  Sensor Requirements:
  //  - GyroCal:  accelerometer, gyroscope, magnetometer [optional]
  //  - OTC-Gyro: GyroCal required sensors + temperature
  //  - AccelCal: accelerometer
  //  - MagCal:   magnetometer
  if (accelIsInitialized || magIsInitialized) {
    nanoCal.Initialize();
  } else {
    LOGE(
        "None of the required sensors to enable a runtime calibration were "
        "successfully initialized.");
  }

  return true;
}

void nanoappHandleEvent(uint32_t senderInstanceId, uint16_t eventType,
                        const void *eventData) {
  switch (eventType) {
    case CHRE_EVENT_SENSOR_UNCALIBRATED_ACCELEROMETER_DATA:
    case CHRE_EVENT_SENSOR_UNCALIBRATED_GYROSCOPE_DATA:
    case CHRE_EVENT_SENSOR_UNCALIBRATED_GEOMAGNETIC_FIELD_DATA: {
      nanoCal.HandleSensorSamples(
          eventType, static_cast<const chreSensorThreeAxisData *>(eventData));
      break;
    }

    case CHRE_EVENT_SENSOR_ACCELEROMETER_TEMPERATURE_DATA: {
      nanoCal.HandleTemperatureSamples(
          eventType, static_cast<const chreSensorFloatData *>(eventData));
      break;
    }

    case CHRE_EVENT_SENSOR_SAMPLING_CHANGE: {
      const auto *ev =
          static_cast<const chreSensorSamplingStatusEvent *>(eventData);

      // Is this the gyro? Check the handle.
      if (sensors[SENSOR_INDEX_GYRO].isInitialized &&
          ev->sensorHandle == sensors[SENSOR_INDEX_GYRO].handle &&
          ev->status.enabled != gGyroEnabled) {
        // Modify sensor latency based on whether Gyro is enabled.
        gGyroEnabled = ev->status.enabled;
        nanoappDynamicConfigure(gGyroEnabled);
      }
      break;
    }

    default:
      LOGW("Unhandled event %d", eventType);
      break;
  }
}

void nanoappEnd() {
  // TODO: Unsubscribe to sensors
  LOGI("Stopped");
}

#ifdef CHRE_NANOAPP_INTERNAL
}  // anonymous namespace
}  // namespace chre

#include "chre/platform/static_nanoapp_init.h"
#include "chre/util/nanoapp/app_id.h"

CHRE_STATIC_NANOAPP_INIT(ImuCal, chre::kImuCalAppId, 0);
#endif  // CHRE_NANOAPP_INTERNAL
