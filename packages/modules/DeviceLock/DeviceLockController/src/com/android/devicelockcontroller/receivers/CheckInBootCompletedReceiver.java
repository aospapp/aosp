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

package com.android.devicelockcontroller.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

import androidx.annotation.VisibleForTesting;

import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.provision.worker.DeviceCheckInHelper;
import com.android.devicelockcontroller.storage.GlobalParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Boot completed broadcast receiver to enqueue the check-in work for provision when device boots
 * for the first time.
 * Note that this boot completed receiver differs with {@link LockTaskBootCompletedReceiver} in the
 * way that it only runs for system user.
 */
public final class CheckInBootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "CheckInBootCompletedReceiver";

    @VisibleForTesting
    static void checkInIfNeeded(DeviceStateController stateController,
            DeviceCheckInHelper checkInHelper) {
        if (stateController.isCheckInNeeded()) {
            Futures.addCallback(GlobalParametersClient.getInstance().needCheckIn(),
                    new FutureCallback<>() {
                        @Override
                        public void onSuccess(Boolean needCheckIn) {
                            if (needCheckIn) {
                                checkInHelper.enqueueDeviceCheckInWork(/* isExpedited= */ false);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            LogUtil.e(TAG, "Failed to know if we need to perform check-in!", t);
                        }
                    }, MoreExecutors.directExecutor());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) return;

        LogUtil.i(TAG, "Received boot completed intent");

        final boolean isUserProfile =
                context.getSystemService(UserManager.class).isProfile();

        if (isUserProfile) {
            return;
        }

        checkInIfNeeded(
                ((PolicyObjectsInterface) context.getApplicationContext()).getStateController(),
                new DeviceCheckInHelper(context));
    }
}
