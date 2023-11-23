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

package com.android.car.internal.property;

import android.car.hardware.CarPropertyConfig;
import android.car.hardware.property.CarPropertyManager;

/**
 * Common utility functions used by {@link CarPropertyManager} stack to sanitize input arguments.
 *
 * @hide
 */
public class InputSanitizationUtils {

    /**
     * Sanitizes the {@code updateRateHz} passed to {@link
     * CarPropertyManager#registerCallback(CarPropertyManager.CarPropertyEventCallback, int, float)}
     * and similar functions.
     */
    public static float sanitizeUpdateRateHz(
            CarPropertyConfig<?> carPropertyConfig, float updateRateHz) {
        float sanitizedUpdateRateHz = updateRateHz;
        if (carPropertyConfig.getChangeMode()
                != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            sanitizedUpdateRateHz = CarPropertyManager.SENSOR_RATE_ONCHANGE;
        } else if (sanitizedUpdateRateHz > carPropertyConfig.getMaxSampleRate()) {
            sanitizedUpdateRateHz = carPropertyConfig.getMaxSampleRate();
        } else if (sanitizedUpdateRateHz < carPropertyConfig.getMinSampleRate()) {
            sanitizedUpdateRateHz = carPropertyConfig.getMinSampleRate();
        }
        return sanitizedUpdateRateHz;
    }
}
