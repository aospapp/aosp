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

#include <algos/gyro_cal.h>

/////// DEFINITIONS AND MACROS ///////////////////////////////////////

// Maximum gyro bias correction (should be set based on
// expected max bias of the given sensor).
#define MAX_GYRO_BIAS 0.096f // [rad/sec]

// Helper constants for converting units.
#define RAD_TO_MDEG (float)(1e3*180.0/M_PI)
#define GRAVITY_TO_G (float)(1.0/9.80665)

/////// FORWARD DECLARATIONS /////////////////////////////////////////

static void deviceStillnessCheck(struct gyroCal_t* gyro_cal,
                                 uint64_t sample_time);

static void computeGyroCal(struct gyroCal_t* gyro_cal,
                           uint64_t calibration_time);

static void checkWatchdog(struct gyroCal_t* gyro_cal,
                          uint64_t sample_time);

#ifdef GYRO_CAL_DBG_ENABLED
static void gyroCalUpdateDebug(struct gyroCal_t* gyro_cal,
                               struct debugGyroCal_t* debug_gyro_cal);

static void gyroCalTuneDebugPrint(struct gyroCal_t* gyro_cal,
                                  uint64_t sample_time);

static void gyroCalDebugPrintData(int count,
                                  struct debugGyroCal_t *debug_data);

// Information print macro.
#define GYRO_INFO_PRINT(fmt, ...) do { \
        osLog(LOG_INFO, "%s " fmt, "[GYRO CAL]", ##__VA_ARGS__); \
    } while (0);

// Macro used to print out floating point numbers.
#define GYRO_ENCODE_FLOAT(x, num_digits) ((x < 0) ? "-" : ""), \
    (int)floorf(fabsf(x)),                                \
    (int)((fabsf(x) - floorf(fabsf(x))) * powf(10,num_digits))
#endif

/////// FUNCTION DEFINITIONS /////////////////////////////////////////

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
                 int remove_bias_enable) {

  // Clear gyro_cal structure memory.
  memset(gyro_cal, 0, sizeof(struct gyroCal_t));

  // Initialize the stillness detectors.
  // Gyro parameter input units are [rad/sec]
  // Accel parameter input units are [m/sec^2]
  // Magnetometer parameter input units are [uT]
  gyroStillDetInit(&gyro_cal->gyro_stillness_detect,
                 gyro_var_threshold,
                 gyro_confidence_delta);
  gyroStillDetInit(&gyro_cal->accel_stillness_detect,
                 accel_var_threshold,
                 accel_confidence_delta);
  gyroStillDetInit(&gyro_cal->mag_stillness_detect,
                 mag_var_threshold,
                 mag_confidence_delta);

  // Reset stillness flag and start timestamp.
  gyro_cal->prev_still = false;
  gyro_cal->start_still_time = 0;

  // Set the min and max window stillness duration.
  gyro_cal->min_still_duration = min_still_duration;
  gyro_cal->max_still_duration = max_still_duration;

  // Sets the duration of the stillness processing windows.
  gyro_cal->window_time_duration = window_time_duration;

  // Set the watchdog timeout duration.
  gyro_cal->gyro_watchdog_timeout_duration =
      2 * window_time_duration;

  // Load the last valid cal from system memory.
  gyro_cal->bias_x = bias_x; // [rad/sec]
  gyro_cal->bias_y = bias_y; // [rad/sec]
  gyro_cal->bias_z = bias_z; // [rad/sec]
  gyro_cal->calibration_time = calibration_time;

  // Set the stillness threshold required for gyro bias calibration.
  gyro_cal->stillness_threshold = stillness_threshold;

  // Current window end time used to assist in keeping sensor data
  // collection in sync. Setting this to zero signals that sensor data
  // will be dropped until a valid end time is set from the first gyro
  // timestamp received.
  gyro_cal->stillness_win_endtime = 0;

  // Gyro calibrations will be applied (see, gyroCalRemoveBias()).
  gyro_cal->gyro_calibration_enable = (remove_bias_enable > 0);

#ifdef GYRO_CAL_DBG_ENABLED
  GYRO_INFO_PRINT("Gyro Cal: Initialized.");
#endif
}

