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
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.provision.grpc.DeviceFinalizeClient;
import com.android.devicelockcontroller.storage.GlobalParametersClient;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Future;

/**
 * A worker class dedicated to report completion of the device lock program.
 */
public final class ReportDeviceLockProgramCompleteWorker extends Worker {

    private static final String REPORT_DEVICE_LOCK_PROGRAM_COMPLETE_WORK_NAME =
            "report-device-lock-program-complete";
    private final Future<DeviceFinalizeClient> mClient;

    /**
     * Report that this device has completed the devicelock program by enqueueing a work item.
     */
    public static void reportDeviceLockProgramComplete(WorkManager workManager) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work =
                new OneTimeWorkRequest.Builder(ReportDeviceLockProgramCompleteWorker.class)
                        .setConstraints(constraints)
                        .build();
        workManager.enqueueUniqueWork(
                REPORT_DEVICE_LOCK_PROGRAM_COMPLETE_WORK_NAME,
                ExistingWorkPolicy.REPLACE, work);
    }

    public ReportDeviceLockProgramCompleteWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final String hostName = context.getResources().getString(
                R.string.check_in_server_host_name);
        final int portNumber = context.getResources().getInteger(
                R.integer.check_in_server_port_number);
        final String className = context.getResources().getString(
                R.string.device_finalize_client_class_name);
        final Pair<String, String> apikey = new Pair<>(
                context.getResources().getString(R.string.finalize_service_api_key_name),
                context.getResources().getString(R.string.finalize_service_api_key_value));
        ListenableFuture<String> registeredDeviceId =
                GlobalParametersClient.getInstance().getRegisteredDeviceId();
        ListenableFuture<String> enrollmentToken =
                GlobalParametersClient.getInstance().getEnrollmentToken();
        mClient = Futures.whenAllSucceed(registeredDeviceId, enrollmentToken).call(
                () -> DeviceFinalizeClient.getInstance(className, hostName, portNumber, apikey,
                        Futures.getDone(registeredDeviceId), Futures.getDone(enrollmentToken)),
                context.getMainExecutor());
    }

    @VisibleForTesting
    ReportDeviceLockProgramCompleteWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams, DeviceFinalizeClient client) {
        super(context, workerParams);
        mClient = Futures.immediateFuture(client);
    }

    @NonNull
    @Override
    public Result doWork() {
        DeviceFinalizeClient.ReportDeviceProgramCompleteResponse response =
                Futures.getUnchecked(mClient).reportDeviceProgramComplete();
        if (response.isSuccessful()) {
            return Result.success();
        } else if (response.hasRecoverableError()) {
            return Result.retry();
        } else {
            return Result.failure();
        }
    }
}
