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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Data;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.provision.grpc.IsDeviceInApprovedCountryGrpcResponse;

import com.google.common.util.concurrent.Futures;

/**
 * A worker class dedicated to check whether device is in approved country.
 */
public final class IsDeviceInApprovedCountryWorker extends
        AbstractCheckInWorker {

    public static final String KEY_CARRIER_INFO = "carrier-info";
    public static final String KEY_IS_IN_APPROVED_COUNTRY = "is-in-approved-country";

    IsDeviceInApprovedCountryWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @VisibleForTesting
    IsDeviceInApprovedCountryWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParameters,
            DeviceCheckInClient client) {
        super(context, workerParameters, client);
    }

    @NonNull
    @Override
    public Result doWork() {
        String carrierInfo = getInputData().getString(KEY_CARRIER_INFO);
        IsDeviceInApprovedCountryGrpcResponse response = Futures.getUnchecked(
                mClient).isDeviceInApprovedCountry(carrierInfo);

        if (response.isSuccessful()) {
            return Result.success(new Data.Builder().putBoolean(KEY_IS_IN_APPROVED_COUNTRY,
                    response.isDeviceInApprovedCountry()).build());
        }
        return Result.failure();
    }

}