// Void all pointers in the gyro calibration data structure
// (prevents compiler warnings).
void gyroCalDestroy(struct gyroCal_t* gyro_cal) {
  (void)gyro_cal;
}

// Get the most recent bias calibration value.
void gyroCalGetBias(struct gyroCal_t* gyro_cal,
                    float* bias_x, float* bias_y, float* bias_z) {
  if (gyro_cal->gyro_calibration_enable) {
    *bias_x = gyro_cal->bias_x;
    *bias_y = gyro_cal->bias_y;
    *bias_z = gyro_cal->bias_z;
  }
}

// Set an initial bias calibration value.
void gyroCalSetBias(struct gyroCal_t* gyro_cal,
                    float bias_x, float bias_y, float bias_z,
                    uint64_t calibration_time) {
  gyro_cal->bias_x = bias_x;
  gyro_cal->bias_y = bias_y;
  gyro_cal->bias_z = bias_z;
  gyro_cal->calibration_time = calibration_time;
}

// Remove bias from a gyro measurement [rad/sec].
void gyroCalRemoveBias(struct gyroCal_t* gyro_cal,
                       float xi, float yi, float zi,
                       float* xo, float* yo, float* zo) {
  if (gyro_cal->gyro_calibration_enable) {
    *xo = xi - gyro_cal->bias_x;
    *yo = yi - gyro_cal->bias_y;
    *zo = zi - gyro_cal->bias_z;
  }
}

// Returns true when a new gyro calibration is available.
bool gyroCalNewBiasAvailable(struct gyroCal_t* gyro_cal) {
  bool new_gyro_cal_available =
      (gyro_cal->gyro_calibration_enable &&
       gyro_cal->new_gyro_cal_available);

  // Clear the flag.
  gyro_cal->new_gyro_cal_available = false;

  return new_gyro_cal_available;
}

// Update the gyro calibration with gyro data [rad/sec].
void gyroCalUpdateGyro(struct gyroCal_t* gyro_cal,
                       uint64_t sample_time,
                       float x, float y, float z,
                       float temperature) {

  // Make sure that a valid window end time is set,
  // and start the watchdog timer.
  if (gyro_cal->stillness_win_endtime <= 0) {
    gyro_cal->stillness_win_endtime =
        sample_time + gyro_cal->window_time_duration;

    // Start the watchdog timer.
    gyro_cal->gyro_watchdog_start = sample_time;
  }

  // Record the latest temperture sample.
  gyro_cal->latest_temperature_celcius = temperature;

  // Pass gyro data to stillness detector
  gyroStillDetUpdate(&gyro_cal->gyro_stillness_detect,
                     gyro_cal->stillness_win_endtime,
                     sample_time, x, y, z);

  // Perform a device stillness check, set next window end time, and
  // possibly do a gyro bias calibration and stillness detector reset.
  deviceStillnessCheck(gyro_cal, sample_time);
}

// Update the gyro calibration with mag data [micro Tesla].
void gyroCalUpdateMag(struct gyroCal_t* gyro_cal,
                      uint64_t sample_time,
                      float x, float y, float z) {

  // Pass magnetometer data to stillness detector.
  gyroStillDetUpdate(&gyro_cal->mag_stillness_detect,
                     gyro_cal->stillness_win_endtime,
                     sample_time, x, y, z);

  // Received a magnetometer sample; incorporate it into detection.
  gyro_cal->using_mag_sensor = true;

  // Perform a device stillness check, set next window end time, and
  // possibly do a gyro bias calibration and stillness detector reset.
  deviceStillnessCheck(gyro_cal, sample_time);
}

