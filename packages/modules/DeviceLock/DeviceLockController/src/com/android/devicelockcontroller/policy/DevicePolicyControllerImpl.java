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

package com.android.devicelockcontroller.policy;

import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_FINANCING_DEFERRED_PROVISIONING;
import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_FINANCING_PROVISIONING;
import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_FINANCING_SECONDARY_USER_PROVISIONING;
import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_SUBSIDY_DEFERRED_PROVISIONING;
import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_SUBSIDY_PROVISIONING;
import static com.android.devicelockcontroller.policy.PolicyHandler.SUCCESS;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.DeviceLockControllerApplication;
import com.android.devicelockcontroller.SystemDeviceLockManagerImpl;
import com.android.devicelockcontroller.common.DeviceLockConstants;
import com.android.devicelockcontroller.common.DeviceLockConstants.ProvisioningType;
import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Class that listens to state changes and applies the corresponding policies.
 * <p>
 * Note that some APIs return a listenable future because the underlying calls to
 * SetupParameterClient return a listenable future for inter process calls.
 */
public final class DevicePolicyControllerImpl
        implements DevicePolicyController, DeviceStateController.StateListener {
    static final String START_LOCK_TASK_MODE_WORK_NAME = StartLockTaskModeWorker.TAG;
    private static final String TAG = "DevicePolicyControllerImpl";
    private static final int START_LOCK_TASK_MODE_WORKER_INTERVAL = 35;
    private final List<PolicyHandler> mPolicyList = new ArrayList<>();
    private final Context mContext;
    private final DevicePolicyManager mDpm;
    private final LockTaskModePolicyHandler mLockTaskHandler;
    private final DeviceStateController mStateController;

    /**
     * Create a new policy controller.
     *
     * @param context         The context used by this policy controller.
     * @param stateController State controller.
     */
    public DevicePolicyControllerImpl(Context context, DeviceStateController stateController) {
        this(context, stateController,
                context.getSystemService(DevicePolicyManager.class));
    }

    @VisibleForTesting
    DevicePolicyControllerImpl(Context context,
            DeviceStateController stateController, DevicePolicyManager dpm) {
        mContext = context;
        mDpm = dpm;
        mStateController = stateController;
        mLockTaskHandler = new LockTaskModePolicyHandler(context, dpm);

        mPolicyList.add(new UserRestrictionsPolicyHandler(dpm,
                context.getSystemService(UserManager.class), Build.isDebuggable()));
        mPolicyList.add(new AppOpsPolicyHandler(context, SystemDeviceLockManagerImpl.getInstance(),
                context.getSystemService(AppOpsManager.class)));
        mPolicyList.add(mLockTaskHandler);
        mPolicyList.add(new PackagePolicyHandler(context, dpm));
        mPolicyList.add(new RolePolicyHandler(context, SystemDeviceLockManagerImpl.getInstance()));
        stateController.addCallback(this);
    }

    @Override
    public ListenableFuture<Boolean> launchActivityInLockedMode() {
        return Futures.transform(getLockedActivity(), launchIntent -> {
            if (launchIntent == null) {
                LogUtil.e(TAG, "Failed to get the locked activity");
                return false;
            }

            final ComponentName activity = launchIntent.getComponent();
            if (activity == null || !mLockTaskHandler.setPreferredActivityForHome(activity)) {
                LogUtil.e(TAG, "Failed to set preferred activity");
                return false;
            }

            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            LogUtil.i(TAG, String.format(Locale.US, "Launching activity: %s", activity));
            mContext.startActivity(launchIntent,
                    ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle());
            return true;
        }, mContext.getMainExecutor());
    }

    @Override
    public void enqueueStartLockTaskModeWorker(boolean isMandatory) {
        enqueueStartLockTaskModeWorkerWithDelay(isMandatory, Duration.ZERO);
    }

    @Override
    public void enqueueStartLockTaskModeWorkerWithDelay(boolean isMandatory, Duration delay) {
        final OneTimeWorkRequest.Builder startLockTaskModeRequestBuilder =
                new OneTimeWorkRequest.Builder(StartLockTaskModeWorker.class)
                        .setInitialDelay(delay)
                        .setBackoffCriteria(BackoffPolicy.LINEAR,
                                Duration.ofSeconds(START_LOCK_TASK_MODE_WORKER_INTERVAL));
        if (isMandatory) {
            startLockTaskModeRequestBuilder
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        }

        WorkManager.getInstance(mContext)
                .enqueueUniqueWork(START_LOCK_TASK_MODE_WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        startLockTaskModeRequestBuilder.build());
    }

    @Override
    public boolean wipeData() {
        LogUtil.i(TAG, "Wiping device");

        try {
            mDpm.wipeData(DevicePolicyManager.WIPE_SILENTLY
                    | DevicePolicyManager.WIPE_RESET_PROTECTION_DATA);
        } catch (SecurityException e) {
            LogUtil.e(TAG, "Cannot wipe device", e);

            return false;
        }

        return true;
    }

    @Override
    public ListenableFuture<Void> onStateChanged(@DeviceState int newState) {
        LogUtil.d(TAG, String.format(Locale.US, "onStateChanged (%d)", newState));

        List<ListenableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0, policyLen = mPolicyList.size(); i < policyLen; i++) {
            PolicyHandler policy = mPolicyList.get(i);
            futures.add(Futures.transform(
                    policy.setPolicyForState(newState), result -> {
                        if (SUCCESS != result) {
                            throw new RuntimeException(
                                    String.format(Locale.US, "Failed to set %s policy", policy));
                        }
                        return null;
                    }, mContext.getMainExecutor()));
        }
        return Futures.whenAllSucceed(futures).call(() -> null, mContext.getMainExecutor());
    }

    @Override
    public DeviceStateController getStateController() {
        return mStateController;
    }

    private ListenableFuture<Intent> getLockedActivity() {
        @DeviceState int state = mStateController.getState();

        switch (state) {
            case DeviceState.SETUP_IN_PROGRESS:
            case DeviceState.SETUP_SUCCEEDED:
                return getLandingActivityIntent();
            case DeviceState.KIOSK_SETUP:
                return getKioskSetupActivityIntent();
            case DeviceState.LOCKED:
                return getLockScreenActivityIntent();
            case DeviceState.SETUP_FAILED:
            case DeviceState.UNLOCKED:
            case DeviceState.CLEARED:
            case DeviceState.UNPROVISIONED:
                LogUtil.w(TAG, String.format(Locale.US, "%d is not a locked state", state));
                return Futures.immediateFuture(null);
            default:
                LogUtil.w(TAG, String.format(Locale.US, "%d is an invalid state", state));
                return Futures.immediateFuture(null);
        }
    }

    private ListenableFuture<Intent> getLandingActivityIntent() {
        SetupParametersClient client = SetupParametersClient.getInstance();
        ListenableFuture<Boolean> isMandatoryTask = client.isProvisionMandatory();
        ListenableFuture<@ProvisioningType Integer> provisioningTypeTask =
                client.getProvisioningType();
        return Futures.whenAllSucceed(isMandatoryTask, provisioningTypeTask).call(
                () -> {
                    Intent resultIntent = new Intent()
                            .setComponent(ComponentName.unflattenFromString(
                                    DeviceLockConstants.getLandingActivity(mContext)));
                    boolean isMandatory = Futures.getDone(isMandatoryTask);
                    switch (Futures.getDone(provisioningTypeTask)) {
                        case ProvisioningType.TYPE_FINANCED:
                            if (!mContext.getUser().isSystem()) {
                                return resultIntent.setAction(
                                        ACTION_START_DEVICE_FINANCING_SECONDARY_USER_PROVISIONING);
                            }
                            return resultIntent.setAction(
                                    isMandatory ? ACTION_START_DEVICE_FINANCING_PROVISIONING
                                            : ACTION_START_DEVICE_FINANCING_DEFERRED_PROVISIONING);
                        case ProvisioningType.TYPE_SUBSIDY:
                            return resultIntent.setAction(
                                    isMandatory ? ACTION_START_DEVICE_SUBSIDY_PROVISIONING
                                            : ACTION_START_DEVICE_SUBSIDY_DEFERRED_PROVISIONING);
                        case ProvisioningType.TYPE_UNDEFINED:
                        default:
                            throw new IllegalArgumentException("Provisioning type is unknown!");
                    }
                }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Intent> getLockScreenActivityIntent() {
        final PackageManager packageManager = mContext.getPackageManager();
        return Futures.transform(SetupParametersClient.getInstance().getKioskPackage(),
                kioskPackage -> {
                    if (kioskPackage == null) {
                        LogUtil.e(TAG, "Missing kiosk package parameter");
                        return null;
                    }

                    final Intent homeIntent =
                            new Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .setPackage(kioskPackage);
                    final ResolveInfo resolvedInfo =
                            packageManager
                                    .resolveActivity(
                                            homeIntent,
                                            PackageManager.MATCH_DEFAULT_ONLY);
                    if (resolvedInfo != null && resolvedInfo.activityInfo != null) {
                        return homeIntent.setComponent(
                                new ComponentName(kioskPackage,
                                        resolvedInfo.activityInfo.name));
                    }
                    // Kiosk app does not have an activity to handle the default home intent.
                    // Fall back to the
                    // launch activity.
                    // Note that in this case, Kiosk App can't be effectively set as the
                    // default home activity.
                    final Intent launchIntent = packageManager.getLaunchIntentForPackage(
                            kioskPackage);
                    if (launchIntent == null) {
                        LogUtil.e(TAG,
                                String.format(Locale.US, "Failed to get launch intent for %s",
                                        kioskPackage));
                        return null;
                    }

                    return launchIntent;
                }, mContext.getMainExecutor());
    }

    private ListenableFuture<Intent> getKioskSetupActivityIntent() {
        return Futures.transform(SetupParametersClient.getInstance().getKioskSetupActivity(),
                setupActivity -> {
                    if (setupActivity == null) {
                        LogUtil.e(TAG, "Failed to get setup Activity");
                        return null;
                    }

                    return new Intent().setComponent(
                            ComponentName.unflattenFromString(setupActivity));
                }, mContext.getMainExecutor());
    }

    /**
     * A worker class dedicated to start lock task mode when device is locked.
     */
    public static final class StartLockTaskModeWorker extends Worker {

        private static final String TAG = "StartLockTaskModeWorker";

        public StartLockTaskModeWorker(
                @NonNull Context context,
                @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            final Context context = DeviceLockControllerApplication.getAppContext();
            final ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_LOCKED) {
                LogUtil.i(TAG, "successfully entered lock task mode");
                return Result.success();
            }

            if (!Futures.getUnchecked(((PolicyObjectsInterface) context).getPolicyController()
                    .launchActivityInLockedMode())) {
                LogUtil.i(TAG, "failed entering lock task mode");
                return Result.failure();
            }

            LogUtil.i(TAG, "Retry entering lock task mode");
            return Result.retry();
        }
    }
}
