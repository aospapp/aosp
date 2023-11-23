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

#include "calibration/nano_calibration/nano_calibration.h"

#include <cstdint>
#include <cstring>

#include "common/techeng_log_util.h"

namespace nano_calibration {
namespace {

// Common log message sensor-specific identifiers.
char const *kAccelTag = "[ACCEL_MPS2]";
char const *kGyroTag = "[GYRO_RPS]";
char const *kMagTag = "[MAG_UT]";

// Defines a plan for limiting log messages so that upon initialization there
// begins a period set by 'duration_of_rapid_messages_min' where log messages
// appear at a rate set by 'rapid_message_interval_sec'. Afterwards, log
// messages will be produced at a rate determined by
// 'slow_message_interval_min'.
struct LogMessageRegimen {
  uint8_t rapid_message_interval_sec;  // Assists device verification.
  uint8_t slow_message_interval_min;   // Avoids long-term log spam.
  uint8_t duration_of_rapid_messages_min;
};

constexpr LogMessageRegimen kGyroscopeMessagePlan = {
    /*rapid_message_interval_sec*/ 20,
    /*slow_message_interval_min*/ 5,
    /*duration_of_rapid_messages_min*/ 3};

using ::online_calibration::CalibrationDataThreeAxis;
using ::online_calibration::CalibrationTypeFlags;
using ::online_calibration::SensorType;

// NanoSensorCal logging macros.
#ifndef LOG_TAG
#define LOG_TAG "[ImuCal]"
#endif

// Some devices do not have multisensor ASH API support. These macros remap to
// single-sensor functions.
#ifndef ASH_MULTI_CAL_SUPPORTED
#define ashSetMultiCalibration(chre_sensor_type, sensor_index,  \
                               calibration_index, ash_cal_info) \
  ashSetCalibration(chre_sensor_type, ash_cal_info)

#define ashSaveMultiCalibrationParams(chre_sensor_type, sensor_index,        \
                                      calibration_index, ash_cal_parameters) \
  ashSaveCalibrationParams(chre_sensor_type, ash_cal_parameters)

#define ashLoadMultiCalibrationParams(chre_sensor_type, sensor_index, \
                                      calibration_index,              \
                                      recalled_ash_cal_parameters)    \
  ashLoadCalibrationParams(chre_sensor_type, ASH_CAL_STORAGE_ASH,     \
                           recalled_ash_cal_parameters)
#endif  // ASH_MULTI_CAL_SUPPORTED

