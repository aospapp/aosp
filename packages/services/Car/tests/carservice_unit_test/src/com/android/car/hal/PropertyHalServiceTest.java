/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET;
import static android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_INTERNAL_ERROR;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE;

import static com.android.car.internal.property.CarPropertyHelper.STATUS_OK;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import com.android.car.VehicleStub;
import com.android.car.VehicleStub.AsyncGetSetRequest;
import com.android.car.VehicleStub.GetVehicleStubAsyncResult;
import com.android.car.VehicleStub.SetVehicleStubAsyncResult;
import com.android.car.VehicleStub.VehicleStubCallbackInterface;
import com.android.car.hal.test.AidlVehiclePropValueBuilder;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.GetSetValueResult;
import com.android.car.internal.property.GetSetValueResultList;
import com.android.car.internal.property.IAsyncPropertyResultCallback;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class PropertyHalServiceTest {
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private VehicleHal mVehicleHal;

    @Mock
    private IAsyncPropertyResultCallback mGetAsyncPropertyResultCallback;
    @Mock
    private IAsyncPropertyResultCallback mSetAsyncPropertyResultCallback;
    @Mock
    private IBinder mGetAsyncPropertyResultBinder;
    @Mock
    private IBinder mSetAsyncPropertyResultBinder;
    @Mock
    private PropertyHalService.PropertyHalListener mPropertyHalListener;
    @Captor
    private ArgumentCaptor<GetSetValueResultList> mAsyncResultCaptor;
    @Captor
    private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor;

    private PropertyHalService mPropertyHalService;
    private static final int REQUEST_ID_1 = 1;
    private static final int REQUEST_ID_2 = 2;
    private static final int REQUEST_ID_3 = 3;
    private static final int RECEIVED_REQUEST_ID_1 = 0;
    private static final int RECEIVED_REQUEST_ID_2 = 1;
    private static final int RECEIVED_REQUEST_ID_3 = 2;
    private static final int INT32_PROP = VehiclePropertyIds.INFO_FUEL_DOOR_LOCATION;
    private static final int VENDOR_ERROR_CODE = 1234;
    private static final AsyncPropertyServiceRequest GET_PROPERTY_SERVICE_REQUEST_1 =
            new AsyncPropertyServiceRequest(REQUEST_ID_1, HVAC_TEMPERATURE_SET, /* areaId= */ 0);
    private static final AsyncPropertyServiceRequest GET_PROPERTY_SERVICE_REQUEST_2 =
            new AsyncPropertyServiceRequest(REQUEST_ID_2, HVAC_TEMPERATURE_SET, /* areaId= */ 0);
    private static final AsyncPropertyServiceRequest SET_PROPERTY_SERVICE_REQUEST =
            new AsyncPropertyServiceRequest(REQUEST_ID_1, HVAC_TEMPERATURE_SET, /* areaId= */ 0,
            new CarPropertyValue(HVAC_TEMPERATURE_SET, /* areaId= */ 0, 17.0f));
    private static final AsyncPropertyServiceRequest SET_PROPERTY_SERVICE_REQUEST_2 =
            new AsyncPropertyServiceRequest(REQUEST_ID_2, PERF_VEHICLE_SPEED, /* areaId= */ 0,
            new CarPropertyValue(PERF_VEHICLE_SPEED, /* areaId= */ 0, 17.0f));
    private static final AsyncPropertyServiceRequest SET_PROPERTY_SERVICE_REQUEST_3 =
            new AsyncPropertyServiceRequest(REQUEST_ID_3, PERF_VEHICLE_SPEED, /* areaId= */ 0,
            new CarPropertyValue(PERF_VEHICLE_SPEED, /* areaId= */ 0, 17.0f));

    private static final long TEST_UPDATE_TIMESTAMP_NANOS = 1234;
    private final HalPropValueBuilder mPropValueBuilder = new HalPropValueBuilder(
            /* isAidl= */ true);
    private final HalPropValue mPropValue = mPropValueBuilder.build(
            HVAC_TEMPERATURE_SET, /* areaId= */ 0, TEST_UPDATE_TIMESTAMP_NANOS, /* status= */ 0,
            17.0f);
    private final HalPropValue mNonTargetPropValue = mPropValueBuilder.build(
            HVAC_TEMPERATURE_SET, /* areaId= */ 0, TEST_UPDATE_TIMESTAMP_NANOS, /* status= */ 0,
            16.0f);
    private final HalPropValue mPropValue2 = mPropValueBuilder.build(
            PERF_VEHICLE_SPEED, /* areaId= */ 0, TEST_UPDATE_TIMESTAMP_NANOS, /* status= */ 0,
            17.0f);

    private AsyncPropertyServiceRequest copyRequest(AsyncPropertyServiceRequest request) {
        return new AsyncPropertyServiceRequest(request.getRequestId(), request.getPropertyId(),
                request.getAreaId(), request.getCarPropertyValue());
    }

    @Before
    public void setUp() {
        when(mVehicleHal.getHalPropValueBuilder()).thenReturn(mPropValueBuilder);
        mPropertyHalService = new PropertyHalService(mVehicleHal);
        mPropertyHalService.init();

        HalPropConfig mockPropConfig1 = mock(HalPropConfig.class);
        when(mockPropConfig1.getPropId()).thenReturn(VehicleProperty.HVAC_TEMPERATURE_SET);
        when(mockPropConfig1.getChangeMode()).thenReturn(VehiclePropertyChangeMode.ON_CHANGE);
        HalPropConfig mockPropConfig2 = mock(HalPropConfig.class);
        when(mockPropConfig2.getPropId()).thenReturn(VehicleProperty.PERF_VEHICLE_SPEED);
        when(mockPropConfig2.getChangeMode()).thenReturn(VehiclePropertyChangeMode.CONTINUOUS);
        when(mockPropConfig2.getMinSampleRate()).thenReturn(20.0f);
        when(mockPropConfig2.getMaxSampleRate()).thenReturn(100.0f);
        mPropertyHalService.takeProperties(ImmutableList.of(mockPropConfig1, mockPropConfig2));
    }

    @After
    public void tearDown() {
        mPropertyHalService.release();
        mPropertyHalService = null;
    }

    private void verifyNoPendingRequest() {
        assertWithMessage("No pending async requests after test finish")
                .that(mPropertyHalService.countPendingAsyncRequests()).isEqualTo(0);
        assertWithMessage("No pending async set request waiting for update after test finish")
                .that(mPropertyHalService.countHalPropIdToWaitForUpdateRequests()).isEqualTo(0);
    }

    private Object deliverResult(InvocationOnMock invocation, Integer expectedServiceRequestId,
            int errorCode, HalPropValue propValue, boolean get) {
        return deliverResult(invocation, expectedServiceRequestId, errorCode,
                /* vendorErrorCode= */ 0, propValue, get);
    }

    private Object deliverResult(InvocationOnMock invocation, Integer expectedServiceRequestId,
            int errorCode, int vendorErrorCode, HalPropValue propValue, boolean get) {
        Object[] args = invocation.getArguments();
        List getVehicleHalRequests = (List) args[0];
        Map<VehicleStubCallbackInterface, List<GetVehicleStubAsyncResult>> callbackToGetResults =
                new HashMap<>();
        Map<VehicleStubCallbackInterface, List<SetVehicleStubAsyncResult>> callbackToSetResults =
                new HashMap<>();
        for (int i = 0; i < getVehicleHalRequests.size(); i++) {
            AsyncGetSetRequest getVehicleHalRequest = (AsyncGetSetRequest)
                    getVehicleHalRequests.get(i);
            VehicleStubCallbackInterface callback = (VehicleStubCallbackInterface) args[1];
            if (callbackToGetResults.get(callback) == null) {
                callbackToGetResults.put(callback, new ArrayList<>());
            }
            if (callbackToSetResults.get(callback) == null) {
                callbackToSetResults.put(callback, new ArrayList<>());
            }

            int serviceRequestId = getVehicleHalRequest.getServiceRequestId();
            if (expectedServiceRequestId != null) {
                assertThat(serviceRequestId).isEqualTo(expectedServiceRequestId);
            }

            if (get) {
                if (propValue != null) {
                    callbackToGetResults.get(callback).add(new GetVehicleStubAsyncResult(
                            serviceRequestId, propValue));
                } else {
                    callbackToGetResults.get(callback).add(new GetVehicleStubAsyncResult(
                            serviceRequestId, errorCode, vendorErrorCode));
                }
            } else {
                callbackToSetResults.get(callback).add(new SetVehicleStubAsyncResult(
                        serviceRequestId, errorCode, vendorErrorCode));
            }
        }

        for (VehicleStubCallbackInterface callback : callbackToGetResults.keySet()) {
            callback.onGetAsyncResults(callbackToGetResults.get(callback));
        }
        for (VehicleStubCallbackInterface callback : callbackToSetResults.keySet()) {
            callback.onSetAsyncResults(callbackToSetResults.get(callback));
        }

        return null;
    }

    private Object deliverOkayGetResult(InvocationOnMock invocation) {
        return deliverOkayGetResult(invocation, mPropValue);
    }

    private Object deliverOkayGetResult(InvocationOnMock invocation, HalPropValue propValue) {
        return deliverResult(invocation, /* expectedServiceRequestId= */ null,
                STATUS_OK, propValue, true);
    }

    private Object deliverOkayGetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, STATUS_OK,
                mPropValue, true);
    }

    private Object deliverOkaySetResult(InvocationOnMock invocation) {
        return deliverOkaySetResult(invocation, /* expectedServiceRequestId= */ null);
    }

    private Object deliverOkaySetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, STATUS_OK,
                null, false);
    }

    private Object deliverTryAgainGetResult(InvocationOnMock invocation) {
        return deliverTryAgainGetResult(invocation, /* expectedServiceRequestId= */ null);
    }

    private Object deliverTryAgainGetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, VehicleStub.STATUS_TRY_AGAIN,
                null, true);
    }

    private Object deliverTryAgainSetResult(InvocationOnMock invocation) {
        return deliverTryAgainSetResult(invocation, /* expectedServiceRequestId= */ null);
    }

    private Object deliverTryAgainSetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, VehicleStub.STATUS_TRY_AGAIN,
                null, false);
    }

    private Object deliverErrorGetResult(InvocationOnMock invocation, int errorCode) {
        return deliverErrorGetResult(invocation, /* expectedServiceRequestId= */ null, errorCode);
    }

    private Object deliverErrorGetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId, int errorCode) {
        return deliverResult(invocation, expectedServiceRequestId, errorCode, null, true);
    }

    private Object deliverErrorSetResult(InvocationOnMock invocation, int errorCode) {
        return deliverErrorSetResult(invocation, /* expectedServiceRequestId= */ null, errorCode);
    }

    private Object deliverErrorSetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId, int errorCode) {
        return deliverResult(invocation, expectedServiceRequestId, errorCode, null, false);
    }

    @Test
    public void testGetCarPropertyValuesAsync() {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<AsyncGetSetRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).getAsync(captor.capture(),
                any(VehicleStubCallbackInterface.class));
        AsyncGetSetRequest gotRequest = captor.getValue().get(0);
        assertThat(gotRequest.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest.getHalPropValue().getPropId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotRequest.getTimeoutUptimeMs()).isGreaterThan(1000);

        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1});
        verify(mVehicleHal, timeout(1000)).cancelRequests(List.of(0));

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_multipleRequests() {
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<AsyncGetSetRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).getAsync(captor.capture(),
                any(VehicleStubCallbackInterface.class));
        AsyncGetSetRequest gotRequest0 = captor.getValue().get(0);
        assertThat(gotRequest0.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest0.getHalPropValue().getPropId()).isEqualTo(
                HVAC_TEMPERATURE_SET);
        assertThat(gotRequest0.getHalPropValue().getAreaId()).isEqualTo(0);
        assertThat(gotRequest0.getTimeoutUptimeMs()).isGreaterThan(1000);
        AsyncGetSetRequest gotRequest1 = captor.getValue().get(1);
        assertThat(gotRequest1.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_2);
        assertThat(gotRequest1.getHalPropValue().getPropId()).isEqualTo(
                HVAC_TEMPERATURE_SET);
        assertThat(gotRequest1.getHalPropValue().getAreaId()).isEqualTo(0);
        assertThat(gotRequest1.getTimeoutUptimeMs()).isGreaterThan(1000);

        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1, REQUEST_ID_2});

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_linkToDeath() throws RemoteException {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);
        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        verify(mGetAsyncPropertyResultBinder).linkToDeath(any(IBinder.DeathRecipient.class),
                anyInt());

        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1});

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_binderDiedBeforeLinkToDeath() throws RemoteException {
        IBinder mockBinder = mock(IBinder.class);
        when(mGetAsyncPropertyResultCallback.asBinder()).thenReturn(mockBinder);
        doThrow(new RemoteException()).when(mockBinder).linkToDeath(any(), anyInt());
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);

        assertThrows(IllegalStateException.class, () -> {
            mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                    mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        });

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_unlinkToDeath_onBinderDied() throws RemoteException {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);
        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<IBinder.DeathRecipient> recipientCaptor = ArgumentCaptor.forClass(
                IBinder.DeathRecipient.class);
        verify(mGetAsyncPropertyResultBinder).linkToDeath(recipientCaptor.capture(), eq(0));

        recipientCaptor.getValue().binderDied();

        verify(mGetAsyncPropertyResultBinder).unlinkToDeath(recipientCaptor.getValue(), 0);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_normalResult() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverOkayGetResult(invocation, RECEIVED_REQUEST_ID_1);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);


        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(result.getCarPropertyValue().getAreaId()).isEqualTo(0);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_retryTwiceAndSucceed() throws RemoteException {
        doAnswer(new Answer() {
            private int mCount = 0;

            public Object answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                List<AsyncGetSetRequest> getVehicleHalRequests = (List<AsyncGetSetRequest>) args[0];
                assertThat(getVehicleHalRequests.size()).isEqualTo(1);

                switch (mCount++) {
                    case 0:
                        return deliverTryAgainGetResult(invocation, RECEIVED_REQUEST_ID_1);
                    case 1:
                        return deliverTryAgainGetResult(invocation, RECEIVED_REQUEST_ID_2);
                    case 2:
                        return deliverOkayGetResult(invocation, RECEIVED_REQUEST_ID_3);
                    default:
                        throw new IllegalStateException("Only expect 3 calls");
                }
            }
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(result.getCarPropertyValue().getAreaId()).isEqualTo(0);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_retryAndTimeout() throws RemoteException {
        doAnswer((invocation) -> {
            // For every request, we return retry result.
            return deliverTryAgainGetResult(invocation);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 10);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_noResultTimeout() throws RemoteException {
        doNothing().when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStubCallbackInterface.class));
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 10);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_timeoutFromVehicleStub() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List getVehicleHalRequests = (List) args[0];
            AsyncGetSetRequest getVehicleHalRequest =
                    (AsyncGetSetRequest) getVehicleHalRequests.get(0);
            VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStubCallbackInterface) args[1];
            // Simulate the request has already timed-out.
            getVehicleStubAsyncCallback.onRequestsTimeout(List.of(
                    getVehicleHalRequest.getServiceRequestId()));
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_errorResult() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverErrorGetResult(invocation, RECEIVED_REQUEST_ID_1,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(result.getCarPropertyValue()).isEqualTo(null);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_errorResultVendorErrorCode() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverResult(invocation, RECEIVED_REQUEST_ID_1,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR,  VENDOR_ERROR_CODE,
                    /* propValue= */ null, /* get= */ true);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(result.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
        assertThat(result.getCarPropertyValue()).isEqualTo(null);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_propStatusUnavailable() throws RemoteException {
        doAnswer((invocation) -> {
            HalPropValue propValue = mPropValueBuilder.build(
                    HVAC_TEMPERATURE_SET, /* areaId= */ 0, TEST_UPDATE_TIMESTAMP_NANOS,
                    VehiclePropertyStatus.UNAVAILABLE, 17.0f);
            return deliverOkayGetResult(invocation, propValue);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        assertThat(result.getVendorErrorCode()).isEqualTo(0);
        assertThat(result.getCarPropertyValue()).isEqualTo(null);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_propStatusError() throws RemoteException {
        doAnswer((invocation) -> {
            HalPropValue propValue = mPropValueBuilder.build(
                    HVAC_TEMPERATURE_SET, /* areaId= */ 0, TEST_UPDATE_TIMESTAMP_NANOS,
                    VehiclePropertyStatus.ERROR, 17.0f);
            return deliverOkayGetResult(invocation, propValue);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(result.getVendorErrorCode()).isEqualTo(0);
        assertThat(result.getCarPropertyValue()).isEqualTo(null);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_multipleResultsSameCall() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List<AsyncGetSetRequest> getVehicleHalRequests = (List) args[0];
            VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStubCallbackInterface) args[1];

            assertThat(getVehicleHalRequests.size()).isEqualTo(2);
            assertThat(getVehicleHalRequests.get(0).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_1);
            assertThat(getVehicleHalRequests.get(0).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);
            assertThat(getVehicleHalRequests.get(1).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_2);
            assertThat(getVehicleHalRequests.get(1).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            List<GetVehicleStubAsyncResult> getVehicleStubAsyncResults =
                    new ArrayList<>();
            getVehicleStubAsyncResults.add(
                    new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_1, mPropValue));
            getVehicleStubAsyncResults.add(
                    new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_2, mPropValue));
            getVehicleStubAsyncCallback.onGetAsyncResults(getVehicleStubAsyncResults);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result1 = mAsyncResultCaptor.getValue().getList().get(0);
        GetSetValueResult result2 = mAsyncResultCaptor.getValue().getList().get(1);
        assertThat(result1.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result1.getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(result2.getRequestId()).isEqualTo(REQUEST_ID_2);
        assertThat(result2.getCarPropertyValue().getValue()).isEqualTo(17.0f);

        verifyNoPendingRequest();
    }

    @Test
    public void testGetCarPropertyValuesAsync_multipleResultsDifferentCalls()
            throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List<AsyncGetSetRequest> getVehicleHalRequests = (List) args[0];
            VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStubCallbackInterface) args[1];

            assertThat(getVehicleHalRequests.size()).isEqualTo(2);
            assertThat(getVehicleHalRequests.get(0).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_1);
            assertThat(getVehicleHalRequests.get(0).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);
            assertThat(getVehicleHalRequests.get(1).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_2);
            assertThat(getVehicleHalRequests.get(1).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            getVehicleStubAsyncCallback.onGetAsyncResults(
                    List.of(new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_1,
                            mPropValue)));
            getVehicleStubAsyncCallback.onGetAsyncResults(
                    List.of(new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_2,
                            mPropValue)));
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000).times(2))
                .onGetValueResults(mAsyncResultCaptor.capture());
        List<GetSetValueResultList> getValuesResults = mAsyncResultCaptor.getAllValues();
        assertThat(getValuesResults.get(0).getList().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(getValuesResults.get(0).getList().get(0).getCarPropertyValue().getValue())
                .isEqualTo(17.0f);
        assertThat(getValuesResults.get(1).getList().get(0).getRequestId()).isEqualTo(REQUEST_ID_2);
        assertThat(getValuesResults.get(1).getList().get(0).getCarPropertyValue().getValue())
                .isEqualTo(17.0f);

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync() {
        Set<Integer> vhalCancelledRequestIds = new ArraySet<>();
        doAnswer((invocation) -> {
            List<Integer> ids = (List<Integer>) invocation.getArgument(0);
            for (int i = 0; i < ids.size(); i++) {
                vhalCancelledRequestIds.add(ids.get(i));
            }
            return null;
        }).when(mVehicleHal).cancelRequests(any());
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<AsyncGetSetRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).setAsync(captor.capture(), any(VehicleStubCallbackInterface.class));
        AsyncGetSetRequest gotRequest = captor.getValue().get(0);
        assertThat(gotRequest.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest.getHalPropValue().getPropId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotRequest.getHalPropValue().getFloatValue(0)).isEqualTo(17.0f);
        assertThat(gotRequest.getTimeoutUptimeMs()).isGreaterThan(1000);

        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1});

        // Because we cancel the onging async set property request, the ongoing get initial value
        // request should be cancelled as well.
        verify(mVehicleHal, timeout(1000).times(2)).cancelRequests(any());
        assertThat(vhalCancelledRequestIds).containsExactlyElementsIn(new Integer[]{0, 1});

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_configNotFound() {
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        AsyncPropertyServiceRequest request = new AsyncPropertyServiceRequest(
                REQUEST_ID_1, /* propId= */ 1, /* areaId= */ 0,
                new CarPropertyValue(/* propId= */ 1, /* areaId= */ 0, 17.0f));

        assertThrows(IllegalArgumentException.class, () -> {
            mPropertyHalService.setCarPropertyValuesAsync(List.of(request),
                    mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        });

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_linkToDeath() throws RemoteException {
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        List<AsyncPropertyServiceRequest> setPropertyServiceRequests = mock(List.class);

        mPropertyHalService.setCarPropertyValuesAsync(setPropertyServiceRequests,
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mSetAsyncPropertyResultBinder).linkToDeath(any(IBinder.DeathRecipient.class),
                anyInt());

        verifyNoPendingRequest();
    }

    // Test the case where we don't wait for property update event. The success callback should be
    // immediately called when the set request succeeded.
    @Test
    public void testSetCarPropertyValuesAsync_noWaitForPropertyUpdate()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        AsyncPropertyServiceRequest request = copyRequest(SET_PROPERTY_SERVICE_REQUEST);
        request.setWaitForPropertyUpdate(false);

        mPropertyHalService.setCarPropertyValuesAsync(List.of(request),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        // Must not subscribe to the property for update events.
        verify(mVehicleHal, never()).subscribeProperty(any(), anyInt(), anyFloat());
        // Must not send get initial value request.
        verify(mVehicleHal, never()).getAsync(any(), any());
        assertThat(setInvocationWrap).hasSize(1);

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(STATUS_OK);
        // This should be the time when the request is successfully sent.
        assertThat(result.getUpdateTimestampNanos()).isGreaterThan(0);

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_mixWaitNoWaitForPropertyUpdate() throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();
        List<HalServiceBase> serviceWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            serviceWrap.add(invocation.getArgument(0));
            return null;
        }).when(mVehicleHal).subscribeProperty(any(), anyInt(), anyFloat());
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        AsyncPropertyServiceRequest request1 = copyRequest(SET_PROPERTY_SERVICE_REQUEST);
        AsyncPropertyServiceRequest request2 = copyRequest(SET_PROPERTY_SERVICE_REQUEST_2);
        request2.setWaitForPropertyUpdate(false);

        mPropertyHalService.setCarPropertyValuesAsync(List.of(request1, request2),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the get initial value result, assume we failed to get initial values.
        deliverErrorGetResult(getInvocationWrap.get(0),
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);

        verify(mSetAsyncPropertyResultCallback, never()).onSetValueResults(any());

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        // We should receive the success callback for request 2 since the set request is already
        // sent.
        verify(mSetAsyncPropertyResultCallback).onSetValueResults(
                mAsyncResultCaptor.capture());

        // Deliver updated value for request 1. Request 2 don't need an updated value.
        assertThat(serviceWrap).hasSize(1);
        serviceWrap.get(0).onHalEvents(List.of(mPropValue));

        // Get init value result must not be passed to the client.
        verify(mSetAsyncPropertyResultCallback, never()).onGetValueResults(any());
        // We should receive the success callback for request 1.
        verify(mSetAsyncPropertyResultCallback, times(2)).onSetValueResults(
                mAsyncResultCaptor.capture());
        for (GetSetValueResultList results: mAsyncResultCaptor.getAllValues()) {
            GetSetValueResult result = results.getList().get(0);
            assertThat(result.getErrorCode()).isEqualTo(STATUS_OK);
            if (result.getRequestId() == REQUEST_ID_1) {
                assertThat(result.getUpdateTimestampNanos()).isEqualTo(
                        TEST_UPDATE_TIMESTAMP_NANOS);
            } else {
                assertThat(result.getUpdateTimestampNanos()).isNotEqualTo(
                        TEST_UPDATE_TIMESTAMP_NANOS);
                // This should be elapsedRealTimeNano when the set is done.
                assertThat(result.getUpdateTimestampNanos()).isGreaterThan(0);
            }
        }
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    // Test the case where we get the result for the init value before we get the result for
    // async set. The init value is the same as the target value.
    @Test
    public void testSetCarPropertyValuesAsync_initValueSameAsTargetValue_beforeSetResult()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the get initial value result.
        deliverOkayGetResult(getInvocationWrap.get(0));

        verify(mSetAsyncPropertyResultCallback, never()).onSetValueResults(any());

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        // Get init value result must not be passed to the client.
        verify(mSetAsyncPropertyResultCallback, never()).onGetValueResults(any());
        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(STATUS_OK);
        assertThat(result.getUpdateTimestampNanos()).isEqualTo(TEST_UPDATE_TIMESTAMP_NANOS);
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    // Test the case where we get the result for the init value after we get the result for
    // async set. The init value is the same as the target value.
    @Test
    public void testSetCarPropertyValuesAsync_initValueSameAsTargetValue_afterSetResult()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        verify(mSetAsyncPropertyResultCallback, never()).onSetValueResults(any());

        // Returns the get initial value result.
        deliverOkayGetResult(getInvocationWrap.get(0));

        // Get init value result must not be passed to the client.
        verify(mSetAsyncPropertyResultCallback, never()).onGetValueResults(any());
        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(STATUS_OK);
        assertThat(result.getUpdateTimestampNanos()).isEqualTo(TEST_UPDATE_TIMESTAMP_NANOS);
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    // Test the case where the get initial value and set value request retried once.
    // The init value is the same as the target value.
    @Test
    public void testSetCarPropertyValuesAsync_initValueSameAsTargetValue_retry()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Retry get initial value request.
        deliverTryAgainGetResult(getInvocationWrap.get(0));
        // Retry set initial value request.
        deliverTryAgainSetResult(setInvocationWrap.get(0));

        // Wait until the retry happens.
        verify(mVehicleHal, timeout(1000).times(2)).getAsync(any(), any());
        verify(mVehicleHal, timeout(1000).times(2)).setAsync(any(), any());

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(1));
        // Returns the get initial value result.
        deliverOkayGetResult(getInvocationWrap.get(1));

        verify(mSetAsyncPropertyResultCallback, never()).onGetValueResults(any());
        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(STATUS_OK);
        assertThat(result.getUpdateTimestampNanos()).isEqualTo(TEST_UPDATE_TIMESTAMP_NANOS);
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    // Test the case where the get initial value returns a different value than target value.
    @Test
    public void testSetCarPropertyValuesAsync_initValueDiffTargetValue_timeout()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 100);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the get initial value result.
        deliverOkayGetResult(getInvocationWrap.get(0), mPropValueBuilder.build(
                HVAC_TEMPERATURE_SET, /* areaId= */ 0, 16.0f));
        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        // Get init value result must not be passed to the client.
        verify(mSetAsyncPropertyResultCallback, never()).onGetValueResults(any());
        // Eventually the request should time out.
        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);

        verifyNoPendingRequest();
    }

    // Test the case where we get set value error result after we get the initial value result.
    @Test
    public void testSetCarPropertyValuesAsync_errorSetResultAfterTargetInitValueResult()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the get initial value result.
        deliverOkayGetResult(getInvocationWrap.get(0));
        // Returns the set value result.
        deliverErrorSetResult(setInvocationWrap.get(0),
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);

        // Get init value result must not be passed to the client.
        verify(mSetAsyncPropertyResultCallback, never()).onGetValueResults(any());
        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    // Test the callback is only invoked once even though the same result is returned multiple
    // times.
    @Test
    public void testSetCarPropertyValuesAsync_callbackOnlyCalledOncePerRequest()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        deliverErrorSetResult(setInvocationWrap.get(0),
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);

        // The same result is returned again.
        deliverErrorSetResult(setInvocationWrap.get(0),
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);

        // We must only call callback once.
        verify(mSetAsyncPropertyResultCallback, times(1)).onSetValueResults(any());
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    // Test the case where the get init value request has error result. The result must be ignored.
    @Test
    public void testSetCarPropertyValuesAsync_errorInitValueResult_timeout()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 100);

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the get initial value result, assume we failed to get initial values.
        deliverErrorGetResult(getInvocationWrap.get(0),
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        // Get init value result must not be passed to the client.
        verify(mSetAsyncPropertyResultCallback, never()).onGetValueResults(any());
        // Eventually the request should time out.
        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_propertyUpdatedThroughEvent()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();
        List<HalServiceBase> serviceWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            serviceWrap.add(invocation.getArgument(0));
            return null;
        }).when(mVehicleHal).subscribeProperty(any(), anyInt(), anyFloat());
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        AsyncPropertyServiceRequest request2 = copyRequest(SET_PROPERTY_SERVICE_REQUEST_2);
        request2.setUpdateRateHz(20.0f);

        mPropertyHalService.setCarPropertyValuesAsync(List.of(
                SET_PROPERTY_SERVICE_REQUEST, request2),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the get initial value result.
        deliverErrorGetResult(getInvocationWrap.get(0),
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));

        // Notify the property is updated to the target value.
        assertThat(serviceWrap).hasSize(2);
        serviceWrap.get(0).onHalEvents(List.of(mPropValue));
        serviceWrap.get(1).onHalEvents(List.of(mPropValue2));

        verify(mSetAsyncPropertyResultCallback, times(2)).onSetValueResults(
                mAsyncResultCaptor.capture());
        for (GetSetValueResultList results : mAsyncResultCaptor.getAllValues()) {
            GetSetValueResult result = results.getList().get(0);
            assertThat(result.getErrorCode()).isEqualTo(STATUS_OK);
            assertThat(result.getUpdateTimestampNanos()).isEqualTo(TEST_UPDATE_TIMESTAMP_NANOS);
        }
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));
        verify(mVehicleHal).unsubscribeProperty(any(), eq(PERF_VEHICLE_SPEED));

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_propertyUpdatedThroughEvent_ignoreNonTargetEvent()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();
        List<HalServiceBase> serviceWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        // Because HVAC_TEMPERATURE_SET is ON_CHANGE property, the sample rate is 0.
        doAnswer((invocation) -> {
            serviceWrap.add(invocation.getArgument(0));
            return null;
        }).when(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the get initial value result. This is not the target value.
        deliverOkayGetResult(getInvocationWrap.get(0), mPropValueBuilder.build(
                HVAC_TEMPERATURE_SET, /* areaId= */ 0, 16.0f));
        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));

        // Generate a property update event with non-target value.
        serviceWrap.get(0).onHalEvents(List.of(mNonTargetPropValue));

        verify(mSetAsyncPropertyResultCallback, never()).onSetValueResults(any());

        // Notify the property is updated to the target value.
        serviceWrap.get(0).onHalEvents(List.of(mPropValue));

        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(STATUS_OK);
        assertThat(result.getUpdateTimestampNanos()).isEqualTo(TEST_UPDATE_TIMESTAMP_NANOS);
        // After the result comes, we must unsubscribe the property.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_updateSubscriptionRate()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        AsyncPropertyServiceRequest request = copyRequest(SET_PROPERTY_SERVICE_REQUEST_2);
        request.setUpdateRateHz(20.0f);
        mPropertyHalService.setCarPropertyValuesAsync(List.of(request),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(20.0f));
        clearInvocations(mVehicleHal);

        mPropertyHalService.subscribeProperty(PERF_VEHICLE_SPEED, /* updateRateHz= */ 40.0f);

        // Subscription rate has to be updated according to client subscription.
        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(40.0f));
        clearInvocations(mVehicleHal);

        // After client unsubscribe, revert back to the internal subscription rate.
        mPropertyHalService.unsubscribeProperty(PERF_VEHICLE_SPEED);

        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(20.0f));
        clearInvocations(mVehicleHal);

        // New client subscription must overwrite the internal rate.
        mPropertyHalService.subscribeProperty(PERF_VEHICLE_SPEED, /* updateRateHz= */ 50.0f);

        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(50.0f));
        clearInvocations(mVehicleHal);

        // Finish the async set request.
        deliverOkaySetResult(setInvocationWrap.get(0));
        deliverOkayGetResult(getInvocationWrap.get(0), mPropValue2);

        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getErrorCode()).isEqualTo(
                STATUS_OK);

        // After the internal subscription is finished, the client subscription must be kept.
        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(50.0f));

        mPropertyHalService.unsubscribeProperty(PERF_VEHICLE_SPEED);

        // After both client and internal unsubscription, the property must be unsubscribed.
        verify(mVehicleHal).unsubscribeProperty(any(), eq(PERF_VEHICLE_SPEED));

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_defaultSubscriptionRate()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST_2),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 10);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Default sample rate is set to max sample rate.
        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(100.0f));

        // Finish the async set request.
        deliverOkaySetResult(setInvocationWrap.get(0));
        deliverOkayGetResult(getInvocationWrap.get(0), mPropValue2);
    }

    @Test
    public void testSetCarPropertyValuesAsync_setUpdateRateHz()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.subscribeProperty(PERF_VEHICLE_SPEED, /* updateRateHz= */ 22.0f);

        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(22.0f));
        clearInvocations(mVehicleHal);

        AsyncPropertyServiceRequest request1 = copyRequest(SET_PROPERTY_SERVICE_REQUEST_2);
        request1.setUpdateRateHz(23.1f);
        AsyncPropertyServiceRequest request2 = copyRequest(SET_PROPERTY_SERVICE_REQUEST_3);
        request2.setUpdateRateHz(23.2f);
        mPropertyHalService.setCarPropertyValuesAsync(List.of(request1, request2),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);
        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(23.2f));

        // Finish the async set request.
        deliverOkaySetResult(setInvocationWrap.get(0));
        deliverOkayGetResult(getInvocationWrap.get(0), mPropValue2);

        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        // Both request must succeed.
        assertThat(mAsyncResultCaptor.getValue().getList()).hasSize(2);
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getErrorCode()).isEqualTo(
                STATUS_OK);
        assertThat(mAsyncResultCaptor.getValue().getList().get(1).getErrorCode()).isEqualTo(
                STATUS_OK);

        // After internal subscription complete, the client subscription rate must be kept.
        verify(mVehicleHal).subscribeProperty(any(), eq(PERF_VEHICLE_SPEED), eq(22.0f));

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_cancelledBeforePropertyUpdateEvent()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();
        List<HalServiceBase> serviceWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        // Because HVAC_TEMPERATURE_SET is ON_CHANGE property, the sample rate is 0.
        doAnswer((invocation) -> {
            serviceWrap.add(invocation.getArgument(0));
            return null;
        }).when(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));

        // Cancel the ongoing request.
        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1});

        // Notify the property is updated to the target value after the request is cancelled.
        serviceWrap.get(0).onHalEvents(List.of(mPropValue));

        verify(mSetAsyncPropertyResultCallback, never()).onSetValueResults(any());
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    @Test
    public void testSetCarPropertyValuesAsync_timeoutBeforePropertyUpdateEvent()
            throws Exception {
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();
        List<HalServiceBase> serviceWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        // Because HVAC_TEMPERATURE_SET is ON_CHANGE property, the sample rate is 0.
        doAnswer((invocation) -> {
            serviceWrap.add(invocation.getArgument(0));
            return null;
        }).when(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));

        verify(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));

        List<AsyncGetSetRequest> setAsyncRequests = setInvocationWrap.get(0).getArgument(0);
        VehicleStubCallbackInterface callback = setInvocationWrap.get(0).getArgument(1);
        callback.onRequestsTimeout(
                List.of(setAsyncRequests.get(0).getServiceRequestId()));

        // Notify the property is updated to the target value after the request is cancelled.
        serviceWrap.get(0).onHalEvents(List.of(mPropValue));

        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        assertThat(mAsyncResultCaptor.getValue().getList()).hasSize(1);
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    // If we receive errors for the [propId, areaId] we are setting via onPropertySetError, we must
    // fail the pending request.
    @Test
    public void testSetCarPropertyValuesAsync_onPropertySetError() throws RemoteException {
        List<HalServiceBase> serviceWrap = new ArrayList<>();
        AsyncPropertyServiceRequest setPropertyRequest =
                new AsyncPropertyServiceRequest(1, HVAC_TEMPERATURE_SET, /* areaId= */ 1);

        doNothing().when(mVehicleHal).setAsync(any(List.class),
                any(VehicleStubCallbackInterface.class));
        doNothing().when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            serviceWrap.add(invocation.getArgument(0));
            return null;
        }).when(mVehicleHal).subscribeProperty(any(), eq(HVAC_TEMPERATURE_SET), eq(0f));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(setPropertyRequest),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArrayList<VehiclePropError> vehiclePropErrors = new ArrayList<>();
        VehiclePropError error1 = new VehiclePropError();
        error1.propId = HVAC_TEMPERATURE_SET;
        error1.areaId = 1;
        error1.errorCode = STATUS_NOT_AVAILABLE | (0x1234 << 16);
        // Error 2 has the wrong area ID and must be ignored.
        VehiclePropError error2 = new VehiclePropError();
        error2.propId = HVAC_TEMPERATURE_SET;
        error2.areaId = 2;
        error2.errorCode = STATUS_INTERNAL_ERROR;
        vehiclePropErrors.add(error1);
        vehiclePropErrors.add(error2);
        assertThat(serviceWrap).hasSize(1);
        serviceWrap.get(0).onPropertySetError(vehiclePropErrors);

        verify(mSetAsyncPropertyResultCallback).onSetValueResults(mAsyncResultCaptor.capture());
        assertThat(mAsyncResultCaptor.getValue().getList()).hasSize(1);
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getVendorErrorCode()).isEqualTo(
                0x1234);
        verify(mVehicleHal).unsubscribeProperty(any(), eq(HVAC_TEMPERATURE_SET));

        verifyNoPendingRequest();
    }

    @Test
    public void testOnSetAsyncResults_RetryAndTimeout() throws RemoteException {
        doAnswer((invocation) -> {
            // For every request, we return retry result.
            return deliverTryAgainSetResult(invocation);
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 10);

        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(
                mAsyncResultCaptor.capture());
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getRequestId())
                .isEqualTo(REQUEST_ID_1);
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);

        verifyNoPendingRequest();
    }

    @Test
    public void testOnSetAsyncResults_TimeoutFromVehicleStub() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List requests = (List) args[0];
            AsyncGetSetRequest request = (AsyncGetSetRequest) requests.get(0);
            VehicleStubCallbackInterface callback = (VehicleStubCallbackInterface) args[1];
            // Simulate the request has already timed-out.
            callback.onRequestsTimeout(List.of(request.getServiceRequestId()));
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(
                mAsyncResultCaptor.capture());
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getRequestId())
                .isEqualTo(REQUEST_ID_1);
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);

        verifyNoPendingRequest();
    }

    @Test
    public void testOnSetAsyncResults_errorResult() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverErrorSetResult(invocation, RECEIVED_REQUEST_ID_1,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(
                mAsyncResultCaptor.capture());
        GetSetValueResult result = mAsyncResultCaptor.getValue().getList().get(0);
        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(result.getErrorCode()).isEqualTo(CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(result.getCarPropertyValue()).isEqualTo(null);

        verifyNoPendingRequest();
    }

    @Test
    public void isDisplayUnitsProperty_returnsTrueForAllDisplayUnitProperties() {
        for (int propId : ImmutableList.of(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS,
                VehiclePropertyIds.FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME,
                VehiclePropertyIds.FUEL_VOLUME_DISPLAY_UNITS,
                VehiclePropertyIds.TIRE_PRESSURE_DISPLAY_UNITS,
                VehiclePropertyIds.EV_BATTERY_DISPLAY_UNITS,
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS)) {
            Assert.assertTrue(mPropertyHalService.isDisplayUnitsProperty(propId));
        }

        verifyNoPendingRequest();
    }

    @Test
    public void setProperty_handlesHalAndMgrPropIdMismatch() {
        HalPropConfig mockPropConfig = mock(HalPropConfig.class);
        CarPropertyValue mockCarPropertyValue = mock(CarPropertyValue.class);
        when(mockCarPropertyValue.getPropertyId()).thenReturn(
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS);
        when(mockCarPropertyValue.getAreaId()).thenReturn(0);
        when(mockCarPropertyValue.getValue()).thenReturn(1.0f);
        when(mockPropConfig.getPropId()).thenReturn(VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS);
        mPropertyHalService.takeProperties(ImmutableList.of(mockPropConfig));

        mPropertyHalService.setProperty(mockCarPropertyValue);

        HalPropValue value = mPropValueBuilder.build(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS, /* areaId= */ 0, /* value= */ 1.0f);
        verify(mVehicleHal).set(value);

        verifyNoPendingRequest();
    }

    @Test
    public void testCancelRequests_getCarPropertyValuesAsync() throws Exception {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<InvocationOnMock> invocationWrap = new ArrayList<>();
        doAnswer((invocation) -> {
            invocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        mPropertyHalService.getCarPropertyValuesAsync(List.of(
                GET_PROPERTY_SERVICE_REQUEST_1, GET_PROPERTY_SERVICE_REQUEST_2),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(invocationWrap).hasSize(1);

        // Cancel the first request.
        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1});

        verify(mVehicleHal).cancelRequests(List.of(0));

        // Deliver the results.
        deliverOkayGetResult(invocationWrap.get(0));

        // We should only get the result for request 2.
        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(
                mAsyncResultCaptor.capture());
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getRequestId())
                .isEqualTo(REQUEST_ID_2);

        verifyNoPendingRequest();
    }

    @Test
    public void testCancelRequests_setCarPropertyValuesAsync() throws Exception {
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        mPropertyHalService.setCarPropertyValuesAsync(List.of(
                SET_PROPERTY_SERVICE_REQUEST, SET_PROPERTY_SERVICE_REQUEST_2),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        assertThat(setInvocationWrap).hasSize(1);
        assertThat(getInvocationWrap).hasSize(1);

        // Cancel the first request.
        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1});

        // Note that because both the async set and the get init value request has the same manager
        // request ID, so they will all be cancelled.
        verify(mVehicleHal, timeout(1000).times(2)).cancelRequests(any());

        // Returns the set value result.
        deliverOkaySetResult(setInvocationWrap.get(0));
        // Returns the get initial value result.
        deliverOkayGetResult(getInvocationWrap.get(0));

        // We should only get the result for request 2.
        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(
                mAsyncResultCaptor.capture());
        assertThat(mAsyncResultCaptor.getValue().getList().get(0).getRequestId())
                .isEqualTo(REQUEST_ID_2);

        verifyNoPendingRequest();
    }

    @Test
    public void testCancelRequests_noPendingRequests() {
        mPropertyHalService.cancelRequests(new int[]{0});

        verify(mVehicleHal, never()).cancelRequests(any());

        verifyNoPendingRequest();
    }

    // Test that when the client binder died, all pending async requests must be cleared.
    @Test
    public void testOnBinderDied() throws Exception {
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        // We return the same binder object for both set and get here.
        doReturn(mSetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<InvocationOnMock> setInvocationWrap = new ArrayList<>();
        List<InvocationOnMock> getInvocationWrap = new ArrayList<>();

        doAnswer((invocation) -> {
            setInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doAnswer((invocation) -> {
            getInvocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST_2),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mSetAsyncPropertyResultBinder).linkToDeath(mDeathRecipientCaptor.capture(),
                anyInt());

        assertThat(setInvocationWrap).hasSize(1);
        // One is for async get, one is for initial value.
        assertThat(getInvocationWrap).hasSize(2);

        // Simulate client binder died.
        mDeathRecipientCaptor.getValue().binderDied();

        verify(mSetAsyncPropertyResultBinder).unlinkToDeath(any(), anyInt());

        // All async pending requests must be cleared.
        verifyNoPendingRequest();
    }

    @Test
    public void testGetPropertySync() throws Exception {
        HalPropValue value = mPropValueBuilder.build(INT32_PROP, /* areaId= */ 0, /* value= */ 123);
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        CarPropertyValue carPropValue = mPropertyHalService.getProperty(
                INT32_PROP, /* areaId= */ 0);

        assertThat(carPropValue.getValue()).isEqualTo(123);
    }

    @Test
    public void testGetPropertySyncErrorPropStatus() throws Exception {
        HalPropValue value = mPropValueBuilder.build(
                AidlVehiclePropValueBuilder.newBuilder(INT32_PROP)
                        .setStatus(VehiclePropertyStatus.ERROR).build());
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        // If the property has ERROR status, getProperty will throw ServiceSpecificException with
        // STATUS_INTERNAL_ERROR as error code.
        ServiceSpecificException e = assertThrows(ServiceSpecificException.class,
                () -> mPropertyHalService.getProperty(INT32_PROP, /* areaId= */ 0));
        assertThat(e.errorCode).isEqualTo(STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testGetPropertySyncUnavailablePropStatus() throws Exception {
        HalPropValue value = mPropValueBuilder.build(
                AidlVehiclePropValueBuilder.newBuilder(INT32_PROP)
                        .setStatus(VehiclePropertyStatus.UNAVAILABLE).build());
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        // If the property has UNAVAILABLE status, getProperty will throw ServiceSpecificException
        // with STATUS_NOT_AVAILABLE as error code.
        ServiceSpecificException e = assertThrows(ServiceSpecificException.class,
                () -> mPropertyHalService.getProperty(INT32_PROP, /* areaId= */ 0));
        assertThat(e.errorCode).isEqualTo(STATUS_NOT_AVAILABLE);
    }

    @Test
    public void testGetPropertySyncInvalidProp() throws Exception {
        // This property has no valid int array element.
        HalPropValue value = mPropValueBuilder.build(
                AidlVehiclePropValueBuilder.newBuilder(INT32_PROP).build());
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        // If the property value is not valid and cannot be converted to CarPropertyValue,
        // getProperty will throw ServiceSpecificException with STATUS_INTERNAL_ERROR.
        ServiceSpecificException e = assertThrows(ServiceSpecificException.class,
                () -> mPropertyHalService.getProperty(INT32_PROP, /* areaId= */ 0));
        assertThat(e.errorCode).isEqualTo(STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testOnPropertySetError() throws Exception {
        ArrayList<VehiclePropError> vehiclePropErrors = new ArrayList<>();
        VehiclePropError error1 = new VehiclePropError();
        error1.propId = HVAC_TEMPERATURE_SET;
        error1.areaId = 1;
        error1.errorCode = STATUS_NOT_AVAILABLE | (0x1234 << 16);
        VehiclePropError error2 = new VehiclePropError();
        error2.propId = PERF_VEHICLE_SPEED;
        error2.areaId = 0;
        error2.errorCode = STATUS_INTERNAL_ERROR;
        vehiclePropErrors.add(error1);
        vehiclePropErrors.add(error2);

        mPropertyHalService.setPropertyHalListener(mPropertyHalListener);
        mPropertyHalService.onPropertySetError(vehiclePropErrors);

        verify(mPropertyHalListener).onPropertySetError(HVAC_TEMPERATURE_SET, 1,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_PROPERTY_NOT_AVAILABLE);
        verify(mPropertyHalListener).onPropertySetError(PERF_VEHICLE_SPEED, 0,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
    }
}