// Update the gyro calibration with accel data [m/sec^2].
void gyroCalUpdateAccel(struct gyroCal_t* gyro_cal,
                        uint64_t sample_time,
                        float x, float y, float z) {
  // Pass accelerometer data to stillnesss detector.
  gyroStillDetUpdate(&gyro_cal->accel_stillness_detect,
                     gyro_cal->stillness_win_endtime,
                     sample_time, x, y, z);

  // Perform a device stillness check, set next window end time, and
  // possibly do a gyro bias calibration and stillness detector reset.
  deviceStillnessCheck(gyro_cal, sample_time);
}

// Checks the state of all stillness detectors to determine
// whether the device is "still".
void deviceStillnessCheck(struct gyroCal_t* gyro_cal,
                          uint64_t sample_time) {

  bool stillness_duration_exceeded = false;
  bool stillness_duration_too_short = false;
  bool device_is_still = false;
  float conf_not_rot = 0;
  float conf_not_accel = 0;
  float conf_still = 0;

  // Check the watchdog timer.
  checkWatchdog(gyro_cal, sample_time);

  // Is there enough data to do a stillness calculation?
  if ((!gyro_cal->mag_stillness_detect.stillness_window_ready &&
       gyro_cal->using_mag_sensor) ||
      !gyro_cal->accel_stillness_detect.stillness_window_ready ||
      !gyro_cal->gyro_stillness_detect.stillness_window_ready) {
    return; // Not yet, wait for more data.
  }

  // Set the next window end time for the stillness detectors.
  gyro_cal->stillness_win_endtime =
      sample_time + gyro_cal->window_time_duration;

  // Update the confidence scores for all sensors.
  gyroStillDetCompute(&gyro_cal->accel_stillness_detect);
  gyroStillDetCompute(&gyro_cal->gyro_stillness_detect);
  if (gyro_cal->using_mag_sensor) {
    gyroStillDetCompute(&gyro_cal->mag_stillness_detect);
  } else {
    // Not using magnetometer, force stillness confidence to 100%.
    gyro_cal->mag_stillness_detect.stillness_confidence = 1.0f;
  }

  // Determine motion confidence scores (rotation, accelerating, and stillness).
  conf_not_rot = gyro_cal->gyro_stillness_detect.stillness_confidence *
      gyro_cal->mag_stillness_detect.stillness_confidence;
  conf_not_accel = gyro_cal->accel_stillness_detect.stillness_confidence;
  conf_still = conf_not_rot * conf_not_accel;

  // determine if the device is currently still.
  device_is_still = (conf_still > gyro_cal->stillness_threshold);

  if (device_is_still) {

    // Device is still logic:
    // If not previously still, then record the start time.
    // If stillness period is too long, then do a calibration.
    // Otherwise, continue collecting stillness data.

    // If device was not previously still, set new start timestamp.
    if (!gyro_cal->prev_still) {

      // Record the starting timestamp of the current stillness window.
      // This enables the calculation of total duration of the stillness period.
      gyro_cal->start_still_time =
          gyro_cal->gyro_stillness_detect.window_start_time;
    }

    // Check to see if current stillness period exceeds the desired limit
    // to avoid corrupting the .
    stillness_duration_exceeded =
        ((gyro_cal->gyro_stillness_detect.last_sample_time -
          gyro_cal->start_still_time) >
          gyro_cal->max_still_duration);

    if (stillness_duration_exceeded) {

      // The current stillness has gone too long. Do a calibration with the
      // current data and reset.

      // Update the gyro bias estimate with the current window data and
      // reset the stats.
      gyroStillDetReset(&gyro_cal->accel_stillness_detect, true);
      gyroStillDetReset(&gyro_cal->gyro_stillness_detect, true);
      gyroStillDetReset(&gyro_cal->mag_stillness_detect, true);

      // Perform calibration.
      computeGyroCal(gyro_cal,
                     gyro_cal->gyro_stillness_detect.last_sample_time);

      // Update stillness flag. Force the start of a new stillness period.
      gyro_cal->prev_still = false;
    } else {

      // Continue collecting stillness data.

      // Reset stillness detectors, and extend stillness period.
      gyroStillDetReset(&gyro_cal->accel_stillness_detect, false);
      gyroStillDetReset(&gyro_cal->gyro_stillness_detect, false);
      gyroStillDetReset(&gyro_cal->mag_stillness_detect, false);

      // Update stillness flag.
      gyro_cal->prev_still = true;
    }
  } else {

    // Device is NOT still; motion detected.

    // If device was previously still and the total stillness duration is not
    // "too short", then do a calibration with the data accumulated thus far.
    stillness_duration_too_short =
        ((gyro_cal->gyro_stillness_detect.window_start_time -
          gyro_cal->start_still_time) < gyro_cal->min_still_duration);

    if (gyro_cal->prev_still && !stillness_duration_too_short) {
      computeGyroCal(gyro_cal,
                     gyro_cal->gyro_stillness_detect.window_start_time);
    }

    // Reset stillness detectors and the stats.
    gyroStillDetReset(&gyro_cal->accel_stillness_detect, true);
    gyroStillDetReset(&gyro_cal->gyro_stillness_detect, true);
    gyroStillDetReset(&gyro_cal->mag_stillness_detect, true);

    // Update stillness flag.
    gyro_cal->prev_still = false;
  }

  // Reset the watchdog timer after we have processed data.
  gyro_cal->gyro_watchdog_start = sample_time;

#ifdef GYRO_CAL_DBG_ENABLED
  // Debug information available.
  gyro_cal->debug_processed_data_available = true;
  gyro_cal->debug_processed_data_time = sample_time;
#endif
}

