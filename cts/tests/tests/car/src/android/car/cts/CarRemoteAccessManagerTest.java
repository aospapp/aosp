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

package android.car.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.car.Car;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.remoteaccess.CarRemoteAccessManager.CompletableRemoteTaskFuture;
import android.car.remoteaccess.CarRemoteAccessManager.RemoteTaskClientCallback;
import android.car.remoteaccess.RemoteTaskClientRegistrationInfo;
import android.content.Context;
import android.platform.test.annotations.AppModeFull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.PollingCheck;
import com.android.internal.annotations.GuardedBy;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.Executor;

@AppModeFull(reason = "Instant Apps cannot get car related permissions")
public final class CarRemoteAccessManagerTest extends AbstractCarTestCase {

    private static final String TAG = CarRemoteAccessManagerTest.class.getSimpleName();
    private static final int CALLBACK_WAIT_TIME_MS = 2_000;
    private static final String INVALID_TASK_ID = "THIS_ID_CANNOT_BE_VALID_!@#$%^&*()";

    private Executor mExecutor;
    private CarRemoteAccessManager mCarRemoteAccessManager;

    @Before
    public void setUp() throws Exception {
        assumeTrue("CarRemoteAccessService is not enabled, skipping test",
                getCar().isFeatureEnabled(Car.CAR_REMOTE_ACCESS_SERVICE));

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mExecutor = context.getMainExecutor();
        mCarRemoteAccessManager = (CarRemoteAccessManager) getCar()
                .getCarManager(Car.CAR_REMOTE_ACCESS_SERVICE);
        assertThat(mCarRemoteAccessManager).isNotNull();
    }

    @Test
    @ApiTest(apis = {
            "android.car.remoteaccess.CarRemoteAccessManager#"
                    + "setRemoteTaskClient(Executor, RemoteTaskClientCallback)",
    })
    public void testSetRemoteTaskClient() {
        RemoteTaskClientCallbackImpl callback = new RemoteTaskClientCallbackImpl();

        mCarRemoteAccessManager.setRemoteTaskClient(mExecutor, callback);

        PollingCheck.waitFor(CALLBACK_WAIT_TIME_MS, () -> callback.getServiceId() != null);
        PollingCheck.waitFor(CALLBACK_WAIT_TIME_MS, () -> callback.getVehicleId() != null);
        PollingCheck.waitFor(CALLBACK_WAIT_TIME_MS, () -> callback.getProcessorId() != null);
        PollingCheck.waitFor(CALLBACK_WAIT_TIME_MS, () -> callback.getClientId() != null);
    }

    @Test
    @ApiTest(apis = {
            "android.car.remoteaccess.CarRemoteAccessManager#"
                    + "setRemoteTaskClient(Executor, RemoteTaskClientCallback)",
    })
    public void testSetRemoteTaskClient_withAlreadyRegisteredClient() {
        RemoteTaskClientCallbackImpl callbackOne = new RemoteTaskClientCallbackImpl();
        RemoteTaskClientCallbackImpl callbackTwo = new RemoteTaskClientCallbackImpl();

        mCarRemoteAccessManager.setRemoteTaskClient(mExecutor, callbackOne);

        assertThrows(IllegalStateException.class,
                () -> mCarRemoteAccessManager.setRemoteTaskClient(mExecutor,
                        callbackTwo));
    }

    @Test
    @ApiTest(apis = {
            "android.car.remoteaccess.CarRemoteAccessManager#"
                    + "setRemoteTaskClient(Executor, RemoteTaskClientCallback)",
            "android.car.remoteaccess.CarRemoteAccessManager#clearRemoteTaskClient"
    })
    public void testClearRemoteTaskClient() {
        RemoteTaskClientCallbackImpl callback = new RemoteTaskClientCallbackImpl();
        mCarRemoteAccessManager.setRemoteTaskClient(mExecutor, callback);

        mCarRemoteAccessManager.clearRemoteTaskClient();

        // Calling clearRemoteTaskClient again to ensure that multiple calls do not cause errors.
        mCarRemoteAccessManager.clearRemoteTaskClient();
    }

    @Test
    @ApiTest(apis = {"android.car.remoteaccess.CarRemoteAccessManager#clearRemoteTaskClient"})
    public void testClearRemoteTaskClient_unregisteredClient() {
        mCarRemoteAccessManager.clearRemoteTaskClient();
    }

    @Test
    @ApiTest(apis = {
            "android.car.remoteaccess.CarRemoteAccessManager#reportRemoteTaskDone(String)"
    })
    public void testReportRemoteTaskDone_unregisteredClient() {
        assertThrows(IllegalStateException.class,
                () -> mCarRemoteAccessManager.reportRemoteTaskDone(INVALID_TASK_ID));
    }

    private static final class RemoteTaskClientCallbackImpl implements RemoteTaskClientCallback {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private RemoteTaskClientRegistrationInfo mInfo;

        @Override
        public void onRegistrationUpdated(@NonNull RemoteTaskClientRegistrationInfo info) {
            synchronized (mLock) {
                mInfo = info;
            }
        }

        @Override
        public void onRegistrationFailed() {
        }

        @Override
        public void onRemoteTaskRequested(@NonNull String taskId, @Nullable byte[] data,
                int taskMaxDurationInSec) {
        }

        @Override
        public void onShutdownStarting(@NonNull CompletableRemoteTaskFuture future) {
        }

        public String getServiceId() {
            synchronized (mLock) {
                return mInfo.getVehicleId();
            }
        }

        public String getVehicleId() {
            synchronized (mLock) {
                return mInfo.getVehicleId();
            }
        }

        public String getProcessorId() {
            synchronized (mLock) {
                return mInfo.getProcessorId();
            }
        }

        public String getClientId() {
            synchronized (mLock) {
                return mInfo.getClientId();
            }
        }
    }
}
