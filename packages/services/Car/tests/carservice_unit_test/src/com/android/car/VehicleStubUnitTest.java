/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car;

import static com.google.common.truth.Truth.assertThat;

import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.StatusCode;
import android.util.SparseIntArray;

import com.android.car.internal.property.CarPropertyHelper;

import org.junit.Test;

public final class VehicleStubUnitTest {

    @Test
    public void testConvertHalToCarPropertyManagerErrorStatusOK() throws Exception {
        int[] errorCodes = VehicleStub.convertHalToCarPropertyManagerError(StatusCode.OK);
        assertThat(errorCodes[0]).isEqualTo(CarPropertyHelper.STATUS_OK);
        assertThat(errorCodes[1]).isEqualTo(0);
    }

    @Test
    public void testConvertHalToCarPropertyManagerErrorStatus() throws Exception {
        SparseIntArray statusCodeToCarPropMgrError = new SparseIntArray();
        statusCodeToCarPropMgrError.put(StatusCode.NOT_AVAILABLE,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        statusCodeToCarPropMgrError.put(StatusCode.NOT_AVAILABLE_DISABLED,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        statusCodeToCarPropMgrError.put(StatusCode.NOT_AVAILABLE_SPEED_LOW,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        statusCodeToCarPropMgrError.put(StatusCode.NOT_AVAILABLE_SPEED_HIGH,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        statusCodeToCarPropMgrError.put(StatusCode.NOT_AVAILABLE_POOR_VISIBILITY,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        statusCodeToCarPropMgrError.put(StatusCode.NOT_AVAILABLE_SAFETY,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        statusCodeToCarPropMgrError.put(StatusCode.TRY_AGAIN,
                VehicleStub.STATUS_TRY_AGAIN);
        statusCodeToCarPropMgrError.put(StatusCode.INTERNAL_ERROR,
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);

        for (int i = 0; i < statusCodeToCarPropMgrError.size(); i++) {
            int statusCode = statusCodeToCarPropMgrError.keyAt(i);
            int carPropMgrError = statusCodeToCarPropMgrError.valueAt(i);
            int[] errorCodes = VehicleStub.convertHalToCarPropertyManagerError(
                    statusCode | (0x1234 << 16));
            assertThat(errorCodes[0]).isEqualTo(carPropMgrError);
            assertThat(errorCodes[1]).isEqualTo(0x1234);
        }
    }
}