// Calculates a new gyro bias offset calibration value.
void computeGyroCal(struct gyroCal_t* gyro_cal,
                    uint64_t calibration_time) {

  // Current calibration duration.
  uint64_t cur_cal_dur =
      calibration_time - gyro_cal->start_still_time;

  // Check to see if new calibration values is within acceptable range.
  if (!(gyro_cal->gyro_stillness_detect.prev_mean_x < MAX_GYRO_BIAS &&
        gyro_cal->gyro_stillness_detect.prev_mean_x >-MAX_GYRO_BIAS &&
        gyro_cal->gyro_stillness_detect.prev_mean_y < MAX_GYRO_BIAS &&
        gyro_cal->gyro_stillness_detect.prev_mean_y >-MAX_GYRO_BIAS &&
        gyro_cal->gyro_stillness_detect.prev_mean_z < MAX_GYRO_BIAS &&
        gyro_cal->gyro_stillness_detect.prev_mean_z >-MAX_GYRO_BIAS)) {
    // Outside of range. Ignore, reset, and continue.
    return;
  }

  // Record new gyro bias offset calibration.
  gyro_cal->bias_x = gyro_cal->gyro_stillness_detect.prev_mean_x;
  gyro_cal->bias_y = gyro_cal->gyro_stillness_detect.prev_mean_y;
  gyro_cal->bias_z = gyro_cal->gyro_stillness_detect.prev_mean_z;

  // Record final stillness confidence.
  gyro_cal->stillness_confidence =
      gyro_cal->gyro_stillness_detect.prev_stillness_confidence *
      gyro_cal->accel_stillness_detect.prev_stillness_confidence *
      gyro_cal->mag_stillness_detect.prev_stillness_confidence;

  // Store calibration stillness duration.
  gyro_cal->calibration_time_duration = cur_cal_dur;

  // Store calibration time stamp.
  gyro_cal->calibration_time = calibration_time;

  // Set flag to indicate a new gyro calibration value is available.
  gyro_cal->new_gyro_cal_available = true;

#ifdef GYRO_CAL_DBG_ENABLED
  // Increment the total count of calibration updates.
  gyro_cal->debug_calibration_count++;

  // Store the last 'DEBUG_GYRO_SHORTTERM_NUM_CAL' calibration debug data
  // in a circular buffer, 'debug_cal_data[]'. 'debug_head' is the index
  // of the last valid calibration.
  gyro_cal->debug_head++;
  if (gyro_cal->debug_head >= DEBUG_GYRO_SHORTTERM_NUM_CAL) {
    gyro_cal->debug_head = 0;
  }
  if (gyro_cal->debug_num_cals < DEBUG_GYRO_SHORTTERM_NUM_CAL) {
    gyro_cal->debug_num_cals++;
  }

  // Update the calibration debug information.
  gyroCalUpdateDebug(gyro_cal,
                     &gyro_cal->debug_cal_data[gyro_cal->debug_head]);

  // Improve the collection of historic calibrations. Limit frequency to
  // every N hours.
  if ((gyro_cal->debug_num_cals_hist <= 0) ||
      (calibration_time -
       gyro_cal->
       debug_cal_data_hist[gyro_cal->debug_head_hist].calibration_time)
      >= DEBUG_GYRO_CAL_LIMIT) {
    gyro_cal->debug_head_hist++;
    if (gyro_cal->debug_head_hist >= DEBUG_GYRO_LONGTERM_NUM_CAL) {
      gyro_cal->debug_head_hist = 0;
    }
    if (gyro_cal->debug_num_cals_hist < DEBUG_GYRO_LONGTERM_NUM_CAL) {
      gyro_cal->debug_num_cals_hist++;
    }

    // Update the calibration debug information.
    gyroCalUpdateDebug(gyro_cal,
                       &gyro_cal->
                       debug_cal_data_hist[gyro_cal->debug_head_hist]);
  }
#endif
}

