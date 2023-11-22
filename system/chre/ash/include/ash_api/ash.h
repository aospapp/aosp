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

#ifndef CHRE_ASH_H_
#define CHRE_ASH_H_

/**
 * @file
 * Defines the interface for the Android Sensor Hub support.
 */

#include <stdbool.h>
#include <stdint.h>

#include "ash/cal.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * The values returned by this sensor cannot be trusted, calibration is needed
 * or the environment doesn't allow readings.
 */
#define ASH_CAL_ACCURACY_UNRELIABLE UINT8_C(0)

/**
 * This sensor is reporting data with low accuracy, calibration with the
 * environment is needed.
 */
#define ASH_CAL_ACCURACY_LOW UINT8_C(1)

/**
 * This sensor is reporting data with an average level of accuracy, calibration
 * with the environment may improve the readings.
 */
#define ASH_CAL_ACCURACY_MEDIUM UINT8_C(2)

/**
 * This sensor is reporting data with maximum accuracy.
 */
#define ASH_CAL_ACCURACY_HIGH UINT8_C(3)

/**
 * Calibration info for a sensor which reports on a maximum of three axes.
 *
 * Let Su be the uncalibrated sensor data and Sc the calibrated one,
 * Sc = compMatrix * (Su - bias)
 *
 */
struct ashCalInfo {
  /**
   * The zero-bias vector in the x, y, z order. If the sensor reports on N
   * axes with N < 3, only the first N elements are considered valid.
   */
  float bias[3];

  /**
   * The compensation matrix in the row major order. If the sensor reports on N
   * axes with N < 3, only the first N elements of each row are considered
   * valid.
   */
  float compMatrix[9];

  /**
   * One of the ASH_CAL_ACCURACY_* constants. This corresponds to the
   * definition in the Android SensorManager. See
   * https://developer.android.com/reference/android/hardware/SensorEvent.html#accuracy
   * for more details.
   * Note that this accuracy field is simply a suggestion to the platform and
   * the platform can ignore or over-write it.
   */
  uint8_t accuracy;
};

/**
 * Updates the runtime calibration info of a given sensor type for the platform
 * to compensate for. The calibration will be applied on top of the sensor's
 * factory calibration if present.
 *
 * @param sensorType One of the CHRE_SENSOR_TYPE_* constants.
 * @param calInfo A non-null pointer to ashCalInfo to update the sensor's
          calibration.
 * @return true if the calibration info has been successfully updated.
 */
bool ashSetCalibration(uint8_t sensorType, const struct ashCalInfo *calInfo);

#ifdef __cplusplus
}
#endif

#endif  // CHRE_ASH_H_
