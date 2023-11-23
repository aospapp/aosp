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

package com.android.devicelockcontroller.provision.worker;

import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceIdType.DEVICE_ID_TYPE_IMEI;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.ListenableWorker.Result;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.TestWorkerBuilder;

import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RunWith(RobolectricTestRunner.class)
public class DeviceCheckInWorkerTest {
    public static final ArraySet<DeviceId> TEST_DEVICE_IDS = new ArraySet<>(
            new DeviceId[]{new DeviceId(DEVICE_ID_TYPE_IMEI, "1234667890")});
    public static final ArraySet<DeviceId> EMPTY_DEVICE_IDS = new ArraySet<>(new DeviceId[]{});
    public static final String TEST_CARRIER_INFO = "1234567890";
    public static final String EMPTY_CARRIER_INFO = "";
    @Rule
    public final MockitoRule mMocks = MockitoJUnit.rule();
    @Mock
    private AbstractDeviceCheckInHelper mHelper;
    @Mock
    private DeviceCheckInClient mClient;
    @Mock
    private GetDeviceCheckInStatusGrpcResponse mResponse;
    private DeviceCheckInWorker mWorker;

    @Before
    public void setUp() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        final Executor executor = Executors.newSingleThreadExecutor();
        when(mClient.getDeviceCheckInStatus(
                eq(TEST_DEVICE_IDS), anyString(), isNull())).thenReturn(mResponse);
        mWorker = TestWorkerBuilder.from(
                        context, DeviceCheckInWorker.class, executor)
                .setWorkerFactory(
                        new WorkerFactory() {
                            @Override
                            public ListenableWorker createWorker(
                                    @NonNull Context context, @NonNull String workerClassName,
                                    @NonNull WorkerParameters workerParameters) {
                                return workerClassName.equals(DeviceCheckInWorker.class.getName())
                                        ? new DeviceCheckInWorker(
                                        context, workerParameters, mHelper, mClient)
                                        : null;
                            }
                        }).build();
    }

    @Test
    public void checkIn_allInfoAvailable_checkInResponseSuccessful_succeeded() {
        // GIVEN all device info available
        setDeviceIdAvailability(true);
        setCarrierInfoAvailability(true);

        // GIVEN check-in response is successful
        setCheckInRequestSuccessful();

        // WHEN work runs
        final Result result = mWorker.doWork();

        // THEN work succeeded
        assertThat(result).isEqualTo(Result.success());
    }

    @Test
    public void checkIn_carrierInfoUnavailable_shouldAtLeastSendCheckInRequest() {
        // GIVEN only device ids available
        setDeviceIdAvailability(true);
        setCarrierInfoAvailability(false);

        // WHEN work runs
        mWorker.doWork();

        // THEN check-in is requested
        verify(mClient).getDeviceCheckInStatus(eq(TEST_DEVICE_IDS), eq(EMPTY_CARRIER_INFO),
                isNull());
    }

    @Test
    public void checkIn_deviceIdsUnavailable_shouldNotSendCheckInRequest() {
        // GIVEN only device ids available
        setDeviceIdAvailability(false);
        setCarrierInfoAvailability(false);

        // WHEN work runs
        mWorker.doWork();

        // THEN check-in is not requested
        verify(mClient, never()).getDeviceCheckInStatus(eq(TEST_DEVICE_IDS), eq(EMPTY_CARRIER_INFO),
                isNull());
    }

    private void setDeviceIdAvailability(boolean isAvailable) {
        when(mHelper.getDeviceUniqueIds()).thenReturn(
                isAvailable ? TEST_DEVICE_IDS : EMPTY_DEVICE_IDS);
    }

    private void setCarrierInfoAvailability(boolean isAvailable) {
        when(mHelper.getCarrierInfo()).thenReturn(
                isAvailable ? TEST_CARRIER_INFO : EMPTY_CARRIER_INFO);
    }

    private void setCheckInRequestSuccessful() {
        when(mResponse.isSuccessful()).thenReturn(true);
        when(mHelper.handleGetDeviceCheckInStatusResponse(mResponse)).thenReturn(true);
    }
}
