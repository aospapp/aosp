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

package com.android.car;

import static android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE;

import static com.android.car.internal.property.CarPropertyHelper.SYNC_OP_LIMIT_TRY_AGAIN;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.AreaIdConfig;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.hal.PropertyHalService;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.AsyncPropertyServiceRequestList;
import com.android.car.internal.property.IAsyncPropertyResultCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public final class CarPropertyServiceUnitTest {

    private static final String TAG = CarLog.tagFor(CarPropertyServiceUnitTest.class);

    @Mock
    private Context mContext;
    @Mock
    private PropertyHalService mHalService;
    @Mock
    private ICarPropertyEventListener mICarPropertyEventListener;
    @Mock
    private IBinder mIBinder;
    @Mock
    private IAsyncPropertyResultCallback mAsyncPropertyResultCallback;
    @Mock
    private CarPropertyConfig<?> mCarPropertyConfig;

    private CarPropertyService mService;

    private static final int SPEED_ID = VehiclePropertyIds.PERF_VEHICLE_SPEED;
    private static final int HVAC_TEMP = VehiclePropertyIds.HVAC_TEMPERATURE_SET;
    private static final int CONTINUOUS_READ_ONLY_PROPERTY_ID = 98732;
    private static final int WRITE_ONLY_INT_PROPERTY_ID = 12345;
    private static final int WRITE_ONLY_LONG_PROPERTY_ID = 22345;
    private static final int WRITE_ONLY_FLOAT_PROPERTY_ID = 32345;
    private static final int WRITE_ONLY_ENUM_PROPERTY_ID = 112345;
    private static final int WRITE_ONLY_OTHER_ENUM_PROPERTY_ID =
            VehiclePropertyIds.CRUISE_CONTROL_TYPE;
    private static final int READ_WRITE_INT_PROPERTY_ID = 42345;

    private static final int ON_CHANGE_READ_WRITE_PROPERTY_ID = 1111;
    private static final int NO_PERMISSION_PROPERTY_ID = 13292;
    private static final String GRANTED_PERMISSION = "GRANTED_PERMISSION";
    private static final String DENIED_PERMISSION = "DENIED_PERMISSION";
    private static final int GLOBAL_AREA_ID = 0;
    private static final int NOT_SUPPORTED_AREA_ID = -1;
    private static final float MIN_SAMPLE_RATE = 2;
    private static final float MAX_SAMPLE_RATE = 10;
    private static final long ASYNC_TIMEOUT_MS = 1000L;
    private static final int MIN_INT_VALUE = 10;
    private static final int MAX_INT_VALUE = 20;
    private static final long MIN_LONG_VALUE = 100;
    private static final long MAX_LONG_VALUE = 200;
    private static final float MIN_FLOAT_VALUE = 0.5f;
    private static final float MAX_FLOAT_VALUE = 5.5f;
    private static final List<Integer> SUPPORTED_ENUM_VALUES = List.of(-1, 0, 1, 2, 4, 5);
    private static final Integer UNSUPPORTED_ENUM_VALUE = 3;
    private static final Integer SUPPORTED_ERROR_STATE_ENUM_VALUE = -1;
    private static final Integer SUPPORTED_OTHER_STATE_ENUM_VALUE = 0;
    private static final int TEST_VALUE = 18;
    private static final CarPropertyValue TEST_PROPERTY_VALUE = new CarPropertyValue(
            READ_WRITE_INT_PROPERTY_ID, /* areaId= */ 0, TEST_VALUE);

    @Before
    public void setUp() {

        when(mICarPropertyEventListener.asBinder()).thenReturn(mIBinder);
        when(mContext.checkCallingOrSelfPermission(GRANTED_PERMISSION)).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(DENIED_PERMISSION)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        SparseArray<CarPropertyConfig<?>> configs = new SparseArray<>();
        configs.put(SPEED_ID, CarPropertyConfig.newBuilder(Float.class, SPEED_ID,
                VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1).addAreaConfig(GLOBAL_AREA_ID, null,
                null).setAccess(CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ).setChangeMode(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS).setMaxSampleRate(
                100).setMinSampleRate(1).build());
        when(mHalService.getReadPermission(SPEED_ID)).thenReturn(GRANTED_PERMISSION);
        // HVAC_TEMP is actually not a global property, but for simplicity, make it global here.
        configs.put(HVAC_TEMP, CarPropertyConfig.newBuilder(Float.class, HVAC_TEMP,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL)
                .addAreaConfig(GLOBAL_AREA_ID, null, null)
                .setAccess(CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ)
                .build());
        when(mHalService.getReadPermission(HVAC_TEMP)).thenReturn(GRANTED_PERMISSION);
        configs.put(VehiclePropertyIds.GEAR_SELECTION,
                CarPropertyConfig.newBuilder(Integer.class, VehiclePropertyIds.GEAR_SELECTION,
                                VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL)
                        .setAccess(CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ)
                        .build());
        // Property with read or read/write access
        when(mHalService.getReadPermission(CONTINUOUS_READ_ONLY_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(CONTINUOUS_READ_ONLY_PROPERTY_ID, CarPropertyConfig.newBuilder(Integer.class,
                CONTINUOUS_READ_ONLY_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaConfig(GLOBAL_AREA_ID, null, null).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ).setChangeMode(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS).setMinSampleRate(
                MIN_SAMPLE_RATE).setMaxSampleRate(MAX_SAMPLE_RATE).build());
        when(mHalService.getWritePermission(WRITE_ONLY_INT_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(WRITE_ONLY_INT_PROPERTY_ID, CarPropertyConfig.newBuilder(Integer.class,
                WRITE_ONLY_INT_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaConfig(GLOBAL_AREA_ID, MIN_INT_VALUE, MAX_INT_VALUE).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE).build());
        when(mHalService.getWritePermission(WRITE_ONLY_LONG_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(WRITE_ONLY_LONG_PROPERTY_ID, CarPropertyConfig.newBuilder(Long.class,
                WRITE_ONLY_LONG_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaConfig(GLOBAL_AREA_ID, MIN_LONG_VALUE, MAX_LONG_VALUE).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE).build());
        when(mHalService.getWritePermission(WRITE_ONLY_FLOAT_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(WRITE_ONLY_FLOAT_PROPERTY_ID, CarPropertyConfig.newBuilder(Float.class,
                WRITE_ONLY_FLOAT_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaConfig(GLOBAL_AREA_ID, MIN_FLOAT_VALUE, MAX_FLOAT_VALUE).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE).build());
        when(mHalService.getWritePermission(WRITE_ONLY_ENUM_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(WRITE_ONLY_ENUM_PROPERTY_ID, CarPropertyConfig.newBuilder(Integer.class,
                WRITE_ONLY_ENUM_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaIdConfig(new AreaIdConfig.Builder(GLOBAL_AREA_ID).setSupportedEnumValues(
                SUPPORTED_ENUM_VALUES).build()).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE).build());
        when(mHalService.getWritePermission(WRITE_ONLY_OTHER_ENUM_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(WRITE_ONLY_OTHER_ENUM_PROPERTY_ID, CarPropertyConfig.newBuilder(Integer.class,
                WRITE_ONLY_OTHER_ENUM_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaIdConfig(new AreaIdConfig.Builder(GLOBAL_AREA_ID).setSupportedEnumValues(
                SUPPORTED_ENUM_VALUES).build()).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE).build());
        when(mHalService.getReadPermission(ON_CHANGE_READ_WRITE_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        when(mHalService.getWritePermission(ON_CHANGE_READ_WRITE_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(ON_CHANGE_READ_WRITE_PROPERTY_ID, CarPropertyConfig.newBuilder(Integer.class,
                ON_CHANGE_READ_WRITE_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaConfig(GLOBAL_AREA_ID, null, null).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE).setChangeMode(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE).build());
        configs.put(NO_PERMISSION_PROPERTY_ID, CarPropertyConfig.newBuilder(Integer.class,
                NO_PERMISSION_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaConfig(GLOBAL_AREA_ID, null, null).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE).build());

        when(mHalService.getWritePermission(READ_WRITE_INT_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        when(mHalService.getReadPermission(READ_WRITE_INT_PROPERTY_ID)).thenReturn(
                GRANTED_PERMISSION);
        configs.put(READ_WRITE_INT_PROPERTY_ID, CarPropertyConfig.newBuilder(Integer.class,
                READ_WRITE_INT_PROPERTY_ID, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                1).addAreaConfig(GLOBAL_AREA_ID, MIN_INT_VALUE, MAX_INT_VALUE).setAccess(
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE).build());
        when(mHalService.getPropertyList()).thenReturn(configs);

        SparseArray<Pair<String, String>> propToPermission = new SparseArray<>();
        propToPermission.put(ON_CHANGE_READ_WRITE_PROPERTY_ID,
                new Pair<String, String>(DENIED_PERMISSION, Car.PERMISSION_CONTROL_DISPLAY_UNITS));
        when(mHalService.getPermissionsForAllProperties()).thenReturn(
                propToPermission);

        mService = new CarPropertyService(mContext, mHalService);
        mService.init();
    }

    @Test
    public void testGetPropertiesAsync() {
        AsyncPropertyServiceRequest getPropertyServiceRequest = new AsyncPropertyServiceRequest(0,
                SPEED_ID, 0);
        List<AsyncPropertyServiceRequest> requests = List.of(getPropertyServiceRequest);

        mService.getPropertiesAsync(new AsyncPropertyServiceRequestList(requests),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS);

        verify(mHalService).getCarPropertyValuesAsync(eq(requests), any(), eq(ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testGetPropertiesAsync_throwsExceptionBecauseOfNullRequests() {
        assertThrows(NullPointerException.class,
                () -> mService.getPropertiesAsync(null, mAsyncPropertyResultCallback,
                        ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testGetPropertiesAsync_throwsExceptionBecauseOfNullCallback() {
        assertThrows(NullPointerException.class,
                () -> mService.getPropertiesAsync(new AsyncPropertyServiceRequestList(List.of()),
                        null, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testGetPropertiesAsync_propertyIdNotSupported() {
        int invalidPropertyID = -1;
        AsyncPropertyServiceRequest getPropertyServiceRequest = new AsyncPropertyServiceRequest(0,
                invalidPropertyID, 0);

        assertThrows(IllegalArgumentException.class, () -> mService.getPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(getPropertyServiceRequest)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testGetPropertiesAsync_noReadPermission() {
        AsyncPropertyServiceRequest getPropertyServiceRequest = new AsyncPropertyServiceRequest(0,
                SPEED_ID, 0);
        when(mHalService.getReadPermission(SPEED_ID)).thenReturn(DENIED_PERMISSION);

        assertThrows(SecurityException.class, () -> mService.getPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(getPropertyServiceRequest)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testGetPropertiesAsync_propertyNotReadable() {
        AsyncPropertyServiceRequest getPropertyServiceRequest = new AsyncPropertyServiceRequest(0,
                WRITE_ONLY_INT_PROPERTY_ID, 0);

        assertThrows(IllegalArgumentException.class, () -> mService.getPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(getPropertyServiceRequest)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testGetPropertiesAsync_areaIdNotSupported() {
        AsyncPropertyServiceRequest getPropertyServiceRequest = new AsyncPropertyServiceRequest(0,
                HVAC_TEMP, NOT_SUPPORTED_AREA_ID);

        assertThrows(IllegalArgumentException.class, () -> mService.getPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(getPropertyServiceRequest)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testGetPropertiesAsync_timeoutNotPositiveNumber() {
        AsyncPropertyServiceRequest getPropertyServiceRequest = new AsyncPropertyServiceRequest(0,
                SPEED_ID, 0);

        assertThrows(IllegalArgumentException.class, () -> mService.getPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(getPropertyServiceRequest)),
                mAsyncPropertyResultCallback, /* timeoutInMs= */ 0));
    }

    @Test
    public void testSetPropertiesAsync() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, TEST_PROPERTY_VALUE);
        List<AsyncPropertyServiceRequest> requests = List.of(request);

        mService.setPropertiesAsync(new AsyncPropertyServiceRequestList(requests),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS);

        verify(mHalService).setCarPropertyValuesAsync(eq(requests), any(), eq(ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_noWaitForPropertyUpdate() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, WRITE_ONLY_INT_PROPERTY_ID, 0, new CarPropertyValue(WRITE_ONLY_INT_PROPERTY_ID,
                        /* areaId= */ 0, /* value= */ 13));
        request.setWaitForPropertyUpdate(false);
        List<AsyncPropertyServiceRequest> requests = List.of(request);

        mService.setPropertiesAsync(new AsyncPropertyServiceRequestList(requests),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS);

        verify(mHalService).setCarPropertyValuesAsync(eq(requests), any(), eq(ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_throwsExceptionBecauseOfNullRequests() {
        assertThrows(NullPointerException.class,
                () -> mService.setPropertiesAsync(null, mAsyncPropertyResultCallback,
                        ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_throwsExceptionBecauseOfNullCallback() {
        assertThrows(NullPointerException.class,
                () -> mService.setPropertiesAsync(new AsyncPropertyServiceRequestList(List.of()),
                        null, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_mismatchPropertyId() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, new CarPropertyValue(SPEED_ID, 0, 1));

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_mismatchAreaId() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, new CarPropertyValue(
                        READ_WRITE_INT_PROPERTY_ID, /* areaId= */ 1, 1));

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_nullPropertyValueToSet() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, /* carPropertyValue= */ null);

        assertThrows(NullPointerException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_nullValueToSet() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, new CarPropertyValue(READ_WRITE_INT_PROPERTY_ID,
                        0, /* value= */ null));

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_mismatchPropertyType() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, new CarPropertyValue(READ_WRITE_INT_PROPERTY_ID,
                        0, Float.valueOf(1.1f)));

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_valueLargerThanMaxValue() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, new CarPropertyValue(READ_WRITE_INT_PROPERTY_ID,
                        0, MAX_INT_VALUE + 1));

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_valueSmallerThanMinValue() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, new CarPropertyValue(READ_WRITE_INT_PROPERTY_ID,
                        0, MIN_INT_VALUE - 1));

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_propertyIdNotSupported() {
        int invalidPropertyID = -1;
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, invalidPropertyID, 0, TEST_PROPERTY_VALUE);

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_noWritePermission() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, TEST_PROPERTY_VALUE);
        when(mHalService.getWritePermission(READ_WRITE_INT_PROPERTY_ID)).thenReturn(
                DENIED_PERMISSION);

        assertThrows(SecurityException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_propertyNotWrittable() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(0,
                SPEED_ID, 0, TEST_PROPERTY_VALUE);

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    // By default waitForPropertyUpdate is true, which requires the read permisison as well.
    @Test
    public void testSetPropertiesAsync_noReadPermission() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, TEST_PROPERTY_VALUE);
        when(mHalService.getReadPermission(READ_WRITE_INT_PROPERTY_ID)).thenReturn(
                DENIED_PERMISSION);

        assertThrows(SecurityException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    // By default waitForPropertyUpdate is true, which requires the read permisison as well.
    @Test
    public void testSetPropertiesAsync_propertyNotReadable() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(0,
                WRITE_ONLY_INT_PROPERTY_ID, 0, TEST_PROPERTY_VALUE);

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_areaIdNotSupported() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, NOT_SUPPORTED_AREA_ID, TEST_PROPERTY_VALUE);

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, ASYNC_TIMEOUT_MS));
    }

    @Test
    public void testSetPropertiesAsync_timeoutNotPositiveNumber() {
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                0, READ_WRITE_INT_PROPERTY_ID, 0, TEST_PROPERTY_VALUE);

        assertThrows(IllegalArgumentException.class, () -> mService.setPropertiesAsync(
                new AsyncPropertyServiceRequestList(List.of(request)),
                mAsyncPropertyResultCallback, /* timeoutInMs= */ 0));
    }

    @Test
    public void testRegisterUnregisterForContinuousProperty() throws Exception {
        ICarPropertyEventListener mMockHandler1 = mock(ICarPropertyEventListener.class);
        ICarPropertyEventListener mMockHandler2 = mock(ICarPropertyEventListener.class);
        // Must use two different binders because listener is uniquely identified by binder.
        IBinder mBinder1 = mock(IBinder.class);
        IBinder mBinder2 = mock(IBinder.class);
        when(mMockHandler1.asBinder()).thenReturn(mBinder1);
        when(mMockHandler2.asBinder()).thenReturn(mBinder2);
        // Initially SPEED_ID is not subscribed, so should return -1.
        when(mHalService.getSubscribedUpdateRateHz(SPEED_ID)).thenReturn(-1f);
        CarPropertyValue mValue = mock(CarPropertyValue.class);
        when(mHalService.getProperty(SPEED_ID, 0)).thenReturn(mValue);

        // Register the first listener.
        mService.registerListener(SPEED_ID, /* rate= */ 10, mMockHandler1);

        // Wait until we get the on property change event for the initial value.
        verify(mMockHandler1, timeout(5000)).onEvent(any());

        verify(mHalService).subscribeProperty(SPEED_ID, 10f);
        verify(mHalService).getProperty(SPEED_ID, 0);

        // Clean up invocation state.
        clearInvocations(mHalService);
        when(mHalService.getSubscribedUpdateRateHz(SPEED_ID)).thenReturn(10f);

        // Register the second listener.
        mService.registerListener(SPEED_ID, /* rate= */ 20, mMockHandler2);

        // Wait until we get the on property change event for the initial value.
        verify(mMockHandler2, timeout(5000)).onEvent(any());

        verify(mHalService).subscribeProperty(SPEED_ID, 20f);
        verify(mHalService).getProperty(SPEED_ID, 0);

        // Clean up invocation state.
        clearInvocations(mHalService);
        when(mHalService.getSubscribedUpdateRateHz(SPEED_ID)).thenReturn(20f);

        // Unregister the second listener, the first listener must still be registered.
        mService.unregisterListener(SPEED_ID, mMockHandler2);

        // The property must not be unsubscribed.
        verify(mHalService, never()).unsubscribeProperty(anyInt());
        // The subscription rate must be updated.
        verify(mHalService).subscribeProperty(SPEED_ID, 10f);
        when(mHalService.getSubscribedUpdateRateHz(SPEED_ID)).thenReturn(10f);

        // Unregister the first listener. We have no more listeners, must cause unsubscription.
        mService.unregisterListener(SPEED_ID, mMockHandler1);

        verify(mHalService).unsubscribeProperty(SPEED_ID);
    }

    @Test
    public void testRegisterUnregisterForOnChangeProperty() throws Exception {
        ICarPropertyEventListener mMockHandler1 = mock(ICarPropertyEventListener.class);
        ICarPropertyEventListener mMockHandler2 = mock(ICarPropertyEventListener.class);
        // Must use two different binders because listener is uniquely identified by binder.
        IBinder mBinder1 = mock(IBinder.class);
        IBinder mBinder2 = mock(IBinder.class);
        when(mMockHandler1.asBinder()).thenReturn(mBinder1);
        when(mMockHandler2.asBinder()).thenReturn(mBinder2);
        // Initially HVAC_TEMP is not subscribed, so should return -1.
        when(mHalService.getSubscribedUpdateRateHz(HVAC_TEMP)).thenReturn(-1f);
        CarPropertyValue mValue = mock(CarPropertyValue.class);
        when(mHalService.getProperty(HVAC_TEMP, 0)).thenReturn(mValue);

        // Register the first listener.
        mService.registerListener(HVAC_TEMP, /* rate= */ SENSOR_RATE_ONCHANGE, mMockHandler1);

        // Wait until we get the on property change event for the initial value.
        verify(mMockHandler1, timeout(5000)).onEvent(any());

        verify(mHalService).subscribeProperty(HVAC_TEMP, 0f);
        verify(mHalService).getProperty(HVAC_TEMP, 0);

        // Clean up invocation state.
        clearInvocations(mHalService);
        when(mHalService.getSubscribedUpdateRateHz(HVAC_TEMP)).thenReturn(0f);

        // Register the second listener.
        mService.registerListener(HVAC_TEMP, /* rate= */ SENSOR_RATE_ONCHANGE, mMockHandler2);

        // Wait until we get the on property change event for the initial value.
        verify(mMockHandler2, timeout(5000)).onEvent(any());

        // Must not subscribe again.
        verify(mHalService, never()).subscribeProperty(anyInt(), anyFloat());
        verify(mHalService).getProperty(HVAC_TEMP, 0);

        // Clean up invocation state.
        clearInvocations(mHalService);

        // Unregister the second listener, the first listener must still be registered.
        mService.unregisterListener(HVAC_TEMP, mMockHandler2);

        // The property must not be unsubscribed.
        verify(mHalService, never()).unsubscribeProperty(anyInt());

        // Unregister the first listener. We have no more listeners, must cause unsubscription.
        mService.unregisterListener(HVAC_TEMP, mMockHandler1);

        verify(mHalService).unsubscribeProperty(HVAC_TEMP);
    }

    private static class EventListener extends ICarPropertyEventListener.Stub{
        private final CarPropertyService mService;
        private List<CarPropertyEvent> mEvents;
        private Exception mException;

        EventListener(CarPropertyService service) {
            mService = service;
        }

        @Override
        public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
            CountDownLatch latch = new CountDownLatch(1);
            new Thread(() -> {
                // Call a function in CarPropertyService to make sure there is no dead lock. This
                // call doesn't actually do anything. We must use a new thread here because
                // the same thread can obtain the same lock again even if there is a dead lock.
                mService.getReadPermission(0);
                latch.countDown();
            }, "onEventThread").start();
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    mException = new Exception("timeout waiting for getReadPermission, dead lock?");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mException = new Exception("waiting for onEvent thread interrupted");
                return;
            }
            mEvents = events;
        }

        List<CarPropertyEvent> getEvents() throws Exception {
            if (mException != null) {
                throw mException;
            }
            return mEvents;
        }
    }

    @Test
    public void testOnEventCallback_MustNotHaveDeadLock() throws Exception {
        // This test checks that CarPropertyService must not hold any lock while calling
        // ICarPropertyListener's onEvent callback, otherwise it might cause dead lock if
        // the callback calls another function in CarPropertyService that requires the same lock.

        // We don't care about the result for getReadPermission so just return an empty map.
        when(mHalService.getPermissionsForAllProperties()).thenReturn(
                new SparseArray<Pair<String, String>>());
        mService.init();

        // Initially HVAC_TEMP is not subscribed, so should return -1.
        when(mHalService.getSubscribedUpdateRateHz(HVAC_TEMP)).thenReturn(-1f);
        CarPropertyValue<Float> value = new CarPropertyValue<Float>(HVAC_TEMP, 0, 1.0f);
        when(mHalService.getProperty(HVAC_TEMP, 0)).thenReturn(value);
        EventListener listener = new EventListener(mService);

        mService.registerListener(HVAC_TEMP, /* rate= */ SENSOR_RATE_ONCHANGE, listener);
        List<CarPropertyEvent> events = List.of(new CarPropertyEvent(0, value));
        mService.onPropertyChange(events);

        List<CarPropertyEvent> actualEvents = listener.getEvents();

        assertThat(actualEvents.size()).isEqualTo(events.size());
        for (int i = 0; i < events.size(); i++) {
            CarPropertyEvent actualEvent = actualEvents.get(i);
            CarPropertyEvent expectedEvent = events.get(i);
            assertThat(actualEvent.getEventType()).isEqualTo(expectedEvent.getEventType());
            assertThat(actualEvent.getErrorCode()).isEqualTo(expectedEvent.getErrorCode());
            CarPropertyValue actualCarPropertyValue = actualEvent.getCarPropertyValue();
            CarPropertyValue expectedCarPropertyValue = expectedEvent.getCarPropertyValue();
            assertThat(actualCarPropertyValue.getPropertyId()).isEqualTo(
                    expectedCarPropertyValue.getPropertyId());
            assertThat(actualCarPropertyValue.getAreaId()).isEqualTo(
                    expectedCarPropertyValue.getAreaId());
            assertThat(actualCarPropertyValue.getStatus()).isEqualTo(
                    expectedCarPropertyValue.getStatus());
            assertThat(actualCarPropertyValue.getTimestamp()).isEqualTo(
                    expectedCarPropertyValue.getTimestamp());
            assertThat(actualCarPropertyValue.getValue()).isEqualTo(
                    expectedCarPropertyValue.getValue());
        }
    }

    @Test
    public void getProperty_throwsExceptionBecauseOfUnsupportedPropertyId() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.getProperty(VehiclePropertyIds.INVALID,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
    }

    @Test
    public void getProperty_throwsExceptionBecausePropertyIsNotReadable() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.getProperty(WRITE_ONLY_INT_PROPERTY_ID,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
    }

    @Test
    public void getProperty_throwsSecurityExceptionIfPlatformDoesNotHavePermissionToRead() {
        assertThrows(SecurityException.class,
                () -> mService.getProperty(VehiclePropertyIds.GEAR_SELECTION, 0));
    }

    @Test
    public void getProperty_throwsSecurityExceptionIfAppDoesNotHavePermissionToRead() {
        when(mHalService.getReadPermission(VehiclePropertyIds.GEAR_SELECTION)).thenReturn(
                DENIED_PERMISSION);
        assertThrows(SecurityException.class,
                () -> mService.getProperty(VehiclePropertyIds.GEAR_SELECTION, 0));
    }

    @Test
    public void getProperty_throwsExceptionBecauseOfUnsupportedAreaId() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.getProperty(ON_CHANGE_READ_WRITE_PROPERTY_ID,
                        NOT_SUPPORTED_AREA_ID));
    }

    @Test
    public void
            getPropertyConfigList_returnEmptyIfNoVendorExtensionPermissionForDisplayUnitsProp() {
        when(mHalService.isDisplayUnitsProperty(ON_CHANGE_READ_WRITE_PROPERTY_ID)).thenReturn(true);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION)).thenReturn(
                PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_DISPLAY_UNITS))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        List<CarPropertyConfig> configList = mService.getPropertyConfigList(
                new int[] { ON_CHANGE_READ_WRITE_PROPERTY_ID }).getConfigs();
        assertThat(configList).isEmpty();
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfNullCarPropertyValue() {
        assertThrows(NullPointerException.class,
                () -> mService.setProperty(null, mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfNullListener() {
        assertThrows(NullPointerException.class, () -> mService.setProperty(
                new CarPropertyValue(ON_CHANGE_READ_WRITE_PROPERTY_ID, GLOBAL_AREA_ID,
                        Integer.MAX_VALUE), null));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfUnsupportedPropertyId() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(VehiclePropertyIds.INVALID, GLOBAL_AREA_ID, Integer.MAX_VALUE),
                mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecausePropertyIsNotWritable() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(CONTINUOUS_READ_ONLY_PROPERTY_ID, GLOBAL_AREA_ID,
                        Integer.MAX_VALUE), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsSecurityExceptionIfPlatformDoesNotHavePermissionToWrite() {
        assertThrows(SecurityException.class, () -> mService.setProperty(
                new CarPropertyValue(NO_PERMISSION_PROPERTY_ID, GLOBAL_AREA_ID, Integer.MAX_VALUE),
                mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsSecurityExceptionIfAppDoesNotHavePermissionToWrite() {
        when(mHalService.getWritePermission(NO_PERMISSION_PROPERTY_ID)).thenReturn(
                DENIED_PERMISSION);
        assertThrows(SecurityException.class, () -> mService.setProperty(
                new CarPropertyValue(NO_PERMISSION_PROPERTY_ID, GLOBAL_AREA_ID, Integer.MAX_VALUE),
                mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionIfNoVendorExtensionPermissionForDisplayUnitsProp() {
        when(mHalService.isDisplayUnitsProperty(ON_CHANGE_READ_WRITE_PROPERTY_ID)).thenReturn(true);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION)).thenReturn(
                PackageManager.PERMISSION_DENIED);
        assertThrows(SecurityException.class, () -> mService.setProperty(
                new CarPropertyValue(ON_CHANGE_READ_WRITE_PROPERTY_ID, GLOBAL_AREA_ID,
                        Integer.MAX_VALUE), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfUnsupportedAreaId() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(ON_CHANGE_READ_WRITE_PROPERTY_ID, NOT_SUPPORTED_AREA_ID,
                        Integer.MAX_VALUE), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfNullSetValue() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(ON_CHANGE_READ_WRITE_PROPERTY_ID, GLOBAL_AREA_ID, null),
                mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfSetValueTypeMismatch() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_INT_PROPERTY_ID, GLOBAL_AREA_ID, Float.MAX_VALUE),
                mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfIntSetValueIsLessThanMinValue() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_INT_PROPERTY_ID, GLOBAL_AREA_ID, MIN_INT_VALUE - 1),
                mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfIntSetValueIsGreaterThanMaxValue() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_INT_PROPERTY_ID, GLOBAL_AREA_ID, MAX_INT_VALUE + 1),
                mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfLongSetValueIsLessThanMinValue() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_LONG_PROPERTY_ID, GLOBAL_AREA_ID,
                        MIN_LONG_VALUE - 1), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfLongSetValueIsGreaterThanMaxValue() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_LONG_PROPERTY_ID, GLOBAL_AREA_ID,
                        MAX_LONG_VALUE + 1), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfFloatSetValueIsLessThanMinValue() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_LONG_PROPERTY_ID, GLOBAL_AREA_ID,
                        MIN_LONG_VALUE - 1), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfFloatSetValueIsGreaterThanMaxValue() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_FLOAT_PROPERTY_ID, GLOBAL_AREA_ID,
                        MAX_FLOAT_VALUE + 1), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfSetValueIsNotInSupportedEnumValues() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_ENUM_PROPERTY_ID, GLOBAL_AREA_ID,
                        UNSUPPORTED_ENUM_VALUE), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfSetValueIsErrorStateEnum() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_OTHER_ENUM_PROPERTY_ID, GLOBAL_AREA_ID,
                        SUPPORTED_ERROR_STATE_ENUM_VALUE), mICarPropertyEventListener));
    }

    @Test
    public void setProperty_throwsExceptionBecauseOfSetValueIsOtherStateEnum() {
        assertThrows(IllegalArgumentException.class, () -> mService.setProperty(
                new CarPropertyValue(WRITE_ONLY_OTHER_ENUM_PROPERTY_ID, GLOBAL_AREA_ID,
                        SUPPORTED_OTHER_STATE_ENUM_VALUE), mICarPropertyEventListener));
    }

    @Test
    public void registerListener_throwsExceptionBecauseOfNullListener() {
        assertThrows(NullPointerException.class,
                () -> mService.registerListener(ON_CHANGE_READ_WRITE_PROPERTY_ID,
                        CarPropertyManager.SENSOR_RATE_NORMAL, /* listener= */ null));
    }

    @Test
    public void registerListener_throwsExceptionBecauseOfUnsupportedPropertyId() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.registerListener(VehiclePropertyIds.INVALID,
                        CarPropertyManager.SENSOR_RATE_NORMAL, mICarPropertyEventListener));
    }

    @Test
    public void registerListener_throwsExceptionBecausePropertyIsNotReadable() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.registerListener(WRITE_ONLY_INT_PROPERTY_ID,
                        CarPropertyManager.SENSOR_RATE_NORMAL, mICarPropertyEventListener));
    }

    @Test
    public void registerListener_throwsSecurityExceptionIfPlatformDoesNotHavePermissionToRead() {
        assertThrows(SecurityException.class,
                () -> mService.registerListener(NO_PERMISSION_PROPERTY_ID, 0,
                        mICarPropertyEventListener));
    }

    @Test
    public void registerListener_throwsSecurityExceptionIfAppDoesNotHavePermissionToRead() {
        when(mHalService.getReadPermission(NO_PERMISSION_PROPERTY_ID)).thenReturn(
                DENIED_PERMISSION);
        assertThrows(SecurityException.class,
                () -> mService.registerListener(NO_PERMISSION_PROPERTY_ID, 0,
                        mICarPropertyEventListener));
    }

    @Test
    public void registerListener_updatesRateForNonContinuousProperty() {
        when(mHalService.getSubscribedUpdateRateHz(ON_CHANGE_READ_WRITE_PROPERTY_ID))
                .thenReturn(-1f);
        mService.registerListener(ON_CHANGE_READ_WRITE_PROPERTY_ID,
                CarPropertyManager.SENSOR_RATE_FAST, mICarPropertyEventListener);
        verify(mHalService).subscribeProperty(ON_CHANGE_READ_WRITE_PROPERTY_ID,
                SENSOR_RATE_ONCHANGE);
    }

    @Test
    public void registerListener_updatesRateToMinForContinuousProperty() {
        when(mHalService.getSubscribedUpdateRateHz(CONTINUOUS_READ_ONLY_PROPERTY_ID))
                .thenReturn(-1f);
        mService.registerListener(CONTINUOUS_READ_ONLY_PROPERTY_ID, MIN_SAMPLE_RATE - 1,
                mICarPropertyEventListener);
        verify(mHalService).subscribeProperty(CONTINUOUS_READ_ONLY_PROPERTY_ID, MIN_SAMPLE_RATE);
    }

    @Test
    public void registerListener_updatesRateToMaxForContinuousProperty() {
        when(mHalService.getSubscribedUpdateRateHz(CONTINUOUS_READ_ONLY_PROPERTY_ID))
                .thenReturn(-1f);
        mService.registerListener(CONTINUOUS_READ_ONLY_PROPERTY_ID, MAX_SAMPLE_RATE + 1,
                mICarPropertyEventListener);
        verify(mHalService).subscribeProperty(CONTINUOUS_READ_ONLY_PROPERTY_ID, MAX_SAMPLE_RATE);
    }

    @Test
    public void registerListenerSafe_returnsTrueWhenSuccessful() {
        assertThat(mService.registerListenerSafe(ON_CHANGE_READ_WRITE_PROPERTY_ID,
                CarPropertyManager.SENSOR_RATE_NORMAL, mICarPropertyEventListener)).isTrue();
    }

    @Test
    public void registerListenerSafe_noExceptionBecauseOfUnsupportedPropertyIdAndReturnsFalse() {
        assertThat(mService.registerListenerSafe(VehiclePropertyIds.INVALID,
                CarPropertyManager.SENSOR_RATE_NORMAL, mICarPropertyEventListener)).isFalse();
    }

    @Test
    public void unregisterListener_throwsExceptionBecauseOfNullListener() {
        assertThrows(NullPointerException.class,
                () -> mService.unregisterListener(ON_CHANGE_READ_WRITE_PROPERTY_ID, /* listener= */
                        null));
    }

    @Test
    public void unregisterListener_throwsExceptionBecauseOfUnsupportedPropertyId() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.unregisterListener(VehiclePropertyIds.INVALID,
                        mICarPropertyEventListener));
    }

    @Test
    public void unregisterListener_throwsExceptionBecausePropertyIsNotReadable() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.unregisterListener(WRITE_ONLY_INT_PROPERTY_ID,
                        mICarPropertyEventListener));
    }

    @Test
    public void unregisterListener_throwsSecurityExceptionIfPlatformDoesNotHavePermissionToRead() {
        assertThrows(SecurityException.class,
                () -> mService.unregisterListener(NO_PERMISSION_PROPERTY_ID,
                        mICarPropertyEventListener));
    }

    @Test
    public void unregisterListener_throwsSecurityExceptionIfAppDoesNotHavePermissionToRead() {
        when(mHalService.getReadPermission(NO_PERMISSION_PROPERTY_ID)).thenReturn(
                DENIED_PERMISSION);
        assertThrows(SecurityException.class,
                () -> mService.unregisterListener(NO_PERMISSION_PROPERTY_ID,
                        mICarPropertyEventListener));
    }

    @Test
    public void unregisterListenerSafe_returnsTrueWhenSuccessful() {
        assertThat(mService.unregisterListenerSafe(ON_CHANGE_READ_WRITE_PROPERTY_ID,
                mICarPropertyEventListener)).isTrue();
    }

    @Test
    public void unregisterListenerSafe_noExceptionBecauseOfUnsupportedPropertyIdAndReturnsFalse() {
        assertThat(mService.unregisterListenerSafe(VehiclePropertyIds.INVALID,
                mICarPropertyEventListener)).isFalse();
    }

    @Test
    public void testCancelRequests() {
        int[] requestIds = new int[]{1, 2};

        mService.cancelRequests(requestIds);

        verify(mHalService).cancelRequests(requestIds);
    }

    // Test that limited number of sync operations are allowed at once.
    @Test
    public void testSyncOperationLimit() throws Exception {
        CountDownLatch startCd = new CountDownLatch(16);
        CountDownLatch finishCd = new CountDownLatch(16);
        CountDownLatch returnCd = new CountDownLatch(1);
        when(mHalService.getProperty(CONTINUOUS_READ_ONLY_PROPERTY_ID, 0))
                .thenReturn(new CarPropertyValue(HVAC_TEMP, /* areaId= */ 0, Float.valueOf(11f)));
        doAnswer((invocation) -> {
            // Notify the operation has started.
            startCd.countDown();

            Log.d(TAG, "simulate sync operation ongoing, waiting for signal before finish.");

            // Wait for the signal before finishing the operation.
            returnCd.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mHalService).setProperty(any());

        Executor executor = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 16; i++) {
            executor.execute(() -> {
                mService.setProperty(
                        new CarPropertyValue(WRITE_ONLY_INT_PROPERTY_ID, GLOBAL_AREA_ID,
                                Integer.valueOf(11)),
                        mICarPropertyEventListener);
                finishCd.countDown();
            });
        }

        // Wait until 16 requests are ongoing.
        startCd.await(10, TimeUnit.SECONDS);

        // The 17th request must throw exception.
        Exception e = assertThrows(ServiceSpecificException.class, () -> mService.getProperty(
                CONTINUOUS_READ_ONLY_PROPERTY_ID, /* areaId= */ 0));
        assertThat(((ServiceSpecificException) e).errorCode).isEqualTo(SYNC_OP_LIMIT_TRY_AGAIN);

        // Unblock the operations.
        returnCd.countDown();
        assertWithMessage("All setProperty operations finished within 10 seconds")
                .that(finishCd.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
