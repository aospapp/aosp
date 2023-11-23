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

import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState.PROVISION_STATE_DISMISSIBLE_UI;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState.PROVISION_STATE_FACTORY_RESET;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState.PROVISION_STATE_PERSISTENT_UI;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState.PROVISION_STATE_RETRY;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState.PROVISION_STATE_SUCCESS;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState.PROVISION_STATE_UNSPECIFIED;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.activities.DeviceLockNotificationManager;
import com.android.devicelockcontroller.common.DeviceLockConstants.SetupFailureReason;
import com.android.devicelockcontroller.policy.DevicePolicyController;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.policy.SetupController;
import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.provision.grpc.ReportDeviceProvisionStateGrpcResponse;
import com.android.devicelockcontroller.storage.GlobalParametersClient;

import com.google.common.util.concurrent.Futures;

import java.time.Duration;

/**
 * A worker class dedicated to report state of provision for the device lock program.
 */
public final class ReportDeviceProvisionStateWorker extends AbstractCheckInWorker {

    public static final String KEY_DEVICE_PROVISION_FAILURE_REASON =
            "device-provision-failure-reason";
    public static final String KEY_IS_PROVISION_SUCCESSFUL = "is-provision-successful";
    @VisibleForTesting
    static final String UNEXPECTED_PROVISION_STATE_ERROR_MESSAGE = "Unexpected provision state!";

    public static final String REPORT_PROVISION_STATE_WORK_NAME = "report-provision-state";
    private static final int NOTIFICATION_REPORT_INTERVAL_DAY = 1;

    /**
     * Get a {@link SetupController.SetupUpdatesCallbacks} which will enqueue this worker to report
     * provision success / failure.
     */
    @NonNull
    public static SetupController.SetupUpdatesCallbacks getSetupUpdatesCallbacks(
            WorkManager workManager) {
        return new SetupController.SetupUpdatesCallbacks() {
            @Override
            public void setupFailed(@SetupFailureReason int reason) {
                reportSetupFailed(reason, workManager);
            }

            @Override
            public void setupCompleted() {
                reportSetupCompleted(workManager);
            }
        };
    }

    private static void reportSetupFailed(@SetupFailureReason int reason, WorkManager workManager) {
        enqueueReportWork(false, reason, workManager, Duration.ZERO);
    }

    private static void reportSetupCompleted(WorkManager workManager) {
        enqueueReportWork(true, /* ignored */ SetupFailureReason.SETUP_FAILED, workManager,
                Duration.ZERO);
    }

    private static void reportStateInOneDay(WorkManager workManager) {
        // Report that we have shown a failure notification to the user for one day and we did
        // not retry setup between this and the last report. The failure reason and setup result
        // have been reported in previous report.
        enqueueReportWork(/* ignored */ false, /* ignored */ SetupFailureReason.SETUP_FAILED,
                workManager,
                Duration.ofDays(NOTIFICATION_REPORT_INTERVAL_DAY));
    }

    private static void enqueueReportWork(boolean isSuccessful, int reason,
            WorkManager workManager, Duration delay) {
        Data inputData = new Data.Builder()
                .putBoolean(KEY_IS_PROVISION_SUCCESSFUL, isSuccessful)
                .putInt(KEY_DEVICE_PROVISION_FAILURE_REASON, reason)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work =
                new OneTimeWorkRequest.Builder(ReportDeviceProvisionStateWorker.class)
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(delay)
                        .build();
        workManager.enqueueUniqueWork(
                REPORT_PROVISION_STATE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, work);
    }

    public ReportDeviceProvisionStateWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @VisibleForTesting
    ReportDeviceProvisionStateWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams, DeviceCheckInClient client) {
        super(context, workerParams, client);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean isSuccessful = getInputData().getBoolean(
                KEY_IS_PROVISION_SUCCESSFUL, /* defaultValue= */ false);
        int reason = getInputData().getInt(KEY_DEVICE_PROVISION_FAILURE_REASON,
                SetupFailureReason.SETUP_FAILED);
        GlobalParametersClient globalParametersClient = GlobalParametersClient.getInstance();
        int lastState = Futures.getUnchecked(
                globalParametersClient.getLastReceivedProvisionState());
        final ReportDeviceProvisionStateGrpcResponse response =
                Futures.getUnchecked(mClient).reportDeviceProvisionState(reason, lastState,
                        isSuccessful);
        if (response.hasRecoverableError()) return Result.retry();
        if (response.hasFatalError()) return Result.failure();
        String enrollmentToken = response.getEnrollmentToken();
        if (!TextUtils.isEmpty(enrollmentToken)) {
            Futures.getUnchecked(globalParametersClient.setEnrollmentToken(enrollmentToken));
        }
        // TODO(b/276392181): Handle next state properly
        int nextState = response.getNextClientProvisionState();
        Futures.getUnchecked(globalParametersClient.setLastReceivedProvisionState(nextState));

        PolicyObjectsInterface policyObjects =
                (PolicyObjectsInterface) mContext.getApplicationContext();
        DevicePolicyController devicePolicyController = policyObjects.getPolicyController();
        DeviceStateController deviceStateController = policyObjects.getStateController();
        switch (nextState) {
            case PROVISION_STATE_RETRY:
                DeviceCheckInHelper.setProvisionSucceeded(deviceStateController,
                        devicePolicyController, mContext, /* isMandatory= */ false);
                break;
            case PROVISION_STATE_DISMISSIBLE_UI:
                DeviceLockNotificationManager.sendDeviceResetNotification(mContext,
                        response.getDaysLeftUntilReset());
                reportStateInOneDay(WorkManager.getInstance(mContext));
                break;
            case PROVISION_STATE_PERSISTENT_UI:
                DeviceLockNotificationManager.sendDeviceResetInOneDayOngoingNotification(mContext);
                reportStateInOneDay(WorkManager.getInstance(mContext));
                break;
            case PROVISION_STATE_FACTORY_RESET:
                // TODO(b/284003841): Show a count down timer.
                devicePolicyController.wipeData();
                break;
            case PROVISION_STATE_SUCCESS:
            case PROVISION_STATE_UNSPECIFIED:
                // no-op
                break;
            default:
                throw new IllegalStateException(UNEXPECTED_PROVISION_STATE_ERROR_MESSAGE);
        }
        return Result.success();
    }
}