// Check for a watchdog timeout condition.
void checkWatchdog(struct gyroCal_t* gyro_cal, uint64_t sample_time) {

  bool watchdog_timeout;

  // Check for initialization of the watchdog time (=0).
  if (gyro_cal->gyro_watchdog_start <= 0) {
    return;
  }

  // Check for timeout condition of watchdog.
  watchdog_timeout = ((sample_time - gyro_cal->gyro_watchdog_start) >
                      gyro_cal->gyro_watchdog_timeout_duration);

  // If a timeout occurred then reset to known good state.
  if (watchdog_timeout) {

    // Reset stillness detectors and restart data capture.
    gyroStillDetReset(&gyro_cal->accel_stillness_detect, true);
    gyroStillDetReset(&gyro_cal->gyro_stillness_detect, true);
    gyroStillDetReset(&gyro_cal->mag_stillness_detect, true);
    gyro_cal->mag_stillness_detect.stillness_confidence = 0;
    gyro_cal->stillness_win_endtime = 0;

    // Force stillness confidence to zero.
    gyro_cal->accel_stillness_detect.prev_stillness_confidence = 0;
    gyro_cal->gyro_stillness_detect.prev_stillness_confidence = 0;
    gyro_cal->mag_stillness_detect.prev_stillness_confidence = 0;
    gyro_cal->stillness_confidence = 0;
    gyro_cal->prev_still = false;

    // If there are no magnetometer samples being received then
    // operate the calibration algorithm without this sensor.
    if (!gyro_cal->mag_stillness_detect.stillness_window_ready &&
       gyro_cal->using_mag_sensor) {
      gyro_cal->using_mag_sensor = false;
    }

    // Assert watchdog timeout flags.
    gyro_cal->gyro_watchdog_timeout |= watchdog_timeout;
    gyro_cal->gyro_watchdog_start = 0;
#ifdef GYRO_CAL_DBG_ENABLED
    gyro_cal->debug_watchdog_count++;
#endif
  }
}