#ifdef NANO_SENSOR_CAL_DBG_ENABLED
#define NANO_CAL_LOGD(tag, format, ...) \
  TECHENG_LOGD("%s " format, tag, ##__VA_ARGS__)
#define NANO_CAL_LOGW(tag, format, ...) \
  TECHENG_LOGW("%s " format, tag, ##__VA_ARGS__)
#define NANO_CAL_LOGE(tag, format, ...) \
  TECHENG_LOGE("%s " format, tag, ##__VA_ARGS__)
#else
#define NANO_CAL_LOGD(tag, format, ...) techeng_log_null(format, ##__VA_ARGS__)
#define NANO_CAL_LOGW(tag, format, ...) techeng_log_null(format, ##__VA_ARGS__)
#define NANO_CAL_LOGE(tag, format, ...) techeng_log_null(format, ##__VA_ARGS__)
#endif  // NANO_SENSOR_CAL_DBG_ENABLED

// NOTE: LOGI is defined to ensure calibration updates are always logged for
// field diagnosis and verification.
#define NANO_CAL_LOGI(tag, format, ...) \
  TECHENG_LOGI("%s " format, tag, ##__VA_ARGS__)

bool GetCalMetaData(const NanoSensorCal::OnlineCalibrationThreeAxis &online_cal,
                    uint8_t *chre_sensor_type, char const **sensor_tag,
                    uint8_t *sensor_index, uint8_t *calibration_index) {
  *chre_sensor_type = 0;
  *sensor_tag = nullptr;
  *sensor_index = online_cal.get_sensor_index();
  *calibration_index = online_cal.get_calibration_index();

  switch (online_cal.get_sensor_type()) {
    case SensorType::kAccelerometerMps2:
      *chre_sensor_type = CHRE_SENSOR_TYPE_ACCELEROMETER;
      *sensor_tag = kAccelTag;
      break;
    case SensorType::kGyroscopeRps:
      *chre_sensor_type = CHRE_SENSOR_TYPE_GYROSCOPE;
      *sensor_tag = kGyroTag;
      break;
    case SensorType::kMagnetometerUt:
      *chre_sensor_type = CHRE_SENSOR_TYPE_GEOMAGNETIC_FIELD;
      *sensor_tag = kMagTag;
      break;
    default:
      NANO_CAL_LOGW("[NanoSensorCal]", "Unexpected sensor calibration (%d).",
                    static_cast<int>(online_cal.get_sensor_type()));
      return false;
  }
  return true;
}

}  // namespace

void NanoSensorCal::UpdateCalibration(
    online_calibration::CalibrationTypeFlags new_cal_flags,
    const OnlineCalibrationThreeAxis &online_cal) {
  if (new_cal_flags != CalibrationTypeFlags::NONE) {
    uint8_t chre_sensor_type = 0;
    char const *sensor_tag = nullptr;
    uint8_t sensor_index = 0;
    uint8_t calibration_index = 0;
    if (GetCalMetaData(online_cal, &chre_sensor_type, &sensor_tag,
                       &sensor_index, &calibration_index)) {
      NotifyAshCalibration(chre_sensor_type, sensor_index, calibration_index,
                           online_cal.GetSensorCalibration(),
                           online_cal.which_calibration_flags(), sensor_tag);
    }
  }
}

bool NanoSensorCal::NotifyAshCalibration(
    uint8_t chre_sensor_type, uint8_t sensor_index, uint8_t calibration_index,
    const CalibrationDataThreeAxis &cal_data, CalibrationTypeFlags flags,
    char const *sensor_tag) {
  bool is_log_update_allowed = true;
  bool send_results_callback = true;

  if (chre_sensor_type == CHRE_SENSOR_TYPE_GYROSCOPE) {
    // Rate-limits OTC gyro log updates since they can happen frequently with
    // temperature changes. However, all GyroCal stillness and OTC model
    // parameter updates will be reported through the results callback.
    is_log_update_allowed =
        IsGyroLogUpdateAllowed(cal_data.cal_update_time_nanos);

    send_results_callback =
        is_log_update_allowed || flags != CalibrationTypeFlags::BIAS;
  }

  if (is_log_update_allowed) {
    PrintCalibration(cal_data, sensor_index, calibration_index, flags,
                     sensor_tag);
  }

  if (result_callback_ != nullptr && send_results_callback) {
    result_callback_->SetCalibrationEvent(cal_data.cal_update_time_nanos,
                                          cal_data.type, sensor_index,
                                          calibration_index, flags, cal_data);
  }

  // Updates the sensor offset calibration using the ASH API.
  ashCalInfo ash_cal_info;
  memset(&ash_cal_info, 0, sizeof(ashCalInfo));
  ash_cal_info.compMatrix[0] = 1.0f;  // Sets diagonal to unity (scale factor).
  ash_cal_info.compMatrix[4] = 1.0f;
  ash_cal_info.compMatrix[8] = 1.0f;
  memcpy(ash_cal_info.bias, cal_data.offset, sizeof(ash_cal_info.bias));

  // Maps CalibrationQualityLevel to ASH calibration accuracy.
  switch (cal_data.calibration_quality.level) {
    case online_calibration::CalibrationQualityLevel::HIGH_QUALITY:
      ash_cal_info.accuracy = ASH_CAL_ACCURACY_HIGH;
      break;

    case online_calibration::CalibrationQualityLevel::MEDIUM_QUALITY:
      ash_cal_info.accuracy = ASH_CAL_ACCURACY_MEDIUM;
      break;

    case online_calibration::CalibrationQualityLevel::LOW_QUALITY:
      ash_cal_info.accuracy = ASH_CAL_ACCURACY_LOW;
      break;

    default:
      ash_cal_info.accuracy = ASH_CAL_ACCURACY_UNRELIABLE;
      break;
  }

  if (!ashSetMultiCalibration(chre_sensor_type, sensor_index, calibration_index,
                              &ash_cal_info)) {
    NANO_CAL_LOGE(sensor_tag, "ASH failed to apply calibration update.");
    return false;
  }

  // Uses the ASH API to store all calibration parameters relevant to a given
  // algorithm as indicated by the input calibration type flags.
  ashCalParams ash_cal_parameters;
  memset(&ash_cal_parameters, 0, sizeof(ashCalParams));
  if (flags & CalibrationTypeFlags::BIAS) {
    ash_cal_parameters.offsetTempCelsius = cal_data.offset_temp_celsius;
    memcpy(ash_cal_parameters.offset, cal_data.offset,
           sizeof(ash_cal_parameters.offset));
    ash_cal_parameters.offsetSource = ASH_CAL_PARAMS_SOURCE_RUNTIME;
    ash_cal_parameters.offsetTempCelsiusSource = ASH_CAL_PARAMS_SOURCE_RUNTIME;
  }

  if (flags & CalibrationTypeFlags::OVER_TEMP) {
    memcpy(ash_cal_parameters.tempSensitivity, cal_data.temp_sensitivity,
           sizeof(ash_cal_parameters.tempSensitivity));
    memcpy(ash_cal_parameters.tempIntercept, cal_data.temp_intercept,
           sizeof(ash_cal_parameters.tempIntercept));
    ash_cal_parameters.tempSensitivitySource = ASH_CAL_PARAMS_SOURCE_RUNTIME;
    ash_cal_parameters.tempInterceptSource = ASH_CAL_PARAMS_SOURCE_RUNTIME;
  }

  if (!ashSaveMultiCalibrationParams(chre_sensor_type, sensor_index,
                                     calibration_index, &ash_cal_parameters)) {
    NANO_CAL_LOGE(sensor_tag, "ASH failed to write calibration update.");
    return false;
  }

  return true;
}

void NanoSensorCal::LoadAshCalibration(OnlineCalibrationThreeAxis *online_cal) {
  uint8_t chre_sensor_type = 0;
  char const *sensor_tag = nullptr;
  uint8_t sensor_index = 0;
  uint8_t calibration_index = 0;
  ashCalParams recalled_ash_cal_parameters = {};

  // Resets the rate limiter for gyro calibration update messages.
  initial_gyro_cal_time_nanos_ = 0;

  if (!GetCalMetaData(*online_cal, &chre_sensor_type, &sensor_tag,
                      &sensor_index, &calibration_index) ||
      !ashLoadMultiCalibrationParams(chre_sensor_type, sensor_index,
                                     calibration_index,
                                     &recalled_ash_cal_parameters)) {
    // This is not necessarily an error since there may not be any previously
    // stored runtime calibration data to load yet (e.g., first device boot).
    NANO_CAL_LOGW(sensor_tag, "ASH did not recall calibration data.");
    return;
  }

  // Checks whether a valid set of runtime calibration parameters was received
  // and can be used for initialization.
  online_calibration::CalibrationTypeFlags flags = CalibrationTypeFlags::NONE;
  if (DetectRuntimeCalibration(chre_sensor_type, sensor_tag, sensor_index,
                               calibration_index, recalled_ash_cal_parameters,
                               flags)) {
    CalibrationDataThreeAxis cal_data;
    cal_data.type = online_cal->get_sensor_type();
    cal_data.cal_update_time_nanos = chreGetTime();

    // Analyzes the calibration flags and sets only the runtime calibration
    // values that were received.
    if (flags & CalibrationTypeFlags::BIAS) {
      cal_data.offset_temp_celsius =
          recalled_ash_cal_parameters.offsetTempCelsius;
      memcpy(cal_data.offset, recalled_ash_cal_parameters.offset,
             sizeof(cal_data.offset));
    }

    if (flags & CalibrationTypeFlags::OVER_TEMP) {
      memcpy(cal_data.temp_sensitivity,
             recalled_ash_cal_parameters.tempSensitivity,
             sizeof(cal_data.temp_sensitivity));
      memcpy(cal_data.temp_intercept, recalled_ash_cal_parameters.tempIntercept,
             sizeof(cal_data.temp_intercept));
    }

    // Sets the algorithm's initial calibration data and notifies ASH to apply
    // the recalled calibration data.
    if (online_cal->SetInitialCalibration(cal_data)) {
      NotifyAshCalibration(chre_sensor_type, sensor_index, calibration_index,
                           online_cal->GetSensorCalibration(), flags,
                           sensor_tag);
    } else {
      NANO_CAL_LOGE(sensor_tag,
                    "Calibration data failed to initialize algorithm.");
    }
  }
}

bool NanoSensorCal::DetectRuntimeCalibration(
    uint8_t chre_sensor_type, const char *sensor_tag, uint8_t sensor_index,
    uint8_t calibration_index, const ashCalParams &ash_cal_parameters,
    CalibrationTypeFlags &flags) {
  // Analyzes calibration source flags to determine whether runtime
  // calibration values have been loaded and may be used for initialization. A
  // valid runtime calibration source will include at least an offset.
  flags = CalibrationTypeFlags::NONE;  // Resets the calibration flags.

  // Uses the ASH calibration source flags to set the appropriate
  // CalibrationTypeFlags. These will be used to determine which values to copy
  // from 'ash_cal_parameters' and provide to the calibration algorithms for
  // initialization.
  bool runtime_cal_detected = false;
  if (ash_cal_parameters.offsetSource == ASH_CAL_PARAMS_SOURCE_RUNTIME &&
      ash_cal_parameters.offsetTempCelsiusSource ==
          ASH_CAL_PARAMS_SOURCE_RUNTIME) {
    runtime_cal_detected = true;
    flags = CalibrationTypeFlags::BIAS;
  }

  if (ash_cal_parameters.tempSensitivitySource ==
          ASH_CAL_PARAMS_SOURCE_RUNTIME &&
      ash_cal_parameters.tempInterceptSource == ASH_CAL_PARAMS_SOURCE_RUNTIME) {
    flags |= CalibrationTypeFlags::OVER_TEMP;
  }

  if (runtime_cal_detected) {
    // Prints the retrieved runtime calibration data.
    NANO_CAL_LOGD(sensor_tag, "Runtime calibration data detected.");
  } else {
    // This is a warning (not an error) since the runtime algorithms will
    // function correctly with no recalled calibration values. They will
    // eventually trigger and update the system with valid calibration data.
    NANO_CAL_LOGW(sensor_tag, "No runtime offset calibration data found.");
  }

  return runtime_cal_detected;
}

void NanoSensorCal::PrintCalibration(const CalibrationDataThreeAxis &cal_data,
                                     uint8_t sensor_index,
                                     uint8_t calibration_index,
                                     CalibrationTypeFlags flags,
                                     const char *sensor_tag) {
  if (flags & CalibrationTypeFlags::BIAS) {
    NANO_CAL_LOGI(
        sensor_tag,
        "(%d, %d) Offset | Temp [C] | Quality: %.6f, %.6f, %.6f | %.2f | %d",
        sensor_index, calibration_index, cal_data.offset[0], cal_data.offset[1],
        cal_data.offset[2], cal_data.offset_temp_celsius,
        static_cast<int>(cal_data.calibration_quality.level));
  }

  if (flags & CalibrationTypeFlags::OVER_TEMP) {
    NANO_CAL_LOGI(sensor_tag, "(%d) Temp Sensitivity: %.6f, %.6f, %.6f",
                  sensor_index, cal_data.temp_sensitivity[0],
                  cal_data.temp_sensitivity[1], cal_data.temp_sensitivity[2]);
    NANO_CAL_LOGI(sensor_tag, "(%d) Temp Intercept: %.6f, %.6f, %.6f",
                  sensor_index, cal_data.temp_intercept[0],
                  cal_data.temp_intercept[1], cal_data.temp_intercept[2]);
  }
}

bool NanoSensorCal::IsGyroLogUpdateAllowed(uint64_t timestamp_nanos) {
  if (initial_gyro_cal_time_nanos_ == 0) {
    initial_gyro_cal_time_nanos_ = timestamp_nanos;
    gyro_notification_time_nanos_ = timestamp_nanos;
    return true;
  }

  // Limits the log messaging update rate for the gyro calibrations since
  // these can occur frequently with rapid temperature changes.
  const int64_t next_log_interval_nanos =
      (NANO_TIMER_CHECK_T1_GEQUAL_T2_PLUS_DELTA(
          /*t1=*/timestamp_nanos, /*t2=*/initial_gyro_cal_time_nanos_,
          MIN_TO_NANOS(kGyroscopeMessagePlan.duration_of_rapid_messages_min)))
          ? MIN_TO_NANOS(kGyroscopeMessagePlan.slow_message_interval_min)
          : SEC_TO_NANOS(kGyroscopeMessagePlan.rapid_message_interval_sec);

  const bool is_log_update_allowed = NANO_TIMER_CHECK_T1_GEQUAL_T2_PLUS_DELTA(
      /*t1=*/timestamp_nanos, /*t2=*/gyro_notification_time_nanos_,
      /*t_delta=*/next_log_interval_nanos);

  if (is_log_update_allowed) {
    gyro_notification_time_nanos_ = timestamp_nanos;
  }

  return is_log_update_allowed;
}

}  // namespace nano_calibration
