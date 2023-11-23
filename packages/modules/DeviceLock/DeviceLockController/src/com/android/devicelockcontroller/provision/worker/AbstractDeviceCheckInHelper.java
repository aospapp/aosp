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

import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;

/**
 * Base class that provides abstraction of utility APIs for device check-in.
 */
public abstract class AbstractDeviceCheckInHelper {

    /**
     * Enqueue the DeviceCheckIn work request to WorkManager
     *
     * @param isExpedited If true, the work request should be expedited;
     */
    public abstract void enqueueDeviceCheckInWork(boolean isExpedited);

    @NonNull
    abstract ArraySet<DeviceId> getDeviceUniqueIds();

    @NonNull
    abstract String getCarrierInfo();

    @WorkerThread
    abstract boolean handleGetDeviceCheckInStatusResponse(
            @NonNull GetDeviceCheckInStatusGrpcResponse response);
}