#ifdef GYRO_CAL_DBG_ENABLED
// Update the calibration debug information.
void gyroCalUpdateDebug(struct gyroCal_t* gyro_cal,
                        struct debugGyroCal_t* debug_gyro_cal) {
  // Probability of stillness (acc, rot, still), duration, timestamp.
  debug_gyro_cal->accel_stillness_conf =
      gyro_cal->accel_stillness_detect.prev_stillness_confidence;
  debug_gyro_cal->gyro_stillness_conf =
      gyro_cal->gyro_stillness_detect.prev_stillness_confidence;
  debug_gyro_cal->mag_stillness_conf =
      gyro_cal->mag_stillness_detect.prev_stillness_confidence;

  // Magnetometer usage.
  debug_gyro_cal->used_mag_sensor = gyro_cal->using_mag_sensor;

  // Temperature at calibration time.
  debug_gyro_cal->temperature_celcius =
      gyro_cal->latest_temperature_celcius;

  // Calibration time and stillness duration.
  debug_gyro_cal->calibration_time_duration =
      gyro_cal->calibration_time_duration;
  debug_gyro_cal->calibration_time =
      gyro_cal->calibration_time;

  // Record the current calibration values
  debug_gyro_cal->calibration[0] = gyro_cal->bias_x;
  debug_gyro_cal->calibration[1] = gyro_cal->bias_y;
  debug_gyro_cal->calibration[2] = gyro_cal->bias_z;

  // Record the previous window means
  debug_gyro_cal->accel_mean[0] =
      gyro_cal->accel_stillness_detect.prev_mean_x;
  debug_gyro_cal->accel_mean[1] =
      gyro_cal->accel_stillness_detect.prev_mean_y;
  debug_gyro_cal->accel_mean[2] =
      gyro_cal->accel_stillness_detect.prev_mean_z;

  debug_gyro_cal->gyro_mean[0] =
      gyro_cal->gyro_stillness_detect.prev_mean_x;
  debug_gyro_cal->gyro_mean[1] =
      gyro_cal->gyro_stillness_detect.prev_mean_y;
  debug_gyro_cal->gyro_mean[2] =
      gyro_cal->gyro_stillness_detect.prev_mean_z;

  debug_gyro_cal->mag_mean[0] =
      gyro_cal->mag_stillness_detect.prev_mean_x;
  debug_gyro_cal->mag_mean[1] =
      gyro_cal->mag_stillness_detect.prev_mean_y;
  debug_gyro_cal->mag_mean[2] =
      gyro_cal->mag_stillness_detect.prev_mean_z;

  // Record the variance data.
  debug_gyro_cal->accel_var[0] =
      gyro_cal->accel_stillness_detect.win_var_x;
  debug_gyro_cal->accel_var[1] =
      gyro_cal->accel_stillness_detect.win_var_y;
  debug_gyro_cal->accel_var[2] =
      gyro_cal->accel_stillness_detect.win_var_z;

  debug_gyro_cal->gyro_var[0] =
      gyro_cal->gyro_stillness_detect.win_var_x;
  debug_gyro_cal->gyro_var[1] =
      gyro_cal->gyro_stillness_detect.win_var_y;
  debug_gyro_cal->gyro_var[2] =
      gyro_cal->gyro_stillness_detect.win_var_z;

  debug_gyro_cal->mag_var[0] =
      gyro_cal->mag_stillness_detect.win_var_x;
  debug_gyro_cal->mag_var[1] =
      gyro_cal->mag_stillness_detect.win_var_y;
  debug_gyro_cal->mag_var[2] =
      gyro_cal->mag_stillness_detect.win_var_z;
}

