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

import static com.google.common.truth.Truth.assertThat;

import android.car.VehicleAreaType;
import android.car.hardware.CarPropertyConfig;

import org.junit.Test;

public final class InputSanitizationUtilsUnitTest {
    private static final int PROPERTY_ID = 123;
    private static final float DEFAULT_UPDATE_RATE_HZ = 55.5f;
    private static final float MIN_UPDATE_RATE_HZ = 11.11f;
    private static final float MAX_UPDATE_RATE_HZ = 100.3f;
    private static final CarPropertyConfig.Builder<Integer> CAR_PROPERTY_CONFIG_BUILDER =
            CarPropertyConfig.newBuilder(
                    Integer.class, PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL);

    @Test
    public void sanitizeUpdateRateHz_returnsZeroForStaticProperties() {
        assertThat(
                        InputSanitizationUtils.sanitizeUpdateRateHz(
                                CAR_PROPERTY_CONFIG_BUILDER
                                        .setChangeMode(
                                                CarPropertyConfig
                                                        .VEHICLE_PROPERTY_CHANGE_MODE_STATIC)
                                        .build(),
                                DEFAULT_UPDATE_RATE_HZ))
                .isEqualTo(0);
    }

    @Test
    public void sanitizeUpdateRateHz_returnsZeroForOnChangeProperties() {
        assertThat(
                        InputSanitizationUtils.sanitizeUpdateRateHz(
                                CAR_PROPERTY_CONFIG_BUILDER
                                        .setChangeMode(
                                                CarPropertyConfig
                                                        .VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE)
                                        .build(),
                                DEFAULT_UPDATE_RATE_HZ))
                .isEqualTo(0);
    }

    @Test
    public void sanitizeUpdateRateHz_returnsMaxUpdateRateHzIfOver() {
        assertThat(
                        InputSanitizationUtils.sanitizeUpdateRateHz(
                                CAR_PROPERTY_CONFIG_BUILDER
                                        .setChangeMode(
                                                CarPropertyConfig
                                                        .VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS)
                                        .setMinSampleRate(MIN_UPDATE_RATE_HZ)
                                        .setMaxSampleRate(MAX_UPDATE_RATE_HZ)
                                        .build(),
                                MAX_UPDATE_RATE_HZ + 1))
                .isEqualTo(MAX_UPDATE_RATE_HZ);
    }

    @Test
    public void sanitizeUpdateRateHz_returnsMinUpdateRateHzIfOver() {
        assertThat(
                        InputSanitizationUtils.sanitizeUpdateRateHz(
                                CAR_PROPERTY_CONFIG_BUILDER
                                        .setChangeMode(
                                                CarPropertyConfig
                                                        .VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS)
                                        .setMinSampleRate(MIN_UPDATE_RATE_HZ)
                                        .setMaxSampleRate(MAX_UPDATE_RATE_HZ)
                                        .build(),
                                MIN_UPDATE_RATE_HZ - 1))
                .isEqualTo(MIN_UPDATE_RATE_HZ);
    }

    @Test
    public void sanitizeUpdateRateHz_returnsDefaultUpdateRateHz() {
        assertThat(
                        InputSanitizationUtils.sanitizeUpdateRateHz(
                                CAR_PROPERTY_CONFIG_BUILDER
                                        .setChangeMode(
                                                CarPropertyConfig
                                                        .VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS)
                                        .setMinSampleRate(MIN_UPDATE_RATE_HZ)
                                        .setMaxSampleRate(MAX_UPDATE_RATE_HZ)
                                        .build(),
                                DEFAULT_UPDATE_RATE_HZ))
                .isEqualTo(DEFAULT_UPDATE_RATE_HZ);
    }
}
