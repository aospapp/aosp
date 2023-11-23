/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.hal;

import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.EPOCH_TIME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import android.car.test.util.BroadcastingFakeContext;
import android.car.test.util.VehicleHalTestingHelper;
import android.content.Intent;
import android.hardware.automotive.vehicle.V2_0.VehicleArea;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

@RunWith(MockitoJUnitRunner.class)
public final class TimeHalServiceTest {

    private static final VehiclePropConfig ANDROID_TIME_PROP =
            VehicleHalTestingHelper.newConfig(EPOCH_TIME);

    @Mock private VehicleHal mVehicleHal;
    private BroadcastingFakeContext mFakeContext;

    private TimeHalService mTimeHalService;

    @Before
    public void setUp() {
        mFakeContext = new BroadcastingFakeContext();
        mTimeHalService = new TimeHalService(mFakeContext, mVehicleHal);
    }

    @Test
    public void testInitDoesNothing() {
        mTimeHalService.takeProperties(Collections.emptyList());

        mTimeHalService.init();

        mFakeContext.verifyReceiverNotRegistered();
    }

    @Test
    public void testInitRegistersBroadcastReceiver() {
        mTimeHalService.takeProperties(Collections.singletonList(ANDROID_TIME_PROP));

        mTimeHalService.init();

        assertThat(mTimeHalService.isAndroidTimeSupported()).isTrue();
        mFakeContext.verifyReceiverRegistered(Intent.ACTION_TIME_CHANGED);
    }

    @Test
    public void testInitSendsAndroidTimeUpdate() {
        mTimeHalService.takeProperties(Collections.singletonList(ANDROID_TIME_PROP));
        long sysTimeMillis = System.currentTimeMillis();

        mTimeHalService.init();

        assertThat(mTimeHalService.isAndroidTimeSupported()).isTrue();
        ArgumentCaptor<VehiclePropValue> captor = ArgumentCaptor.forClass(VehiclePropValue.class);
        verify(mVehicleHal).set(captor.capture());
        VehiclePropValue propValue = captor.getValue();
        assertThat(propValue.prop).isEqualTo(EPOCH_TIME);
        assertThat(propValue.areaId).isEqualTo(VehicleArea.GLOBAL);
        assertThat(propValue.status).isEqualTo(VehiclePropertyStatus.AVAILABLE);
        assertThat(propValue.timestamp).isAtLeast(sysTimeMillis);
        assertThat(propValue.value.int64Values).hasSize(1);
        assertThat(propValue.value.int64Values.get(0)).isAtLeast(sysTimeMillis);
    }

    @Test
    public void testReleaseUnregistersBroadcastReceiver() {
        mTimeHalService.takeProperties(Collections.singletonList(ANDROID_TIME_PROP));
        mTimeHalService.init();
        clearInvocations(mVehicleHal);

        mTimeHalService.release();

        mFakeContext.verifyReceiverNotRegistered();
        assertThat(mTimeHalService.isAndroidTimeSupported()).isFalse();
    }

    @Test
    public void testSendsAndroidTimeUpdateWhenBroadcast() {
        mTimeHalService.takeProperties(Collections.singletonList(ANDROID_TIME_PROP));
        mTimeHalService.init();
        clearInvocations(mVehicleHal);
        long sysTimeMillis = System.currentTimeMillis();

        mFakeContext.sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));

        ArgumentCaptor<VehiclePropValue> captor = ArgumentCaptor.forClass(VehiclePropValue.class);
        verify(mVehicleHal).set(captor.capture());
        VehiclePropValue propValue = captor.getValue();
        assertThat(propValue.prop).isEqualTo(EPOCH_TIME);
        assertThat(propValue.areaId).isEqualTo(VehicleArea.GLOBAL);
        assertThat(propValue.status).isEqualTo(VehiclePropertyStatus.AVAILABLE);
        assertThat(propValue.timestamp).isAtLeast(sysTimeMillis);
        assertThat(propValue.value.int64Values).hasSize(1);
        assertThat(propValue.value.int64Values.get(0)).isAtLeast(sysTimeMillis);
    }
}