// Helper function to print debug statements.
void gyroCalDebugPrintData(int count,
                       struct debugGyroCal_t *debug_data) {
  GYRO_INFO_PRINT(
      "#%d Gyro Bias Calibration = {%s%d.%06d, %s%d.%06d, %s%d.%06d} [mdps]\n",
      count,
      GYRO_ENCODE_FLOAT(debug_data->calibration[0] * RAD_TO_MDEG, 6),
      GYRO_ENCODE_FLOAT(debug_data->calibration[1] * RAD_TO_MDEG, 6),
      GYRO_ENCODE_FLOAT(debug_data->calibration[2] * RAD_TO_MDEG, 6)
                  );

  if (debug_data->used_mag_sensor) {
    GYRO_INFO_PRINT("   Using Magnetometer.\n");
  } else {
    GYRO_INFO_PRINT("   Not Using Magnetometer.\n");
  }

  GYRO_INFO_PRINT("   Temperature = %s%d.%06d [C]\n",
                  GYRO_ENCODE_FLOAT(debug_data->temperature_celcius, 6));

  GYRO_INFO_PRINT("   Calibration Timestamp = %llu [nsec]\n",
                  debug_data->calibration_time);

  GYRO_INFO_PRINT("   Stillness Duration = %llu [nsec]\n",
                  debug_data->calibration_time_duration);

  GYRO_INFO_PRINT("   Accel Mean = {%s%d.%06d, %s%d.%06d, %s%d.%06d} [g]\n",
                  GYRO_ENCODE_FLOAT(debug_data->accel_mean[0] *
                                    GRAVITY_TO_G, 6),
                  GYRO_ENCODE_FLOAT(debug_data->accel_mean[1] *
                                    GRAVITY_TO_G, 6),
                  GYRO_ENCODE_FLOAT(debug_data->accel_mean[2] *
                                    GRAVITY_TO_G, 6));

  GYRO_INFO_PRINT("   Mag Mean = {%s%d.%06d, %s%d.%06d, %s%d.%06d} [uT]\n",
                  GYRO_ENCODE_FLOAT(debug_data->mag_mean[0], 6),
                  GYRO_ENCODE_FLOAT(debug_data->mag_mean[1], 6),
                  GYRO_ENCODE_FLOAT(debug_data->mag_mean[2], 6)
                  );
}

// Print Debug data useful for tuning the stillness detectors.
void gyroCalTuneDebugPrint(struct gyroCal_t* gyro_cal, uint64_t sample_time) {

  static uint64_t counter = 0;

  // Output sensor variance levels to assist with tuning thresholds
  // every N seconds.
  if ((sample_time - counter) > 10000000000) { //nsec
    GYRO_INFO_PRINT("");
    GYRO_INFO_PRINT(
        "   Gyro Variance = {%s%d.%08d, %s%d.%08d, %s%d.%08d} [rad/sec]^2\n",
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->gyro_stillness_detect.win_var_x, 8),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->gyro_stillness_detect.win_var_y, 8),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->gyro_stillness_detect.win_var_z, 8));
    GYRO_INFO_PRINT(
        "   Accel Variance = {%s%d.%08d, %s%d.%08d, %s%d.%08d} [m/sec^2]^2\n",
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->accel_stillness_detect.win_var_x, 8),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->accel_stillness_detect.win_var_y, 8),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->accel_stillness_detect.win_var_z, 8));
    GYRO_INFO_PRINT(
        "   Mag Variance = {%s%d.%06d, %s%d.%06d, %s%d.%06d} [uT]^2\n",
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->mag_stillness_detect.win_var_x, 6),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->mag_stillness_detect.win_var_y, 6),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->mag_stillness_detect.win_var_z, 6));
    GYRO_INFO_PRINT(
        "   Stillness = {G%s%d.%06d, A%s%d.%06d, M%s%d.%06d}\n",
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->
                        gyro_stillness_detect.stillness_confidence, 6),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->
                        accel_stillness_detect.stillness_confidence, 6),
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->
                        mag_stillness_detect.stillness_confidence, 6));
    GYRO_INFO_PRINT(
        "   Temperature = %s%d.%06d [C]\n",
                    GYRO_ENCODE_FLOAT(
                        gyro_cal->latest_temperature_celcius, 6));
    GYRO_INFO_PRINT("Total Gyro Calibrations: %lu\n",
                    gyro_cal->debug_calibration_count);
    counter = sample_time; //reset
  }
}

