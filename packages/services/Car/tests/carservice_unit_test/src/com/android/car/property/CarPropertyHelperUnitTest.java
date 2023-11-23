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

import android.car.VehiclePropertyIds;

import org.junit.Test;

public final class CarPropertyHelperUnitTest {

    private static final int SYSTEM_ERROR_CODE = 0x0123;
    private static final int VENDOR_ERROR_CODE = 0x1234;
    private static final int COMBINDED_ERROR_CODE = 0x12340123;

    @Test
    public void testIsSupported() {
        // VehiclePropertyGroup:VENDOR, VehicleArea:GLOBAL,VehiclePropertyType:STRING, ID:0x0001
        assertThat(CarPropertyHelper.isSupported(0x21100001)).isTrue();
        // VEHICLE_SPEED_DISPLAY_UNITS is special because car property manager property ID is
        // different than vehicle HAL property ID.
        assertThat(CarPropertyHelper.isSupported(
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS)).isTrue();
         // This is a regular system property.
        assertThat(CarPropertyHelper.isSupported(VehiclePropertyIds.INFO_VIN)).isTrue();

        assertThat(CarPropertyHelper.isSupported(VehiclePropertyIds.INVALID)).isFalse();
        // This is a wrong property ID. It is like INFO_VIN but with the wrong VehicleArea.
        assertThat(CarPropertyHelper.isSupported(0x12100100)).isFalse();
    }

    @Test
    public void testGetVhalSystemErrorcode() {
        assertThat(CarPropertyHelper.getVhalSystemErrorCode(COMBINDED_ERROR_CODE)).isEqualTo(
                SYSTEM_ERROR_CODE);
    }

    @Test
    public void testGetVhalVendorErrorCode() {
        assertThat(CarPropertyHelper.getVhalVendorErrorCode(COMBINDED_ERROR_CODE)).isEqualTo(
                VENDOR_ERROR_CODE);
    }
}
