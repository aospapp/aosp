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

/*
 * This module provides a helper class for storage, recall, and updating of
 * calibration data using the ASH (Android Sensor Hub) API for dynamic runtime
 * calibration algorithms that affect the following sensors:
 *       - Accelerometer (offset)
 *       - Gyroscope (offset, with over-temperature compensation)
 *       - Magnetometer (offset)
 *
 * Sensor Units:
 *       - Accelerometer [meters/sec^2]
 *       - Gyroscope     [radian/sec]
 *       - Magnetometer  [micro Tesla, uT]
 *       - Temperature   [Celsius].
 *
 * INPUTS:
 *   This module uses pointers to runtime calibration algorithm objects.
 *   These must be constructed and initialized outside of this class. The owner
 *   bears the burden of managing the lifetime of these objects with respect to
 *   the NanoSensorCal class which depends on these objects and handles their
 *   interaction with the Android ASH/CHRE system. This arrangement makes it
 *   convenient to abstract the specific algorithm implementations (i.e., choice
 *   of calibration algorithm, parameter tuning, etc.) at the nanoapp level
 *   without the need to specialize the standard functionality implemented here.
 *
 *     OnlineCalibration<CalibrationDataThreeAxis> *online_cal
 *       Pointer to the sensor calibration algorithm that provides calibration
 *       updates.
 *
 * NOTE: Define NANO_SENSOR_CAL_DBG_ENABLED to enable debug messaging.
 */

#ifndef LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_NANO_CALIBRATION_NANO_CALIBRATION_H_
#define LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_NANO_CALIBRATION_NANO_CALIBRATION_H_

#include <ash.h>
#include <chre.h>
#include <stdbool.h>
#include <stdint.h>

#include <cstdint>

#include "calibration/online_calibration/common_data/calibration_data.h"
#include "calibration/online_calibration/common_data/online_calibration.h"
#include "calibration/online_calibration/common_data/result_callback_interface.h"
#include "calibration/online_calibration/common_data/sensor_data.h"
#include "common/math/macros.h"

namespace nano_calibration {

/*
 * NanoSensorCal is a helper class for dynamic runtime calibration sensor
 * algorithms used by the IMU_Cal CHRE nanoapp. The main purpose of this class
 * is to manage sensor calibration data persistence (storage & recall), and to
 * provide calibration updates to CHRE using the ASH API.
 */
class NanoSensorCal {
 public:
  // Alias used to reference the three-axis OnlineCalibration baseclass used by
  // the runtime calibration sensor wrappers. This is for convenience and to
  // help with code readability.
  using OnlineCalibrationThreeAxis = online_calibration::OnlineCalibration<
      online_calibration::CalibrationDataThreeAxis>;

  NanoSensorCal() = default;

  // Provides ASH calibration updates using the sensor calibration associated
  // with the 'online_cal' algorithm. The input bit mask 'new_cal_flags'
  // describe the new calibration elements to update.
  void UpdateCalibration(online_calibration::CalibrationTypeFlags new_cal_flags,
                         const OnlineCalibrationThreeAxis &online_cal);

  // Loads runtime calibration data from the system registry using ASH. This is
  // usually called once whenever the owning runtime calibration algorithm is
  // initialized.
  void LoadAshCalibration(OnlineCalibrationThreeAxis *online_cal);

  // Sets the pointer to a calibration result logger.
  void set_result_callback(
      online_calibration::ResultCallbackInterface *result_callback) {
    result_callback_ = result_callback;
  }

 private:
  // Provides sensor calibration updates using the ASH API for the specified
  // sensor type. 'cal_data' contains the new calibration data. 'flags' is used
  // to indicate all of the valid calibration values that should be provided
  // with the update. Returns 'true' with a successful ASH update.
  bool NotifyAshCalibration(
      uint8_t chre_sensor_type, uint8_t sensor_index, uint8_t calibration_index,
      const online_calibration::CalibrationDataThreeAxis &cal_data,
      online_calibration::CalibrationTypeFlags flags, char const *sensor_tag);

  // Checks whether 'ash_cal_parameters' is a valid set of runtime calibration
  // data and can be used for algorithm initialization. Updates 'flags' to
  // indicate which runtime calibration parameters were detected. Returns true
  // if valid runtime calibration data is detected and may be used.
  bool DetectRuntimeCalibration(
      uint8_t chre_sensor_type, const char *sensor_tag, uint8_t sensor_index,
      uint8_t calibration_index, const ashCalParams &ash_cal_parameters,
      online_calibration::CalibrationTypeFlags &flags);

  // Helper functions for logging calibration information.
  void PrintCalibration(
      const online_calibration::CalibrationDataThreeAxis &cal_data,
      uint8_t sensor_index, uint8_t calibration_index,
      online_calibration::CalibrationTypeFlags flags, const char *sensor_tag);

  bool IsGyroLogUpdateAllowed(uint64_t timestamp_nanos);

  // Limits the log messaging update rate for the gyro calibrations since these
  // can occur frequently with rapid temperature changes.
  uint64_t gyro_notification_time_nanos_ = 0;
  uint64_t initial_gyro_cal_time_nanos_ = 0;

  // Pointer to a calibration result logger (e.g., telemetry).
  online_calibration::ResultCallbackInterface *result_callback_ = nullptr;
};

}  // namespace nano_calibration

#endif  // LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_NANO_CALIBRATION_NANO_CALIBRATION_H_
