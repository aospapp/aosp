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

import static android.car.VehiclePropertyIds.FUEL_DOOR_OPEN;
import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET;
import static android.car.VehiclePropertyIds.INFO_EV_CONNECTOR_TYPE;
import static android.car.VehiclePropertyIds.INFO_FUEL_DOOR_LOCATION;
import static android.car.VehiclePropertyIds.INVALID;
import static android.car.hardware.property.CarPropertyManager.GetPropertyResult;
import static android.car.hardware.property.CarPropertyManager.PropertyAsyncError;
import static android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE;
import static android.car.hardware.property.CarPropertyManager.SetPropertyRequest;
import static android.car.hardware.property.CarPropertyManager.SetPropertyResult;

import static com.android.car.internal.property.CarPropertyHelper.SYNC_OP_LIMIT_TRY_AGAIN;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarInternalErrorException;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.ICarProperty;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.hardware.property.PropertyAccessDeniedSecurityException;
import android.car.hardware.property.PropertyNotAvailableAndRetryException;
import android.car.hardware.property.PropertyNotAvailableErrorCode;
import android.car.hardware.property.PropertyNotAvailableException;
import android.car.hardware.property.VehicleHalStatusCode;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;

import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.AsyncPropertyServiceRequestList;
import com.android.car.internal.property.CarPropertyConfigList;
import com.android.car.internal.property.GetSetValueResult;
import com.android.car.internal.property.GetSetValueResultList;
import com.android.car.internal.property.IAsyncPropertyResultCallback;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>This class contains unit tests for the {@link CarPropertyManager}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class CarPropertyManagerUnitTest {
    // Defined as vendor property.
    private static final int VENDOR_CONTINUOUS_PROPERTY = 0x21100111;
    private static final int VENDOR_ON_CHANGE_PROPERTY = 0x21100222;
    private static final int VENDOR_STATIC_PROPERTY = 0x21100333;

    private static final int BOOLEAN_PROP = FUEL_DOOR_OPEN;
    private static final int INT32_PROP = INFO_FUEL_DOOR_LOCATION;
    private static final int INT32_VEC_PROP = INFO_EV_CONNECTOR_TYPE;
    private static final int FLOAT_PROP = HVAC_TEMPERATURE_SET;

    private static final float MIN_UPDATE_RATE_HZ = 10;
    private static final float MAX_UPDATE_RATE_HZ = 100;
    private static final float FIRST_UPDATE_RATE_HZ = 50;
    private static final float LARGER_UPDATE_RATE_HZ = 50.1f;
    private static final float SMALLER_UPDATE_RATE_HZ = 49.9f;

    private static final int VENDOR_ERROR_CODE = 0x2;
    private static final int VENDOR_ERROR_CODE_SHIFT = 16;
    private static final int UNKNOWN_ERROR = -101;

    private static final long TEST_TIMESTAMP = 1234;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Mock
    private Car mCar;
    @Mock
    private ApplicationInfo mApplicationInfo;
    @Mock
    private ICarProperty mICarProperty;
    @Mock
    private Context mContext;
    @Mock
    private CarPropertyManager.CarPropertyEventCallback mCarPropertyEventCallback;
    @Mock
    private CarPropertyManager.CarPropertyEventCallback mCarPropertyEventCallback2;
    @Mock
    private CarPropertyConfig mContinuousCarPropertyConfig;
    @Mock
    private CarPropertyConfig mOnChangeCarPropertyConfig;
    @Mock
    private CarPropertyConfig mStaticCarPropertyConfig;
    @Mock
    private CarPropertyManager.GetPropertyCallback mGetPropertyCallback;
    @Mock
    private CarPropertyManager.SetPropertyCallback mSetPropertyCallback;

    @Captor
    private ArgumentCaptor<Integer> mPropertyIdCaptor;
    @Captor
    private ArgumentCaptor<Float> mUpdateRateHzCaptor;
    @Captor
    private ArgumentCaptor<AsyncPropertyServiceRequestList> mAsyncPropertyServiceRequestCaptor;
    @Captor
    private ArgumentCaptor<PropertyAsyncError> mPropertyAsyncErrorCaptor;
    @Captor
    private ArgumentCaptor<GetPropertyResult<?>> mGetPropertyResultCaptor;
    @Captor
    private ArgumentCaptor<SetPropertyResult> mSetPropertyResultCaptor;
    private CarPropertyManager mCarPropertyManager;

    private static List<CarPropertyEvent> createErrorCarPropertyEventList() {
        CarPropertyValue<Integer> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0,
                CarPropertyValue.STATUS_AVAILABLE, 0, -1);
        CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                CarPropertyEvent.PROPERTY_EVENT_ERROR, value,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
        return List.of(carPropertyEvent);
    }

    private static List<CarPropertyEvent> createCarPropertyEventList() {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);
        CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, value);
        return List.of(carPropertyEvent);
    }

    @Before
    public void setUp() throws RemoteException {
        when(mCar.getContext()).thenReturn(mContext);
        when(mCar.getEventHandler()).thenReturn(mMainHandler);
        when(mCar.handleRemoteExceptionFromCarService(any(RemoteException.class), any()))
                .thenAnswer((inv) -> {
                    return inv.getArgument(1);
                });
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContinuousCarPropertyConfig.getChangeMode()).thenReturn(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS);
        when(mContinuousCarPropertyConfig.getMinSampleRate()).thenReturn(MIN_UPDATE_RATE_HZ);
        when(mContinuousCarPropertyConfig.getMaxSampleRate()).thenReturn(MAX_UPDATE_RATE_HZ);
        when(mOnChangeCarPropertyConfig.getChangeMode()).thenReturn(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE);
        when(mStaticCarPropertyConfig.getChangeMode()).thenReturn(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC);
        when(mICarProperty.getPropertyConfigList(new int[]{VENDOR_CONTINUOUS_PROPERTY})).thenReturn(
                new CarPropertyConfigList(
                        ImmutableList.of(mContinuousCarPropertyConfig)));
        when(mICarProperty.getPropertyConfigList(new int[]{VENDOR_ON_CHANGE_PROPERTY})).thenReturn(
                new CarPropertyConfigList(ImmutableList.of(mOnChangeCarPropertyConfig)));
        when(mICarProperty.getPropertyConfigList(new int[]{VENDOR_STATIC_PROPERTY})).thenReturn(
                new CarPropertyConfigList(ImmutableList.of(mStaticCarPropertyConfig)));
        mCarPropertyManager = new CarPropertyManager(mCar, mICarProperty);
    }

    @Test
    public void testGetProperty_returnsValue() throws RemoteException {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0)).isEqualTo(value);
    }

    @Test
    public void testGetProperty_unsupportedProperty() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getProperty(INVALID, 0));
    }

    @Test
    public void testGetProperty_syncOpTryAgain() throws RemoteException {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(SYNC_OP_LIMIT_TRY_AGAIN)).thenReturn(value);

        assertThat(mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0)).isEqualTo(value);
        verify(mICarProperty, times(2)).getProperty(HVAC_TEMPERATURE_SET, 0);
    }

    @Test
    public void testGetProperty_syncOpTryAgain_exceedRetryCountLimit() throws RemoteException {
        // Car service will throw CarInternalException with version >= R.
        setAppTargetSdk(Build.VERSION_CODES.R);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(SYNC_OP_LIMIT_TRY_AGAIN));

        assertThrows(CarInternalErrorException.class, () ->
                mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
        verify(mICarProperty, times(10)).getProperty(HVAC_TEMPERATURE_SET, 0);
    }

    private void setAppTargetSdk(int appTargetSdk) {
        mApplicationInfo.targetSdkVersion = appTargetSdk;
        mCarPropertyManager = new CarPropertyManager(mCar, mICarProperty);
    }

    @Test
    public void testGetProperty_notAvailableBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_notAvailableEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0)).isNull();
    }

    @Test
    public void testGetProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_accessDeniedBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_ACCESS_DENIED));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_accessDeniedEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_ACCESS_DENIED));

        assertThrows(PropertyAccessDeniedSecurityException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_internalErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_INTERNAL_ERROR));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_internalErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_INTERNAL_ERROR));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_internalErrorEqualAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(combineErrors(
                        VehicleHalStatusCode.STATUS_INTERNAL_ERROR, VENDOR_ERROR_CODE)));

        CarInternalErrorException exception = assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));

        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testGetProperty_unknownErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(UNKNOWN_ERROR));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_unknownErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(UNKNOWN_ERROR));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetPropertyWithClass() throws Exception {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getProperty(Float.class, HVAC_TEMPERATURE_SET, 0).getValue())
                .isEqualTo(17.0f);
    }

    @Test
    public void testGetPropertyWithClass_mismatchClass() throws Exception {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(value);

        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getProperty(Integer.class, HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetPropertyWithClass_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getProperty(Float.class, HVAC_TEMPERATURE_SET, 0))
                .isNull();
    }

    @Test
    public void testGetPropertyWithClass_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getProperty(Float.class, HVAC_TEMPERATURE_SET, 0));
    }


    @Test
    public void testGetBooleanProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Boolean> value = new CarPropertyValue<>(BOOLEAN_PROP, 0, true);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0)).isTrue();
    }

    @Test
    public void testGetBooleanProperty_notAvailableBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_notAvailableEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetProperty_notAvailableEqualAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(combineErrors(
                        VehicleHalStatusCode.STATUS_NOT_AVAILABLE, VENDOR_ERROR_CODE)));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));

        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testGetProperty_notAvailableDisabledBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_notAvailableDisabledAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_DISABLED);
    }

    @Test
    public void testGetProperty_notAvailableDisabledAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(combineErrors(
                        VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED, VENDOR_ERROR_CODE)));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));

        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_DISABLED);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testGetProperty_notAvailableSafetyBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_notAvailableSafetyAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SAFETY);
    }

    @Test
    public void testGetProperty_notAvailableSafetyAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(combineErrors(
                        VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY, VENDOR_ERROR_CODE)));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));

        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SAFETY);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testGetProperty_notAvailableSpeedHighBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_notAvailableSpeedHighAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_HIGH);
    }

    @Test
    public void testGetProperty_notAvailableSpeedHighAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(combineErrors(
                        VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH, VENDOR_ERROR_CODE)));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));

        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_HIGH);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testGetProperty_notAvailableSpeedLowBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_notAvailableSpeedLowAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_LOW);
    }

    @Test
    public void testGetProperty_notAvailableSpeedLowAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(combineErrors(
                        VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW, VENDOR_ERROR_CODE)));

        PropertyNotAvailableException exception = assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));

        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_LOW);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testGetBooleanProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0)).isFalse();
    }

    @Test
    public void testGetBooleanProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_accessDeniedBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_ACCESS_DENIED));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_accessDeniedEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_ACCESS_DENIED));

        assertThrows(PropertyAccessDeniedSecurityException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_internalErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_INTERNAL_ERROR));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_internalErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_INTERNAL_ERROR));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_unknownErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(UNKNOWN_ERROR));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_unknownErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(UNKNOWN_ERROR));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetIntProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Integer> value = new CarPropertyValue<>(INT32_PROP, 0, 1);
        when(mICarProperty.getProperty(INT32_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getIntProperty(INT32_PROP, 0)).isEqualTo(1);
    }

    @Test
    public void testGetIntProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(INT32_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getIntProperty(INT32_PROP, 0)).isEqualTo(0);
    }

    @Test
    public void testGetIntProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(INT32_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getIntProperty(INT32_PROP, 0));
    }

    @Test
    public void testGetIntArrayProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Integer[]> value = new CarPropertyValue<>(INT32_VEC_PROP, 0,
                new Integer[]{1, 2});
        when(mICarProperty.getProperty(INT32_VEC_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getIntArrayProperty(INT32_VEC_PROP, 0)).isEqualTo(
                new int[]{1, 2});
    }

    @Test
    public void testGetIntArrayProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(INT32_VEC_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getIntArrayProperty(INT32_VEC_PROP, 0)).isEqualTo(
                new int[0]);
    }

    @Test
    public void testGetIntArrayProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(INT32_VEC_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getIntArrayProperty(INT32_VEC_PROP, 0));
    }

    @Test
    public void testGetFloatProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Float> value = new CarPropertyValue<>(FLOAT_PROP, 0, 1.f);
        when(mICarProperty.getProperty(FLOAT_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getFloatProperty(FLOAT_PROP, 0)).isEqualTo(1.f);
    }

    @Test
    public void testGetFloatProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(FLOAT_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getFloatProperty(FLOAT_PROP, 0)).isEqualTo(0.f);
    }

    @Test
    public void testGetFloatProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(FLOAT_PROP, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getFloatProperty(FLOAT_PROP, 0));
    }

    private CarPropertyManager.GetPropertyRequest createGetPropertyRequest() {
        return mCarPropertyManager.generateGetPropertyRequest(HVAC_TEMPERATURE_SET, 0);
    }

    @Test
    public void testGetPropertiesAsync() throws RemoteException {
        mCarPropertyManager.getPropertiesAsync(List.of(createGetPropertyRequest()), null, null,
                mGetPropertyCallback);

        ArgumentCaptor<AsyncPropertyServiceRequestList> argumentCaptor = ArgumentCaptor.forClass(
                AsyncPropertyServiceRequestList.class);
        verify(mICarProperty).getPropertiesAsync(argumentCaptor.capture(), any(), anyLong());
        assertThat(argumentCaptor.getValue().getList().get(0).getRequestId()).isEqualTo(0);
        assertThat(argumentCaptor.getValue().getList().get(0).getPropertyId())
                .isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(argumentCaptor.getValue().getList().get(0).getAreaId()).isEqualTo(0);
    }

    @Test
    public void testGetPropertiesAsyncWithTimeout() throws RemoteException {
        mCarPropertyManager.getPropertiesAsync(List.of(createGetPropertyRequest()),
                /* timeoutInMs= */ 1000, null, null, mGetPropertyCallback);

        ArgumentCaptor<AsyncPropertyServiceRequestList> argumentCaptor = ArgumentCaptor.forClass(
                AsyncPropertyServiceRequestList.class);
        verify(mICarProperty).getPropertiesAsync(argumentCaptor.capture(), any(), eq(1000L));
        assertThat(argumentCaptor.getValue().getList().get(0).getRequestId()).isEqualTo(0);
        assertThat(argumentCaptor.getValue().getList().get(0).getPropertyId())
                .isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(argumentCaptor.getValue().getList().get(0).getAreaId()).isEqualTo(0);
    }

    @Test
    public void testGetPropertiesAsync_illegalArgumentException() throws RemoteException {
        IllegalArgumentException exception = new IllegalArgumentException();
        doThrow(exception).when(mICarProperty).getPropertiesAsync(any(
                AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());

        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getPropertiesAsync(
                        List.of(createGetPropertyRequest()), null, null, mGetPropertyCallback));
    }

    @Test
    public void testGetPropertiesAsync_SecurityException() throws RemoteException {
        SecurityException exception = new SecurityException();
        doThrow(exception).when(mICarProperty).getPropertiesAsync(any(
                AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());

        assertThrows(SecurityException.class,
                () -> mCarPropertyManager.getPropertiesAsync(
                        List.of(createGetPropertyRequest()), null, null, mGetPropertyCallback));
    }

    @Test
    public void tsetGetPropertiesAsync_unsupportedProperty() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getPropertiesAsync(
                        List.of(mCarPropertyManager.generateGetPropertyRequest(INVALID, 0)), null,
                        null, mGetPropertyCallback));
    }

    @Test
    public void testGetPropertiesAsync_remoteException() throws RemoteException {
        RemoteException remoteException = new RemoteException();
        doThrow(remoteException).when(mICarProperty).getPropertiesAsync(any(
                AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());

        mCarPropertyManager.getPropertiesAsync(List.of(createGetPropertyRequest()), null, null,
                mGetPropertyCallback);

        verify(mCar).handleRemoteExceptionFromCarService(any(RemoteException.class));
    }

    @Test
    public void testGetPropertiesAsync_clearRequestIdAfterFailed() throws RemoteException {
        CarPropertyManager.GetPropertyRequest getPropertyRequest = createGetPropertyRequest();
        IllegalArgumentException exception = new IllegalArgumentException();
        doThrow(exception).when(mICarProperty).getPropertiesAsync(any(
                AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());

        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getPropertiesAsync(List.of(getPropertyRequest), null,
                        null, mGetPropertyCallback));

        clearInvocations(mICarProperty);
        doNothing().when(mICarProperty).getPropertiesAsync(any(
                AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());
        ArgumentCaptor<AsyncPropertyServiceRequestList> argumentCaptor = ArgumentCaptor.forClass(
                AsyncPropertyServiceRequestList.class);

        mCarPropertyManager.getPropertiesAsync(List.of(getPropertyRequest), null, null,
                mGetPropertyCallback);

        verify(mICarProperty).getPropertiesAsync(argumentCaptor.capture(), any(), anyLong());
        assertThat(argumentCaptor.getValue().getList().get(0).getRequestId()).isEqualTo(0);
        assertThat(argumentCaptor.getValue().getList().get(0).getPropertyId())
                .isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(argumentCaptor.getValue().getList().get(0).getAreaId()).isEqualTo(0);
    }

    @Test
    public void testGetPropertiesAsync_cancellationSignalCancelRequests() throws Exception {
        CarPropertyManager.GetPropertyRequest getPropertyRequest = createGetPropertyRequest();
        CancellationSignal cancellationSignal = new CancellationSignal();
        List<IAsyncPropertyResultCallback> callbackWrapper = new ArrayList<>();
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            callbackWrapper.add((IAsyncPropertyResultCallback) args[1]);
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());

        mCarPropertyManager.getPropertiesAsync(List.of(getPropertyRequest), cancellationSignal,
                /* callbackExecutor= */ null, mGetPropertyCallback);

        // Cancel the pending request.
        cancellationSignal.cancel();

        verify(mICarProperty).cancelRequests(new int[]{0});

        // Call the manager callback after the request is already cancelled.
        GetSetValueResult getValueResult = GetSetValueResult.newErrorResult(0,
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR, /* vendorErrorCode= */ 0);
        assertThat(callbackWrapper.size()).isEqualTo(1);
        callbackWrapper.get(0).onGetValueResults(
                new GetSetValueResultList(List.of(getValueResult)));

        // No client callbacks should be called.
        verify(mGetPropertyCallback, never()).onFailure(any());
        verify(mGetPropertyCallback, never()).onSuccess(any());
    }

    @Test
    public void testOnGetValueResult_onSuccess() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            AsyncPropertyServiceRequestList asyncGetPropertyServiceRequestList =
                    (AsyncPropertyServiceRequestList) args[0];
            List getPropertyServiceList = asyncGetPropertyServiceRequestList.getList();
            AsyncPropertyServiceRequest getPropertyServiceRequest =
                    (AsyncPropertyServiceRequest) getPropertyServiceList.get(0);
            IAsyncPropertyResultCallback getAsyncPropertyResultCallback =
                    (IAsyncPropertyResultCallback) args[1];

            assertThat(getPropertyServiceRequest.getRequestId()).isEqualTo(0);
            assertThat(getPropertyServiceRequest.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);

            CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);
            GetSetValueResult getValueResult = GetSetValueResult.newGetValueResult(0, value);

            getAsyncPropertyResultCallback.onGetValueResults(
                    new GetSetValueResultList(List.of(getValueResult)));
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());

        mCarPropertyManager.getPropertiesAsync(List.of(createGetPropertyRequest()), null, null,
                mGetPropertyCallback);

        verify(mGetPropertyCallback, timeout(1000)).onSuccess(
                mGetPropertyResultCaptor.capture());
        GetPropertyResult<Float> gotResult = (GetPropertyResult<Float>)
                mGetPropertyResultCaptor.getValue();
        assertThat(gotResult.getRequestId()).isEqualTo(0);
        assertThat(gotResult.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotResult.getAreaId()).isEqualTo(0);
        assertThat(gotResult.getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testOnGetValueResult_onSuccessMultipleRequests() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            AsyncPropertyServiceRequestList getPropertyServiceRequest =
                    (AsyncPropertyServiceRequestList) args[0];
            List<AsyncPropertyServiceRequest> getPropertyServiceRequests =
                    getPropertyServiceRequest.getList();
            IAsyncPropertyResultCallback getAsyncPropertyResultCallback =
                    (IAsyncPropertyResultCallback) args[1];

            assertThat(getPropertyServiceRequests.size()).isEqualTo(2);
            assertThat(getPropertyServiceRequests.get(0).getRequestId()).isEqualTo(0);
            assertThat(getPropertyServiceRequests.get(0).getPropertyId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);
            assertThat(getPropertyServiceRequests.get(1).getRequestId()).isEqualTo(1);
            assertThat(getPropertyServiceRequests.get(1).getPropertyId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);
            List<GetSetValueResult> getValueResults = List.of(
                    GetSetValueResult.newGetValueResult(0, value),
                    GetSetValueResult.newGetValueResult(1, value));

            getAsyncPropertyResultCallback.onGetValueResults(
                    new GetSetValueResultList(getValueResults));
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());

        List<CarPropertyManager.GetPropertyRequest> getPropertyRequests = new ArrayList<>();
        getPropertyRequests.add(createGetPropertyRequest());
        getPropertyRequests.add(createGetPropertyRequest());

        mCarPropertyManager.getPropertiesAsync(getPropertyRequests, null, null,
                mGetPropertyCallback);

        verify(mGetPropertyCallback, timeout(1000).times(2)).onSuccess(
                    mGetPropertyResultCaptor.capture());
        List<GetPropertyResult<?>> gotPropertyResults = mGetPropertyResultCaptor.getAllValues();
        assertThat(gotPropertyResults.get(0).getRequestId()).isEqualTo(0);
        assertThat(gotPropertyResults.get(0).getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotPropertyResults.get(0).getAreaId()).isEqualTo(0);
        assertThat(gotPropertyResults.get(0).getValue()).isEqualTo(17.0f);
        assertThat(gotPropertyResults.get(1).getRequestId()).isEqualTo(1);
        assertThat(gotPropertyResults.get(1).getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotPropertyResults.get(1).getAreaId()).isEqualTo(0);
        assertThat(gotPropertyResults.get(1).getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testOnGetValueResult_onFailure() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            AsyncPropertyServiceRequestList asyncPropertyServiceREquestList =
                    (AsyncPropertyServiceRequestList) args[0];
            List getPropertyServiceList = asyncPropertyServiceREquestList.getList();
            AsyncPropertyServiceRequest getPropertyServiceRequest =
                    (AsyncPropertyServiceRequest) getPropertyServiceList.get(0);
            IAsyncPropertyResultCallback getAsyncPropertyResultCallback =
                    (IAsyncPropertyResultCallback) args[1];

            assertThat(getPropertyServiceRequest.getRequestId()).isEqualTo(0);
            assertThat(getPropertyServiceRequest.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);

            GetSetValueResult getValueResult = GetSetValueResult.newErrorResult(0,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR, VENDOR_ERROR_CODE);

            getAsyncPropertyResultCallback.onGetValueResults(
                    new GetSetValueResultList(List.of(getValueResult)));
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());


        mCarPropertyManager.getPropertiesAsync(List.of(createGetPropertyRequest()), null, null,
                mGetPropertyCallback);

        verify(mGetPropertyCallback, timeout(1000)).onFailure(mPropertyAsyncErrorCaptor.capture());
        PropertyAsyncError error = mPropertyAsyncErrorCaptor.getValue();
        assertThat(error.getRequestId()).isEqualTo(0);
        assertThat(error.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(error.getAreaId()).isEqualTo(0);
        assertThat(error.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(error.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testSetProperty_setsValue() throws RemoteException {
        mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f);

        ArgumentCaptor<CarPropertyValue> value = ArgumentCaptor.forClass(CarPropertyValue.class);

        verify(mICarProperty).setProperty(value.capture(), any());
        assertThat(value.getValue().getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testSetProperty_syncOpTryAgain() throws RemoteException {
        doThrow(new ServiceSpecificException(SYNC_OP_LIMIT_TRY_AGAIN)).doNothing()
                .when(mICarProperty).setProperty(any(), any());

        mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f);

        verify(mICarProperty, times(2)).setProperty(any(), any());
    }

    @Test
    public void testSetProperty_unsupportedProperty() throws RemoteException {
        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.setProperty(Float.class, INVALID, 0, 17.0f));
    }

    private SetPropertyRequest<Float> createSetPropertyRequest() {
        return mCarPropertyManager.generateSetPropertyRequest(HVAC_TEMPERATURE_SET, 0,
                Float.valueOf(17.0f));
    }

    @Test
    public void testSetPropertiesAsync() throws RemoteException {
        SetPropertyRequest<Float> setPropertyRequest = createSetPropertyRequest();
        setPropertyRequest.setUpdateRateHz(10.1f);
        setPropertyRequest.setWaitForPropertyUpdate(false);
        mCarPropertyManager.setPropertiesAsync(List.of(setPropertyRequest), null, null,
                mSetPropertyCallback);

        verify(mICarProperty).setPropertiesAsync(mAsyncPropertyServiceRequestCaptor.capture(),
                any(), anyLong());

        AsyncPropertyServiceRequest request = mAsyncPropertyServiceRequestCaptor.getValue()
                .getList().get(0);

        assertThat(request.getRequestId()).isEqualTo(0);
        assertThat(request.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(request.getAreaId()).isEqualTo(0);
        assertThat(request.isWaitForPropertyUpdate()).isFalse();
        assertThat(request.getUpdateRateHz()).isEqualTo(10.1f);
        CarPropertyValue requestValue = request.getCarPropertyValue();
        assertThat(requestValue).isNotNull();
        assertThat(requestValue.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(requestValue.getAreaId()).isEqualTo(0);
        assertThat(requestValue.getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testSetPropertiesAsync_nullRequests() throws RemoteException {
        assertThrows(NullPointerException.class,
                () -> mCarPropertyManager.setPropertiesAsync(
                        null, null, null, mSetPropertyCallback));
    }

    @Test
    public void testSetPropertiesAsync_nullCallback() throws RemoteException {
        assertThrows(NullPointerException.class,
                () -> mCarPropertyManager.setPropertiesAsync(
                        List.of(createSetPropertyRequest()), null, null, null));
    }

    @Test
    public void testSetPropertiesAsyncWithTimeout() throws RemoteException {
        mCarPropertyManager.setPropertiesAsync(List.of(createSetPropertyRequest()),
                /* timeoutInMs= */ 1000, /* cancellationSignal= */ null,
                /* callbackExecutor= */ null, mSetPropertyCallback);

        verify(mICarProperty).setPropertiesAsync(mAsyncPropertyServiceRequestCaptor.capture(),
                any(), eq(1000L));
        AsyncPropertyServiceRequest request = mAsyncPropertyServiceRequestCaptor.getValue()
                .getList().get(0);
        assertThat(request.getRequestId()).isEqualTo(0);
        assertThat(request.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(request.getAreaId()).isEqualTo(0);
        CarPropertyValue requestValue = request.getCarPropertyValue();
        assertThat(requestValue).isNotNull();
        assertThat(requestValue.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(requestValue.getAreaId()).isEqualTo(0);
        assertThat(requestValue.getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testSetPropertiesAsync_illegalArgumentException() throws RemoteException {
        doThrow(new IllegalArgumentException()).when(mICarProperty).setPropertiesAsync(
                any(AsyncPropertyServiceRequestList.class), any(IAsyncPropertyResultCallback.class),
                anyLong());

        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.setPropertiesAsync(
                        List.of(createSetPropertyRequest()), null, null, mSetPropertyCallback));
    }

    @Test
    public void testSetPropertiesAsync_SecurityException() throws RemoteException {
        doThrow(new SecurityException()).when(mICarProperty).setPropertiesAsync(
                any(AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());

        assertThrows(SecurityException.class,
                () -> mCarPropertyManager.setPropertiesAsync(
                        List.of(createSetPropertyRequest()), null, null, mSetPropertyCallback));
    }

    @Test
    public void testSetPropertiesAsync_unsupportedProperty() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.setPropertiesAsync(
                        List.of(mCarPropertyManager.generateSetPropertyRequest(
                                INVALID, 0, Integer.valueOf(0))),
                        null, null, mSetPropertyCallback));
    }

    @Test
    public void testSetPropertiesAsync_remoteException() throws RemoteException {
        doThrow(new RemoteException()).when(mICarProperty).setPropertiesAsync(
                any(AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());

        mCarPropertyManager.setPropertiesAsync(List.of(createSetPropertyRequest()), null, null,
                mSetPropertyCallback);

        verify(mCar).handleRemoteExceptionFromCarService(any(RemoteException.class));
    }

    @Test
    public void testSetPropertiesAsync_duplicateRequestId() throws RemoteException {
        SetPropertyRequest request = createSetPropertyRequest();

        mCarPropertyManager.setPropertiesAsync(List.of(request), null, null,
                mSetPropertyCallback);

        // Send the same request again with the same request ID is not allowed.
        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.setPropertiesAsync(List.of(request), null, null,
                        mSetPropertyCallback));
    }

    @Test
    public void testSetPropertiesAsync_clearRequestIdAfterFailed() throws RemoteException {
        SetPropertyRequest setPropertyRequest = createSetPropertyRequest();
        IllegalArgumentException exception = new IllegalArgumentException();
        doThrow(exception).when(mICarProperty).setPropertiesAsync(
                any(AsyncPropertyServiceRequestList.class), any(IAsyncPropertyResultCallback.class),
                anyLong());

        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.setPropertiesAsync(List.of(setPropertyRequest), null,
                        null, mSetPropertyCallback));

        clearInvocations(mICarProperty);
        doNothing().when(mICarProperty).setPropertiesAsync(
                any(AsyncPropertyServiceRequestList.class),
                any(IAsyncPropertyResultCallback.class), anyLong());

        // After the first request failed, the request ID map should be cleared so we can use the
        // same request ID again.
        mCarPropertyManager.setPropertiesAsync(List.of(setPropertyRequest), null, null,
                mSetPropertyCallback);

        verify(mICarProperty).setPropertiesAsync(any(), any(), anyLong());
    }

    @Test
    public void testSetProperty_notAvailableAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(combineErrors(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE, VENDOR_ERROR_CODE))).when(mICarProperty)
                .setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testSetProperty_internalErrorAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(combineErrors(
                VehicleHalStatusCode.STATUS_INTERNAL_ERROR, VENDOR_ERROR_CODE))).when(mICarProperty)
                .setProperty(eq(carPropertyValue), any());

        CarInternalErrorException exception =  assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testSetProperty_notAvailableDisabledBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
    }

    @Test
    public void testSetProperty_notAvailableDisabledAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_DISABLED);
    }

    @Test
    public void testSetProperty_notAvailableDisabledAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(combineErrors(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED, VENDOR_ERROR_CODE)))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_DISABLED);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testSetProperty_notAvailableSafetyBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
    }

    @Test
    public void testSetProperty_notAvailableSafetyAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SAFETY);
    }

    @Test
    public void testSetProperty_notAvailableSafetyAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(combineErrors(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY, VENDOR_ERROR_CODE)))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SAFETY);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testSetProperty_notAvailableSpeedHighBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
    }

    @Test
    public void testSetProperty_notAvailableSpeedHighAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_HIGH);
    }

    @Test
    public void testSetProperty_notAvailableSpeedHighAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(combineErrors(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH, VENDOR_ERROR_CODE)))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_HIGH);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testSetProperty_notAvailableSpeedLowBeforeU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.TIRAMISU);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
    }

    @Test
    public void testSetProperty_notAvailableSpeedLowAfterU() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_LOW);
    }

    @Test
    public void testSetProperty_notAvailableSpeedLowAfterU_withVendorErrorCode() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        CarPropertyValue<Float> carPropertyValue = new CarPropertyValue<>(
                HVAC_TEMPERATURE_SET, 0, 17.0f);
        doThrow(new ServiceSpecificException(combineErrors(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW, VENDOR_ERROR_CODE)))
                .when(mICarProperty).setProperty(eq(carPropertyValue), any());

        PropertyNotAvailableException exception =  assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f));
        assertThat(exception.getDetailedErrorCode())
                .isEqualTo(PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_LOW);
        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testOnSetValueResult_onSuccess() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            AsyncPropertyServiceRequestList setPropertyRequest =
                    (AsyncPropertyServiceRequestList) args[0];
            List setPropertyServiceList = setPropertyRequest.getList();
            AsyncPropertyServiceRequest setPropertyServiceRequest =
                    (AsyncPropertyServiceRequest) setPropertyServiceList.get(0);
            IAsyncPropertyResultCallback setAsyncPropertyResultCallback =
                    (IAsyncPropertyResultCallback) args[1];

            assertThat(setPropertyServiceRequest.getRequestId()).isEqualTo(0);
            assertThat(setPropertyServiceRequest.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
            assertThat(setPropertyServiceRequest.getCarPropertyValue().getValue()).isEqualTo(
                    17.0f);

            GetSetValueResult setValueResult = GetSetValueResult.newSetValueResult(0,
                        /* updateTimestampNanos= */ TEST_TIMESTAMP);

            setAsyncPropertyResultCallback.onSetValueResults(
                    new GetSetValueResultList(List.of(setValueResult)));
            return null;
        }).when(mICarProperty).setPropertiesAsync(any(), any(), anyLong());


        mCarPropertyManager.setPropertiesAsync(List.of(createSetPropertyRequest()), null, null,
                mSetPropertyCallback);

        verify(mSetPropertyCallback, timeout(1000)).onSuccess(mSetPropertyResultCaptor.capture());
        SetPropertyResult gotResult = mSetPropertyResultCaptor.getValue();
        assertThat(gotResult.getRequestId()).isEqualTo(0);
        assertThat(gotResult.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotResult.getAreaId()).isEqualTo(0);
        assertThat(gotResult.getUpdateTimestampNanos()).isEqualTo(TEST_TIMESTAMP);
    }

    @Test
    public void testOnSetValueResult_onSuccessMultipleRequests() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            AsyncPropertyServiceRequestList setPropertyServiceRequest =
                    (AsyncPropertyServiceRequestList) args[0];
            List<AsyncPropertyServiceRequest> setPropertyServiceRequests =
                    setPropertyServiceRequest.getList();
            IAsyncPropertyResultCallback setAsyncPropertyResultCallback =
                    (IAsyncPropertyResultCallback) args[1];

            assertThat(setPropertyServiceRequests.size()).isEqualTo(2);
            assertThat(setPropertyServiceRequests.get(0).getRequestId()).isEqualTo(0);
            assertThat(setPropertyServiceRequests.get(0).getPropertyId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);
            assertThat(setPropertyServiceRequests.get(1).getRequestId()).isEqualTo(1);
            assertThat(setPropertyServiceRequests.get(1).getPropertyId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            List<GetSetValueResult> setValueResults = List.of(
                    GetSetValueResult.newSetValueResult(0,
                            /* updateTimestampNanos= */ TEST_TIMESTAMP),
                    GetSetValueResult.newSetValueResult(1,
                            /* updateTimestampNanos= */ TEST_TIMESTAMP));

            setAsyncPropertyResultCallback.onSetValueResults(
                    new GetSetValueResultList(setValueResults));
            return null;
        }).when(mICarProperty).setPropertiesAsync(any(), any(), anyLong());

        List<SetPropertyRequest<?>> setPropertyRequests = new ArrayList<>();
        setPropertyRequests.add(createSetPropertyRequest());
        setPropertyRequests.add(createSetPropertyRequest());

        mCarPropertyManager.setPropertiesAsync(setPropertyRequests, null, null,
                mSetPropertyCallback);

        verify(mSetPropertyCallback, timeout(1000).times(2)).onSuccess(
                mSetPropertyResultCaptor.capture());
        List<SetPropertyResult> gotPropertyResults = mSetPropertyResultCaptor.getAllValues();
        assertThat(gotPropertyResults.get(0).getRequestId()).isEqualTo(0);
        assertThat(gotPropertyResults.get(0).getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotPropertyResults.get(0).getAreaId()).isEqualTo(0);
        assertThat(gotPropertyResults.get(0).getUpdateTimestampNanos()).isEqualTo(TEST_TIMESTAMP);
        assertThat(gotPropertyResults.get(1).getRequestId()).isEqualTo(1);
        assertThat(gotPropertyResults.get(1).getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotPropertyResults.get(1).getAreaId()).isEqualTo(0);
        assertThat(gotPropertyResults.get(1).getUpdateTimestampNanos()).isEqualTo(TEST_TIMESTAMP);
    }

    @Test
    public void testOnSetValueResult_onFailure() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            AsyncPropertyServiceRequestList setPropertyService =
                    (AsyncPropertyServiceRequestList) args[0];
            List setPropertyServiceList = setPropertyService.getList();
            AsyncPropertyServiceRequest setPropertyServiceRequest =
                    (AsyncPropertyServiceRequest) setPropertyServiceList.get(0);
            IAsyncPropertyResultCallback setAsyncPropertyResultCallback =
                    (IAsyncPropertyResultCallback) args[1];

            assertThat(setPropertyServiceRequest.getRequestId()).isEqualTo(0);
            assertThat(setPropertyServiceRequest.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);

            GetSetValueResult setValueResult = GetSetValueResult.newErrorSetValueResult(0,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR, VENDOR_ERROR_CODE);

            setAsyncPropertyResultCallback.onSetValueResults(
                    new GetSetValueResultList(List.of(setValueResult)));
            return null;
        }).when(mICarProperty).setPropertiesAsync(any(), any(), anyLong());

        mCarPropertyManager.setPropertiesAsync(List.of(createSetPropertyRequest()), null, null,
                mSetPropertyCallback);

        verify(mSetPropertyCallback, timeout(1000)).onFailure(mPropertyAsyncErrorCaptor.capture());
        PropertyAsyncError error = mPropertyAsyncErrorCaptor.getValue();
        assertThat(error.getRequestId()).isEqualTo(0);
        assertThat(error.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(error.getAreaId()).isEqualTo(0);
        assertThat(error.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(error.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }

    @Test
    public void testRegisterCallback_returnsFalseIfPropertyIdNotSupportedInVehicle()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VehiclePropertyIds.INVALID, FIRST_UPDATE_RATE_HZ)).isFalse();
        verify(mICarProperty, never()).registerListener(anyInt(), anyFloat(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_registersWithServiceOnFirstCallback() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(FIRST_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_registersWithMaxUpdateRateOnFirstCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, MAX_UPDATE_RATE_HZ + 1)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(MAX_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_registersWithMinUpdateRateOnFirstCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, MIN_UPDATE_RATE_HZ - 1)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(MIN_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_registersWithOnChangeRateForOnChangeProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_registersWithOnChangeRateForStaticProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_STATIC_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_STATIC_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_returnsFalseForRemoteException() throws RemoteException {
        RemoteException remoteException = new RemoteException();
        doThrow(remoteException).when(mICarProperty).registerListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isFalse();
    }

    @Test
    public void testRegisterCallback_recoversAfterFirstRemoteException() throws RemoteException {
        RemoteException remoteException = new RemoteException();
        doThrow(remoteException).doNothing().when(mICarProperty).registerListener(
                eq(VENDOR_ON_CHANGE_PROPERTY), eq(CarPropertyManager.SENSOR_RATE_ONCHANGE),
                any(ICarPropertyEventListener.class));
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isFalse();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();
        verify(mICarProperty, times(2)).registerListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_returnsFalseForIllegalArgumentException()
            throws RemoteException {
        doThrow(IllegalArgumentException.class).when(mICarProperty).registerListener(
                eq(VENDOR_ON_CHANGE_PROPERTY), eq(CarPropertyManager.SENSOR_RATE_ONCHANGE),
                any(ICarPropertyEventListener.class));

        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isFalse();
    }

    @Test
    public void testRegisterCallback_SecurityException() throws RemoteException {
        doThrow(SecurityException.class).when(mICarProperty).registerListener(
                eq(VENDOR_ON_CHANGE_PROPERTY), eq(CarPropertyManager.SENSOR_RATE_ONCHANGE),
                any(ICarPropertyEventListener.class));

        assertThrows(SecurityException.class,
                () -> mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                        VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE));
    }

    @Test
    public void testRegisterCallback_registersTwiceWithHigherRateCallback() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY, LARGER_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty, times(2)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_CONTINUOUS_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                LARGER_UPDATE_RATE_HZ);
    }

    @Test
    public void testRegisterCallback_registersOnSecondLowerRateWithSameCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, SMALLER_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty, times(2)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_CONTINUOUS_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                SMALLER_UPDATE_RATE_HZ);
    }

    @Test
    public void testRegisterCallback_doesNotRegistersOnSecondLowerRateCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY, SMALLER_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(FIRST_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testRegisterCallback_registersTwiceForDifferentProperties() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();
        verify(mICarProperty, times(2)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_ON_CHANGE_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
    }

    @Test
    public void testUnregisterCallback_doesNothingIfNothingRegistered() throws RemoteException {
        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback, VENDOR_STATIC_PROPERTY);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_doesNothingIfPropertyIsNotRegisteredForCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback, VENDOR_STATIC_PROPERTY);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_doesNothingIfCallbackIsNotRegisteredForProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersCallbackForSingleProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY);
        verify(mICarProperty).unregisterListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersCallbackForSpecificProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY);
        verify(mICarProperty).unregisterListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersCallbackForBothProperties()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback);
        verify(mICarProperty, times(2)).unregisterListener(mPropertyIdCaptor.capture(),
                any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_ON_CHANGE_PROPERTY);
    }

    @Test
    public void testUnregisterCallback_unregistersAllCallbackForSingleProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
            VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
            VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback);
        verify(mICarProperty).unregisterListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersUpdatesRegisteredRateHz() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY, LARGER_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback2);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
        verify(mICarProperty, times(3)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_CONTINUOUS_PROPERTY, VENDOR_CONTINUOUS_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                LARGER_UPDATE_RATE_HZ, FIRST_UPDATE_RATE_HZ);
    }

    @Test
    public void testUnregisterCallback_doesNothingWithPropertyIdIfNothingRegistered()
            throws RemoteException {
        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_returnsVoidForIllegalArgumentException()
            throws RemoteException {
        doThrow(IllegalArgumentException.class).when(mICarProperty).unregisterListener(
                eq(VENDOR_ON_CHANGE_PROPERTY), any(ICarPropertyEventListener.class));

        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();
        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY);

        verify(mICarProperty).unregisterListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_SecurityException() throws RemoteException {
        doThrow(SecurityException.class).when(mICarProperty).unregisterListener(
                eq(VENDOR_ON_CHANGE_PROPERTY), any(ICarPropertyEventListener.class));

        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();
        assertThrows(SecurityException.class,
                () -> mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback,
                        VENDOR_ON_CHANGE_PROPERTY));
    }

    @Test
    public void testOnErrorEvent_callbackIsCalledWithErrorEvent() throws RemoteException {
        List<CarPropertyEvent> eventList = createErrorCarPropertyEventList();
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(
                new CarPropertyConfigList(configs));
        ICarPropertyEventListener listener = getCarPropertyEventListener();

        listener.onEvent(eventList);

        // Wait until we get the on error event for the initial value.
        verify(mCarPropertyEventCallback, timeout(5000)).onErrorEvent(HVAC_TEMPERATURE_SET, 0,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
    }

    @Test
    public void testOnChangeEvent_callbackIsCalledWithEvent() throws RemoteException {
        List<CarPropertyEvent> eventList = createCarPropertyEventList();
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(
                new CarPropertyConfigList(configs));
        ICarPropertyEventListener listener = getCarPropertyEventListener();
        ArgumentCaptor<CarPropertyValue> value = ArgumentCaptor.forClass(CarPropertyValue.class);

        listener.onEvent(eventList);

        // Wait until we get the on property change event for the initial value.
        verify(mCarPropertyEventCallback, timeout(5000)).onChangeEvent(value.capture());
        assertThat(value.getValue().getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(value.getValue().getValue()).isEqualTo(17.0f);
    }

    private ICarPropertyEventListener getCarPropertyEventListener() throws RemoteException {
        ArgumentCaptor<ICarPropertyEventListener> carPropertyEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(ICarPropertyEventListener.class);
        mCarPropertyManager.registerCallback(mCarPropertyEventCallback, HVAC_TEMPERATURE_SET,
                SENSOR_RATE_ONCHANGE);

        verify(mICarProperty).registerListener(eq(HVAC_TEMPERATURE_SET), eq(SENSOR_RATE_ONCHANGE),
                carPropertyEventListenerArgumentCaptor.capture());

        return carPropertyEventListenerArgumentCaptor.getValue();
    }

    @Test
    public void testGetPropertyList() throws Exception {
        List<CarPropertyConfig> expectedConfigs = mock(List.class);
        when(mICarProperty.getPropertyList())
                .thenReturn(new CarPropertyConfigList(expectedConfigs));


        assertThat(mCarPropertyManager.getPropertyList()).isEqualTo(expectedConfigs);
    }

    @Test
    public void testGetPropertyList_withPropertyIds() throws Exception {
        Integer[] requestedPropertyIds = new Integer[] {
                VENDOR_CONTINUOUS_PROPERTY, HVAC_TEMPERATURE_SET};
        List<CarPropertyConfig> expectedConfigs = mock(List.class);
        ArgumentCaptor<int[]> argumentCaptor = ArgumentCaptor.forClass(int[].class);
        when(mICarProperty.getPropertyConfigList(argumentCaptor.capture()))
                .thenReturn(new CarPropertyConfigList(expectedConfigs));

        assertThat(mCarPropertyManager.getPropertyList(new ArraySet<Integer>(requestedPropertyIds)))
                .isEqualTo(expectedConfigs);
        assertThat(argumentCaptor.getValue()).asList()
                .containsExactlyElementsIn(requestedPropertyIds);
    }

    @Test
    public void testGetPropertyList_filterUnsupportedPropertyIds() throws Exception {
        Integer[] requestedPropertyIds = new Integer[]{
                0, 1, VENDOR_CONTINUOUS_PROPERTY, HVAC_TEMPERATURE_SET};
        Integer[] filteredPropertyIds = new Integer[]{
                VENDOR_CONTINUOUS_PROPERTY, HVAC_TEMPERATURE_SET};
        List<CarPropertyConfig> expectedConfigs = mock(List.class);
        ArgumentCaptor<int[]> argumentCaptor = ArgumentCaptor.forClass(int[].class);
        when(mICarProperty.getPropertyConfigList(argumentCaptor.capture()))
                .thenReturn(new CarPropertyConfigList(expectedConfigs));

        assertThat(mCarPropertyManager.getPropertyList(new ArraySet<Integer>(requestedPropertyIds)))
                .isEqualTo(expectedConfigs);
        assertThat(argumentCaptor.getValue()).asList()
                .containsExactlyElementsIn(filteredPropertyIds);
    }

    @Test
    public void testGetCarPropertyConfig() throws Exception {
        CarPropertyConfig mockConfig = mock(CarPropertyConfig.class);
        List<CarPropertyConfig> expectedConfigs = List.of(mockConfig);
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET}))
                .thenReturn(new CarPropertyConfigList(expectedConfigs));

        assertThat(mCarPropertyManager.getCarPropertyConfig(HVAC_TEMPERATURE_SET))
                .isEqualTo(mockConfig);
    }

    @Test
    public void testGetCarPropertyConfig_noConfigReturned() throws Exception {
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET}))
                .thenReturn(new CarPropertyConfigList(
                        new ArrayList<CarPropertyConfig>()));

        assertThat(mCarPropertyManager.getCarPropertyConfig(HVAC_TEMPERATURE_SET)).isNull();
    }

    @Test
    public void testGetCarPropertyConfig_unsupported() throws Exception {
        assertThat(mCarPropertyManager.getCarPropertyConfig(/* propId= */ 0)).isNull();
    }

    @Test
    public void testIsPropertyAvailable() throws Exception {
        CarPropertyValue<Integer> expectedValue = new CarPropertyValue<>(HVAC_TEMPERATURE_SET,
                /* areaId= */ 0, CarPropertyValue.STATUS_AVAILABLE, /* timestamp= */ 0,
                /* value= */ 1);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(expectedValue);

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isTrue();
    }

    @Test
    public void testIsPropertyAvailable_syncOpTryAgain() throws Exception {
        CarPropertyValue<Integer> expectedValue = new CarPropertyValue<>(HVAC_TEMPERATURE_SET,
                /* areaId= */ 0, CarPropertyValue.STATUS_AVAILABLE, /* timestamp= */ 0,
                /* value= */ 1);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(SYNC_OP_LIMIT_TRY_AGAIN)).thenReturn(expectedValue);

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isTrue();
        verify(mICarProperty, times(2)).getProperty(HVAC_TEMPERATURE_SET, 0);
    }

    @Test
    public void testIsPropertyAvailable_notAvailable() throws Exception {
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE));

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isFalse();
    }

    @Test
    public void testIsPropertyAvailable_tryAgain() throws Exception {
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isFalse();
    }


    @Test
    public void testIsPropertyAvailable_RemoteException() throws Exception {
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new RemoteException());

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isFalse();
    }

    @Test
    public void testIsPropertyAvailable_unsupported() throws Exception {
        assertThat(mCarPropertyManager.isPropertyAvailable(/* propId= */ 0, /* areaId= */ 0))
                .isFalse();
    }

    private static int combineErrors(int systemError, int vendorError) {
        return vendorError << VENDOR_ERROR_CODE_SHIFT | systemError;
    }
}
