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
import android.content.res.Resources;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.storage.GlobalParametersClient;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * A base class for workers that execute gRPC requests with DeviceLock backend server.
 */
public abstract class AbstractCheckInWorker extends Worker {

    static final String TAG = "CheckInWorker";
    final ListenableFuture<DeviceCheckInClient> mClient;
    final Context mContext;

    AbstractCheckInWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        Resources resources = context.getResources();
        String hostName = resources.getString(R.string.check_in_server_host_name);
        int portNumber = resources.getInteger(R.integer.check_in_server_port_number);
        String className = resources.getString(R.string.device_check_in_client_class_name);
        Pair<String, String> apikey = new Pair<>(
                resources.getString(R.string.check_in_service_api_key_name),
                resources.getString(R.string.check_in_service_api_key_value));
        mClient = Futures.transform(GlobalParametersClient.getInstance().getRegisteredDeviceId(),
                registeredId -> DeviceCheckInClient.getInstance(
                        className, hostName, portNumber, apikey, registeredId),
                MoreExecutors.directExecutor());
        mContext = context;
    }

    @VisibleForTesting
    AbstractCheckInWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParameters, DeviceCheckInClient client) {
        super(context, workerParameters);
        mClient = Futures.immediateFuture(client);
        mContext = context;
    }
}
