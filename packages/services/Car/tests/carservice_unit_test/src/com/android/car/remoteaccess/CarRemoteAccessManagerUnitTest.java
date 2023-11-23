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

package com.android.car.remoteaccess;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.remoteaccess.CarRemoteAccessManager.CompletableRemoteTaskFuture;
import android.car.remoteaccess.CarRemoteAccessManager.RemoteTaskClientCallback;
import android.car.remoteaccess.ICarRemoteAccessCallback;
import android.car.remoteaccess.ICarRemoteAccessService;
import android.car.remoteaccess.RemoteTaskClientRegistrationInfo;
import android.car.test.mocks.JavaMockitoHelper;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public final class CarRemoteAccessManagerUnitTest {

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Executor mExecutor = mContext.getMainExecutor();

    private CarRemoteAccessManager mRemoteAccessManager;
    private static final int DEFAULT_TIMEOUT = 3000;

    @Mock private Car mCar;
    @Mock private IBinder mBinder;
    @Mock private ICarRemoteAccessService mService;
    @Captor
    private ArgumentCaptor<CompletableRemoteTaskFuture> mFutureCaptor;

    @Before
    public void setUp() throws Exception {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mService);
        mRemoteAccessManager = new CarRemoteAccessManager(mCar, mBinder);
    }

    @Test
    public void testSetRemoteTaskClient() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 0);

        mRemoteAccessManager.setRemoteTaskClient(mExecutor, remoteTaskClient);

        verify(mService).addCarRemoteTaskClient(any(ICarRemoteAccessCallback.class));
    }

    @Test
    public void testSetRemoteTaskClient_invalidArguments() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 0);

        assertThrows(IllegalArgumentException.class, () -> mRemoteAccessManager.setRemoteTaskClient(
                /* executor= */ null, remoteTaskClient));
        assertThrows(IllegalArgumentException.class, () -> mRemoteAccessManager.setRemoteTaskClient(
                mExecutor, /* callback= */ null));
    }

    @Test
    public void testSetRemoteTaskClient_doubleRegistration() throws Exception {
        RemoteTaskClient remoteTaskClientOne = new RemoteTaskClient(/* expectedCallbackCount= */ 0);
        RemoteTaskClient remoteTaskClientTwo = new RemoteTaskClient(/* expectedCallbackCount= */ 0);

        mRemoteAccessManager.setRemoteTaskClient(mExecutor, remoteTaskClientOne);

        assertThrows(IllegalStateException.class, () -> mRemoteAccessManager.setRemoteTaskClient(
                mExecutor, remoteTaskClientTwo));
    }

    @Test
    public void testSetRmoteTaskClient_remoteException() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 0);
        doThrow(RemoteException.class).when(mService)
                .addCarRemoteTaskClient(any(ICarRemoteAccessCallback.class));

        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(remoteTaskClient);
        internalCallback.onRemoteTaskRequested("clientId_testing", "taskId_testing",
                /* data= */ null, /* taskMaxDurationInSec= */ 10);

        assertWithMessage("Remote task").that(remoteTaskClient.getTaskId()).isNull();
    }

    @Test
    public void testClearRemoteTaskClient() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 0);
        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(remoteTaskClient);

        mRemoteAccessManager.clearRemoteTaskClient();
        internalCallback.onRemoteTaskRequested("clientId_testing", "taskId_testing",
                /* data= */ null, /* taskMaxDurationInSec= */ 10);

        assertWithMessage("Remote task").that(remoteTaskClient.getTaskId()).isNull();
    }

    @Test
    public void testClearRemoteTaskClient_remoteException() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 0);
        doThrow(RemoteException.class).when(mService)
                .removeCarRemoteTaskClient(any(ICarRemoteAccessCallback.class));
        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(remoteTaskClient);

        mRemoteAccessManager.clearRemoteTaskClient();
        internalCallback.onRemoteTaskRequested("clientId_testing", "taskId_testing",
                /* data= */ null, /* taskMaxDurationInSec= */ 10);

        assertWithMessage("Remote task").that(remoteTaskClient.getTaskId()).isNull();
    }

    @Test
    public void testClientRegistration() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 1);
        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(remoteTaskClient);
        String serviceId = "serviceId_testing";
        String vehicleId = "vehicleId_testing";
        String processorId = "processorId_testing";
        String clientId = "clientId_testing";

        internalCallback.onClientRegistrationUpdated(
                new RemoteTaskClientRegistrationInfo(serviceId, vehicleId, processorId, clientId));

        assertWithMessage("Service ID").that(remoteTaskClient.getServiceId()).isEqualTo(serviceId);
        assertWithMessage("Vehicle ID").that(remoteTaskClient.getVehicleId()).isEqualTo(vehicleId);
        assertWithMessage("Processor ID").that(remoteTaskClient.getProcessorId())
                .isEqualTo(processorId);
        assertWithMessage("Client ID").that(remoteTaskClient.getClientId()).isEqualTo(clientId);
    }

    @Test
    public void testClientRegistrationFail() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 1);
        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(remoteTaskClient);

        internalCallback.onClientRegistrationFailed();

        assertWithMessage("Registration fail").that(remoteTaskClient.isRegistrationFail()).isTrue();
    }

    @Test
    public void testRemoteTaskRequested() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 2);
        String clientId = "clientId_testing";
        String taskId = "taskId_testing";
        prepareRemoteTaskRequested(remoteTaskClient, clientId, taskId, /* data= */ null);

        assertWithMessage("Task ID").that(remoteTaskClient.getTaskId()).isEqualTo(taskId);
        assertWithMessage("Data").that(remoteTaskClient.getData()).isNull();
    }

    @Test
    public void testRemoteTaskRequested_withData() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 2);
        String clientId = "clientId_testing";
        String taskId = "taskId_testing";
        byte[] data = new byte[]{1, 2, 3, 4};
        prepareRemoteTaskRequested(remoteTaskClient, clientId, taskId, data);

        assertWithMessage("Task ID").that(remoteTaskClient.getTaskId()).isEqualTo(taskId);
        assertWithMessage("Data").that(remoteTaskClient.getData()).asList()
                .containsExactlyElementsIn(new Byte[]{1, 2, 3, 4});
    }

    @Test
    public void testRemoteTaskRequested_mismatchedClientId() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 1);
        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(remoteTaskClient);
        String serviceId = "serviceId_testing";
        String vehicleId = "vehicleId_testing";
        String processorId = "processorId_testing";
        String clientId = "clientId_testing";
        String misMatchedClientId = "clientId_mismatch";
        String taskId = "taskId_testing";
        byte[] data = new byte[]{1, 2, 3, 4};

        internalCallback.onClientRegistrationUpdated(
                new RemoteTaskClientRegistrationInfo(serviceId, vehicleId, processorId, clientId));
        internalCallback.onRemoteTaskRequested(misMatchedClientId, taskId, data,
                /* taskMaximumDurationInSec= */ 10);

        assertWithMessage("Task ID").that(remoteTaskClient.getTaskId()).isNull();
        assertWithMessage("Data").that(remoteTaskClient.getData()).isNull();
    }

    @Test
    public void testReportRemoteTaskDone() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 2);
        String clientId = "clientId_testing";
        String taskId = "taskId_testing";
        prepareRemoteTaskRequested(remoteTaskClient, clientId, taskId, /* data= */ null);

        mRemoteAccessManager.reportRemoteTaskDone(taskId);

        verify(mService).reportRemoteTaskDone(clientId, taskId);
    }

    @Test
    public void testReportRemoteTaskDone_nullTaskId() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mRemoteAccessManager.reportRemoteTaskDone(/* taskId= */ null));
    }

    @Test
    public void testReportRemoteTaskDone_noRegisteredClient() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> mRemoteAccessManager.reportRemoteTaskDone("taskId_testing"));
    }

    @Test
    public void testReportRemoteTaskDone_invalidTaskId() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient(/* expectedCallbackCount= */ 2);
        String clientId = "clientId_testing";
        String taskId = "taskId_testing";
        prepareRemoteTaskRequested(remoteTaskClient, clientId, taskId, /* data= */ null);
        doThrow(IllegalStateException.class).when(mService)
                .reportRemoteTaskDone(clientId, taskId);

        assertThrows(IllegalStateException.class,
                () -> mRemoteAccessManager.reportRemoteTaskDone(taskId));
    }

    @Test
    public void testSetPowerStatePostTaskExecution() throws Exception {
        int nextPowerState = CarRemoteAccessManager.NEXT_POWER_STATE_SUSPEND_TO_RAM;
        boolean runGarageMode = true;

        mRemoteAccessManager.setPowerStatePostTaskExecution(nextPowerState, runGarageMode);

        verify(mService).setPowerStatePostTaskExecution(nextPowerState, runGarageMode);
    }

    @Test
    public void testOnShutdownStarting() throws Exception {
        RemoteTaskClientCallback remoteTaskClient = mock(RemoteTaskClientCallback.class);
        String clientId = "clientId_testing";
        String serviceId = "serviceId_testing";
        String vehicleId = "vehicleId_testing";
        String processorId = "processorId_testing";
        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(remoteTaskClient);

        internalCallback.onClientRegistrationUpdated(
                new RemoteTaskClientRegistrationInfo(serviceId, vehicleId, processorId, clientId));

        verify(remoteTaskClient, timeout(DEFAULT_TIMEOUT)).onRegistrationUpdated(any());

        internalCallback.onShutdownStarting();

        verify(remoteTaskClient, timeout(DEFAULT_TIMEOUT)).onShutdownStarting(
                mFutureCaptor.capture());
        CompletableRemoteTaskFuture future = mFutureCaptor.getValue();

        verify(mService, never()).confirmReadyForShutdown(any());

        future.complete();

        verify(mService).confirmReadyForShutdown(clientId);
    }

    private ICarRemoteAccessCallback setClientAndGetCallback(RemoteTaskClientCallback client)
            throws Exception {
        ArgumentCaptor<ICarRemoteAccessCallback> internalCallbackCaptor =
                ArgumentCaptor.forClass(ICarRemoteAccessCallback.class);
        mRemoteAccessManager.setRemoteTaskClient(mExecutor, client);
        verify(mService).addCarRemoteTaskClient(internalCallbackCaptor.capture());
        return internalCallbackCaptor.getValue();
    }

    private void prepareRemoteTaskRequested(RemoteTaskClient client, String clientId,
            String taskId, byte[] data) throws Exception {
        ICarRemoteAccessCallback internalCallback = setClientAndGetCallback(client);
        String serviceId = "serviceId_testing";
        String vehicleId = "vehicleId_testing";
        String processorId = "processorId_testing";

        internalCallback.onClientRegistrationUpdated(
                new RemoteTaskClientRegistrationInfo(serviceId, vehicleId, processorId, clientId));
        internalCallback.onRemoteTaskRequested(clientId, taskId, data,
                /* taskMaximumDurationInSec= */ 10);
    }

    private static final class RemoteTaskClient implements RemoteTaskClientCallback {
        private static final int DEFAULT_TIMEOUT = 3000;

        private final CountDownLatch mLatch;
        private String mServiceId;
        private String mVehicleId;
        private String mProcessorId;
        private String mClientId;
        private String mTaskId;
        private boolean mRegistrationFailed;
        private byte[] mData;

        private RemoteTaskClient(int expectedCallbackCount) {
            mLatch = new CountDownLatch(expectedCallbackCount);
        }

        @Override
        public void onRegistrationUpdated(RemoteTaskClientRegistrationInfo info) {
            mServiceId = info.getServiceId();
            mVehicleId = info.getVehicleId();
            mProcessorId = info.getProcessorId();
            mClientId = info.getClientId();
            mLatch.countDown();
        }

        @Override
        public void onRegistrationFailed() {
            mRegistrationFailed = true;
            mLatch.countDown();
        }

        @Override
        public void onRemoteTaskRequested(String taskId, byte[] data, int remainingTimeSec) {
            mTaskId = taskId;
            mData = data;
            mLatch.countDown();
        }

        @Override
        public void onShutdownStarting(CarRemoteAccessManager.CompletableRemoteTaskFuture future) {
            mLatch.countDown();
        }

        public String getServiceId() throws Exception {
            JavaMockitoHelper.await(mLatch, DEFAULT_TIMEOUT);
            return mServiceId;
        }

        public String getVehicleId() throws Exception {
            JavaMockitoHelper.await(mLatch, DEFAULT_TIMEOUT);
            return mVehicleId;
        }

        public String getProcessorId() throws Exception {
            JavaMockitoHelper.await(mLatch, DEFAULT_TIMEOUT);
            return mProcessorId;
        }

        public String getClientId() throws Exception {
            JavaMockitoHelper.await(mLatch, DEFAULT_TIMEOUT);
            return mClientId;
        }

        public String getTaskId() throws Exception {
            JavaMockitoHelper.await(mLatch, DEFAULT_TIMEOUT);
            return mTaskId;
        }

        public byte[] getData() throws Exception {
            JavaMockitoHelper.await(mLatch, DEFAULT_TIMEOUT);
            return mData;
        }

        public boolean isRegistrationFail() throws Exception {
            JavaMockitoHelper.await(mLatch, DEFAULT_TIMEOUT);
            return mRegistrationFailed;
        }
    }
}
