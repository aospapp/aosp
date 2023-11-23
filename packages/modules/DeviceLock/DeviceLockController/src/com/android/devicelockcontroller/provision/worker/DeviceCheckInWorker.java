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

package com.android.devicelockcontroller.provision.worker;

import android.content.Context;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.Futures;

/**
 * A worker class dedicated to execute the check-in operation for device lock program.
 */
public final class DeviceCheckInWorker extends AbstractCheckInWorker {

    private final AbstractDeviceCheckInHelper mCheckInHelper;

    public DeviceCheckInWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        mCheckInHelper = new DeviceCheckInHelper(context);
    }

    @VisibleForTesting
    DeviceCheckInWorker(@NonNull Context context, @NonNull WorkerParameters workerParameters,
            AbstractDeviceCheckInHelper helper, DeviceCheckInClient client) {
        super(context, workerParameters, client);
        mCheckInHelper = helper;
    }

    @NonNull
    @Override
    public Result doWork() {
        LogUtil.i(TAG, "perform check-in request");
        final ArraySet<DeviceId> deviceIds = mCheckInHelper.getDeviceUniqueIds();
        final String carrierInfo = mCheckInHelper.getCarrierInfo();
        if (deviceIds.isEmpty()) {
            LogUtil.w(TAG, "CheckIn failed. No device identifier available!");
            return Result.failure();
        }
        final GetDeviceCheckInStatusGrpcResponse response =
                Futures.getUnchecked(mClient).getDeviceCheckInStatus(
                        deviceIds, carrierInfo, /* fcmRegistrationToken= */ null);
        if (response.isSuccessful()) {
            return mCheckInHelper.handleGetDeviceCheckInStatusResponse(response)
                    ? Result.success()
                    : Result.retry();
        }
        LogUtil.w(TAG, "CheckIn failed: " + response);
        return Result.failure();
    }
}
