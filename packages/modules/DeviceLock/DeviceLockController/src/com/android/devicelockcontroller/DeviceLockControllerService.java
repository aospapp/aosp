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

package com.android.devicelockcontroller;

import static com.android.devicelockcontroller.IDeviceLockControllerService.KEY_HARDWARE_ID_RESULT;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.CLEAR;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.LOCK_DEVICE;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.UNLOCK_DEVICE;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.PSEUDO_LOCKED;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;

import androidx.annotation.NonNull;
import androidx.work.WorkManager;

import com.android.devicelockcontroller.policy.DevicePolicyController;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.provision.worker.ReportDeviceLockProgramCompleteWorker;
import com.android.devicelockcontroller.storage.GlobalParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Device Lock Controller Service. This is hosted in an APK and is bound
 * by the Device Lock System Service.
 */
public final class DeviceLockControllerService extends Service {
    private static final String TAG = "DeviceLockControllerService";
    private DevicePolicyController mPolicyController;
    private DeviceStateController mStateController;

    private final IDeviceLockControllerService.Stub mBinder =
            new IDeviceLockControllerService.Stub() {
                @Override
                public void lockDevice(RemoteCallback remoteCallback) {
                    Futures.addCallback(
                            Futures.transformAsync(
                                    mStateController.setNextStateForEvent(LOCK_DEVICE),
                                    (Void unused) -> mStateController.getState() == PSEUDO_LOCKED
                                            ? Futures.immediateFuture(true)
                                            : mPolicyController.launchActivityInLockedMode(),
                                    DeviceLockControllerService.this.getMainExecutor()),
                            remoteCallbackWrapper(remoteCallback, KEY_LOCK_DEVICE_RESULT),
                            MoreExecutors.directExecutor());
                }

                @Override
                public void unlockDevice(RemoteCallback remoteCallback) {
                    Futures.addCallback(
                            Futures.transform(
                                    mStateController.setNextStateForEvent(UNLOCK_DEVICE),
                                    (Void unused) -> true, MoreExecutors.directExecutor()),
                            remoteCallbackWrapper(remoteCallback, KEY_UNLOCK_DEVICE_RESULT),
                            MoreExecutors.directExecutor());

                }

                @Override
                public void isDeviceLocked(RemoteCallback remoteCallback) {
                    final boolean isLocked = mStateController.isLocked();
                    sendResult(IDeviceLockControllerService.KEY_IS_DEVICE_LOCKED_RESULT,
                            remoteCallback, isLocked);
                }

                @Override
                public void getDeviceIdentifier(RemoteCallback remoteCallback) {
                    Futures.addCallback(
                            GlobalParametersClient.getInstance().getRegisteredDeviceId(),
                            remoteCallbackWrapper(remoteCallback, KEY_HARDWARE_ID_RESULT),
                            MoreExecutors.directExecutor());
                }

                @Override
                public void clearDeviceRestrictions(RemoteCallback remoteCallback) {
                    Futures.addCallback(
                            Futures.transform(mStateController.setNextStateForEvent(CLEAR),
                                    (Void unused) -> {
                                        WorkManager workManager =
                                                WorkManager.getInstance(getApplicationContext());
                                        ReportDeviceLockProgramCompleteWorker
                                                .reportDeviceLockProgramComplete(workManager);
                                        return true;
                                    }, MoreExecutors.directExecutor()),
                            remoteCallbackWrapper(remoteCallback, KEY_CLEAR_DEVICE_RESULT),
                            MoreExecutors.directExecutor());

                }
            };

    @NonNull
    private static FutureCallback<Object> remoteCallbackWrapper(RemoteCallback remoteCallback,
            final String key) {
        return new FutureCallback<>() {
            @Override
            public void onSuccess(Object result) {
                sendResult(key, remoteCallback, result);
            }

            @Override
            public void onFailure(Throwable t) {
                LogUtil.e(TAG, "Failed to perform the request", t);
                sendResult(key, remoteCallback, null);
            }
        };
    }

    private static void sendResult(String key, RemoteCallback remoteCallback, Object result) {
        final Bundle bundle = new Bundle();
        if (result instanceof Boolean) {
            bundle.putBoolean(key, (Boolean) result);
        } else if (result instanceof String) {
            bundle.putString(key, (String) result);
        }
        remoteCallback.sendResult(bundle);
    }

    @Override
    public void onCreate() {
        LogUtil.d(TAG, "onCreate");

        final PolicyObjectsInterface policyObjects = (PolicyObjectsInterface) getApplication();
        mStateController = policyObjects.getStateController();
        mPolicyController = policyObjects.getPolicyController();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
