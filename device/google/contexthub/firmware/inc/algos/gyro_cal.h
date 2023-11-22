#ifndef GYRO_CAL_H_
#define GYRO_CAL_H_

/*
 * Copyright (C) 2016 The Android Open Source Project
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

///////////////////////////////////////////////////////////////
/*
 * This module contains the algorithms for producing a
 * gyroscope offset calibration.  The algorithm looks
 * for periods of stillness as indicated by accelerometer,
 * magnetometer and gyroscope, and computes a bias estimate
 * by taking the average of the gyroscope during the
 * stillness times.
 *
 * Currently, this algorithm is tuned such that the device
 * is only considered still when the device is on a
 * stationary surface (e.g., not on a person).
 *
 * NOTE - Time units are agnostic (i.e., determined by the
 * user's application and usage). However, typical time units
 * are nanoseconds.
 *
 * Required Sensors and Units:
 *       - Gyroscope     [rad/sec]
 *       - Accelerometer [m/sec^2]
 *
 * Optional Sensors and Units:
 *       - Magnetometer  [micro-Tesla, uT]
 *       - Temperature   [Celcius]
 */
///////////////////////////////////////////////////////////////

#include <seos.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <sys/types.h>
#include <string.h>
#include <algos/gyro_stillness_detect.h>

#ifdef GYRO_CAL_DBG_ENABLED
// Debug: Number of past calibrations to store.
#define DEBUG_GYRO_SHORTTERM_NUM_CAL 30
#define DEBUG_GYRO_LONGTERM_NUM_CAL 30
#define DEBUG_GYRO_CAL_LIMIT 7200000000000 //2hours [nsec]

// Gyro Cal debug information/data tracking structure.
struct debugGyroCal_t{
  float temperature_celcius;
  float accel_stillness_conf;
  float gyro_stillness_conf;
  float mag_stillness_conf;
  uint64_t calibration_time;
  uint64_t calibration_time_duration;
  bool used_mag_sensor;
  float calibration[3];
  float accel_mean[3];
  float gyro_mean[3];
  float mag_mean[3];
  float accel_var[3];
  float gyro_var[3];
  float mag_var[3];
};
#endif

struct gyroCal_t{

  // Stillness detectors.
  struct gyroStillDet_t accel_stillness_detect;
  struct gyroStillDet_t mag_stillness_detect;
  struct gyroStillDet_t gyro_stillness_detect;

  // Flag is "true" when the magnetometer is used.
  bool using_mag_sensor;

  // Flag set by user to control whether calibrations are used
  // (default: "true").
  bool gyro_calibration_enable;

  // Latest temperature measurement.
  float latest_temperature_celcius;

  // Aggregated sensor stillness threshold required for gyro bias calibration.
  float stillness_threshold;

  // Min and max durations for gyro bias calibration.
  uint64_t min_still_duration;
  uint64_t max_still_duration;

  // Duration of the stillness processing windows.
  uint64_t window_time_duration;

  // Flag to indicate if device was previously still.
  bool prev_still;

  // Timestamp when device started a still period.
  uint64_t start_still_time;

  // gyro bias estimates and last calibration timestamp.
  float bias_x, bias_y, bias_z; // [rad/sec]
  uint64_t calibration_time;
  uint64_t calibration_time_duration;
  float stillness_confidence;
  bool new_gyro_cal_available; //true when a new cal is ready.

  // Current window end time for all sensors. Used to assist in keeping
  // sensor data collection in sync. On initialization this will be set to
  // zero indicating that sensor data will be dropped until a valid end time
  // is set from the first gyro timestamp received.
  uint64_t stillness_win_endtime;

  // Watchdog timer to reset to a known good state when data capture stalls.
  uint64_t gyro_watchdog_start;
  uint64_t gyro_watchdog_timeout_duration;
  bool gyro_watchdog_timeout;

  //----------------------------------------------------------------

#ifdef GYRO_CAL_DBG_ENABLED
  // Debug info.
  bool debug_processed_data_available; //flag on a per window basis.
  uint64_t debug_processed_data_time; //flag time stamp.
  uint32_t debug_calibration_count; //total number of cals performed.
  uint32_t debug_watchdog_count; //total number of watchdog timeouts.

  // Debug short-term history data.
  struct debugGyroCal_t debug_cal_data[DEBUG_GYRO_SHORTTERM_NUM_CAL];
  uint8_t debug_num_cals; //number of calibrations collected.
  uint8_t debug_head; //index of last valid calibration.

  // Debug long-term history data (limited collection frequency).
  struct debugGyroCal_t debug_cal_data_hist[DEBUG_GYRO_LONGTERM_NUM_CAL];
  uint8_t debug_num_cals_hist; //number of calibrations collected.
  uint8_t debug_head_hist; //index of last valid calibration.
#endif
};

/////// FUNCTION PROTOTYPES //////////////////////////////////////////

// Initialize the gyro calibration data structure.
void gyroCalInit(struct gyroCal_t* gyro_cal, uint64_t min_still_duration,
                 uint64_t max_still_duration,
                 float bias_x, float bias_y, float bias_z,
                 uint64_t calibration_time,
                 uint64_t window_time_duration,
                 float gyro_var_threshold,
                 float gyro_confidence_delta,
                 float accel_var_threshold,
                 float accel_confidence_delta,
                 float mag_var_threshold,
                 float mag_confidence_delta,
                 float stillness_threshold,
                 int remove_bias_enable);

// Void all pointers in the gyro calibration data structure.
void gyroCalDestroy(struct gyroCal_t* gyro_cal);

// Get the most recent bias calibration value.
void gyroCalGetBias(struct gyroCal_t* gyro_cal,
                    float* bias_x, float* bias_y, float* bias_z);

// Set an initial bias calibration value.
void gyroCalSetBias(struct gyroCal_t* gyro_cal,
                    float bias_x, float bias_y, float bias_z,
                    uint64_t calibration_time);

// Remove gyro bias from the calibration [rad/sec].
void gyroCalRemoveBias(struct gyroCal_t* gyro_cal, float xi, float yi,
                       float zi, float* xo, float* yo, float* zo);

// Returns true when a new gyro calibration is available.
bool gyroCalNewBiasAvailable(struct gyroCal_t* gyro_cal);

// Update the gyro calibration with gyro data [rad/sec].
void gyroCalUpdateGyro(struct gyroCal_t* gyro_cal,
                       uint64_t sample_time,
                       float x, float y, float z,
                       float temperature);

// Update the gyro calibration with mag data [micro Tesla].
void gyroCalUpdateMag(struct gyroCal_t* gyro_cal,
                      uint64_t sample_time,
                      float x, float y, float z);

// Update the gyro calibration with accel data [m/sec^2].
void gyroCalUpdateAccel(struct gyroCal_t* gyro_cal,
                        uint64_t sample_time,
                        float x, float y, float z);

#ifdef GYRO_CAL_DBG_ENABLED
// Print debug data report.
void gyroCalDebugPrint(struct gyroCal_t* gyro_cal,
                       int* state,
                       uint64_t sample_time);
#endif

#endif // GYRO_CAL_H_
