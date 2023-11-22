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
 * This module provides a containing class (NanoSensorCal) for runtime
 * calibration algorithms that affect the following sensors:
 *       - Accelerometer (offset)
 *       - Gyroscope (offset, with optional over-temperature compensation)
 *       - Magnetometer (offset, with optional scale factor and cross-axis)
 *
 * Sensor Units:
 *       - Accelerometer [meters/sec^2]
 *       - Gyroscope [radian/sec]
 *       - Magnetometer [micro Tesla, uT]
 *       - Temperature [Celsius].
 *
 * NOTE: Define NANO_SENSOR_CAL_DBG_ENABLED to show debug messages.
 */

#ifndef LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_NANO_CALIBRATION_NANO_CALIBRATION_H_
#define LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_NANO_CALIBRATION_NANO_CALIBRATION_H_

#include <ash.h>

#ifdef ACCEL_CAL_ENABLED
#include "calibration/accelerometer/accel_cal.h"
#endif  // ACCEL_CAL_ENABLED

#ifdef GYRO_CAL_ENABLED
#include "calibration/gyroscope/gyro_cal.h"
#ifdef OVERTEMPCAL_GYRO_ENABLED
#include "calibration/over_temp/over_temp_cal.h"
#endif  // OVERTEMPCAL_GYRO_ENABLED
#endif  // GYRO_CAL_ENABLED

#ifdef MAG_CAL_ENABLED
#include "calibration/magnetometer/mag_cal.h"
#ifdef SPHERE_FIT_ENABLED
#include "calibration/magnetometer/mag_sphere_fit.h"
#endif  // SPHERE_FIT_ENABLED
#endif  // MAG_CAL_ENABLED

#include <chre.h>

namespace nano_calibration {

// Data struct for sample rate estimate fuction. Visible for the class in order
// to allow usage in all algorithms.
struct SampleRateData {
  uint64_t last_timestamp_nanos;
  uint64_t time_delta_accumulator;
  size_t num_samples;
};

// TODO: move typedef to mag_cal.h.
typedef uint32_t MagUpdateFlags;

/*
 * Class Definition:  NanoSensorCal.
 */
class NanoSensorCal {
 public:
  // Default constructor.
  NanoSensorCal();

  // Virtual destructor.
  virtual ~NanoSensorCal() {}

  // Initializes the sensor calibration algorithms.
  void Initialize();

  // Sends new sensor samples to the calibration algorithms.
  void HandleSensorSamples(uint16_t event_type,
                           const chreSensorThreeAxisData *event_data);

  // Provides temperature updates to the calibration algorithms.
  void HandleTemperatureSamples(uint16_t event_type,
                                const chreSensorFloatData *event_data);

  // Returns the availability of new calibration data (useful for polling).
  bool is_accel_calibration_ready() { return accel_calibration_ready_; }
  bool is_gyro_calibration_ready()  { return gyro_calibration_ready_; }
  bool is_mag_calibration_ready()   { return mag_calibration_ready_; }

  // Returns true if the NanoSensorCal object has been initialized.
  bool is_initialized() { return nanosensorcal_initialized_; }

  // Calibration data accessor functions useful, in conjunction with the
  // 'is_calibration_ready' functions, to poll for changes in calibration
  // parameters. Calling these functions returns the calibration parameters and
  // resets the calibration ready flags.
  void GetAccelerometerCalibration(struct ashCalParams *accel_cal_params) const;
  void GetGyroscopeCalibration(struct ashCalParams *gyro_cal_params) const;
  void GetMagnetometerCalibration(struct ashCalParams *mag_cal_params) const ;

 private:
  // Sends new sensor samples to the AccelCal.
  void HandleSensorSamplesAccelCal(uint16_t event_type,
                                   const chreSensorThreeAxisData *event_data);

  // Sends new sensor samples to the GyroCal/OTC. GyroCal utilizes multiple
  // sensor types (i.e., accel/gyro/mag).
  void HandleSensorSamplesGyroCal(uint16_t event_type,
                                  const chreSensorThreeAxisData *event_data);

  // Sends new sensor samples to the MagCal.
  void HandleSensorSamplesMagCal(uint16_t event_type,
                                 const chreSensorThreeAxisData *event_data);

  // Updates the local calibration parameters containers.
  void UpdateAccelCalParams();
  void UpdateGyroCalParams();
  void UpdateMagCalParams(MagUpdateFlags new_update);

  // Loads persistent calibration data using the ASH API.
  void LoadAshAccelCal();
  void LoadAshGyroCal();
  void LoadAshOtcGyroCal();
  void LoadAshMagCal();

  // Stores persistent calibration data parameters and updates calibration
  // information using the ASH API.
  void NotifyAshAccelCal();
  void NotifyAshGyroCal();
  void NotifyAshMagCal(MagUpdateFlags new_update);

#ifdef ACCEL_CAL_ENABLED
  // Accelerometer runtime calibration.
  struct AccelCal accel_cal_;
#endif  // ACCEL_CAL_ENABLED

#ifdef GYRO_CAL_ENABLED
  // Gyroscope runtime calibration.
  struct GyroCal gyro_cal_;

  // Used to limit the rate of gyro debug notification messages.
  uint64_t gyro_notification_time_check_ = 0;
#ifdef OVERTEMPCAL_GYRO_ENABLED
  // Gyroscope over-temperature runtime calibration.
  struct OverTempCal over_temp_gyro_cal_;
#endif  // OVERTEMPCAL_GYRO_ENABLED
#endif  // GYRO_CAL_ENABLED

#ifdef MAG_CAL_ENABLED
  // Magnetometer runtime calibration.
  struct MagCal mag_cal_;
#ifdef SPHERE_FIT_ENABLED
  struct SampleRateData mag_sample_rate_data_;
  struct MagCalSphere mag_cal_sphere_;
  float mag_odr_estimate_hz_ = 0;
#endif  // SPHERE_FIT_ENABLED
#endif  // MAG_CAL_ENABLED

  // Flag to indicate whether the NanoSensorCal object has been initialized.
  bool nanosensorcal_initialized_ = false;

  // Flags to indicate availability of new calibration data (polling).
  mutable bool accel_calibration_ready_ = false;
  mutable bool gyro_calibration_ready_ = false;
  mutable bool mag_calibration_ready_ = false;

  // Sensor temperature.
  float temperature_celsius_;

  // Sensor calibration parameter containers.
  struct ashCalParams accel_cal_params_;
  struct ashCalParams gyro_cal_params_;
  struct ashCalParams mag_cal_params_;
};

}  // namespace nano_calibration

#endif  // LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_NANO_CALIBRATION_NANO_CALIBRATION_H_