// Print Debug data report.
void gyroCalDebugPrint(struct gyroCal_t* gyro_cal,
                       int* state,
                       uint64_t sample_time) {

  static int head_index;
  static int i, j;

  if (gyro_cal->debug_num_cals == 0) {
    *state = -1; // idle state.
  }

  // State machine to control reporting out debug print data.
  switch (*state) {

    case 0:
      // STATE 0 : Print out header
      GYRO_INFO_PRINT("");
      GYRO_INFO_PRINT(
          "---DEBUG REPORT-------------------------------------\n");
      GYRO_INFO_PRINT("Gyro Calibrations To Report (newest to oldest): %d\n",
                      gyro_cal->debug_num_cals);
      head_index = gyro_cal->debug_head;
      i = 1; // initialize report count
      *state = 1; // set next state.
      break;

    case 1:
      // STATE 1: Print out past N recent calibrations.
      if (i <= gyro_cal->debug_num_cals) {

        // Print the data.
        gyroCalDebugPrintData(i, &gyro_cal->debug_cal_data[head_index]);

        // Move to the previous calibration data set.
        head_index--;
        if (head_index < 0) {
          head_index = DEBUG_GYRO_SHORTTERM_NUM_CAL-1;
        }

        // Increment the report count.
        i++;

        // Go to wait state.
        j = 0;
        *state = 2;

      } else {
        // Print out the footer data.
        *state = 3;
      }
      break;

    case 2:
      // STATE 2 : Wait state.
      // Wait for N cycles of gyro samples before printing out more data.
      // This helps throttle the print statements.
      if (j <= 50) {
        j++;
      } else {
        *state = 1;
      }
      break;

    case 3:
      // STATE 3 : Print the end of the debug report.
      GYRO_INFO_PRINT("Total Gyro Calibrations: %lu\n",
                      gyro_cal->debug_calibration_count);

      GYRO_INFO_PRINT("Total Watchdog Timeouts: %lu\n",
                      gyro_cal->debug_watchdog_count);

      GYRO_INFO_PRINT(
          "---END DEBUG REPORT---------------------------------\n");
      *state = 4;
      break;

    case 4:
      // STATE 4 : Print out header (historic data)
      GYRO_INFO_PRINT("");
      GYRO_INFO_PRINT(
          "---DEBUG REPORT (HISTORY)---------------------------\n");
      GYRO_INFO_PRINT("Gyro Calibrations To Report (newest to oldest): %d\n",
                      gyro_cal->debug_num_cals_hist);
      head_index = gyro_cal->debug_head_hist;
      i = 1; // initialize report count
      *state = 5; // set next state.
      break;

    case 5:
      // STATE 5: Print out past N historic calibrations.
      if (i <= gyro_cal->debug_num_cals_hist) {

        // Print the data.
        gyroCalDebugPrintData(i, &gyro_cal->debug_cal_data_hist[head_index]);

        // Move to the previous calibration data set.
        head_index--;
        if (head_index < 0) {
          head_index = DEBUG_GYRO_LONGTERM_NUM_CAL-1;
        }

        // Increment the report count.
        i++;

        // Go to wait state.
        j = 0;
        *state = 6;

      } else {
        // Print out the footer data.
        *state = 7;
      }
      break;

    case 6:
      // STATE 6 : Wait state.
      // Wait for N cycles of gyro samples before printing out more data.
      // This helps throttle the print statements.
      if (j <= 50) {
        j++;
      } else {
        *state = 5;
      }
      break;

    case 7:
      // STATE 3 : Print the end of the debug report.
      GYRO_INFO_PRINT("Total Gyro Calibrations: %lu\n",
                      gyro_cal->debug_calibration_count);

      GYRO_INFO_PRINT("Total Watchdog Timeouts: %lu\n",
                      gyro_cal->debug_watchdog_count);

      GYRO_INFO_PRINT(
          "---END DEBUG REPORT---------------------------------\n");
      *state = -1;
      break;

    default :
      // Idle state.
      *state = -1;

      // Report periodic data useful for tuning the stillness detectors.
      //gyroCalTuneDebugPrint(gyro_cal, sample_time);
    }
}
#endif
