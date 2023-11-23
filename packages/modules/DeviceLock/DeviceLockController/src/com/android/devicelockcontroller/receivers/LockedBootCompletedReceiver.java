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

package com.android.devicelockcontroller.receivers;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserManager;

import androidx.annotation.VisibleForTesting;

import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.util.LogUtil;

/**
 * Handle {@link  Intent#ACTION_LOCKED_BOOT_COMPLETED}. This receiver runs for any user
 * (singleUser="false").
 *
 * This receiver starts lock task mode if applicable.
 */
public final class LockedBootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "LockedBootCompletedReceiver";

    private static DeviceStateController getDeviceStateController(Context context) {
        return ((PolicyObjectsInterface) context.getApplicationContext())
                .getStateController();
    }

    @VisibleForTesting
    static void startLockTaskModeIfApplicable(Context context) {
        final PackageManager pm = context.getPackageManager();
        final ComponentName lockTaskBootCompletedReceiver = new ComponentName(context,
                LockTaskBootCompletedReceiver.class);
        if (getDeviceStateController(context).isInSetupState()) {
            // b/172281939: WorkManager is not available at this moment, and we may not launch
            // lock task mode successfully. Therefore, defer it to LockTaskBootCompletedReceiver.
            LogUtil.i(TAG,
                    "Setup has not completed yet when ACTION_LOCKED_BOOT_COMPLETED is received. "
                            + "We can not start lock task mode here.");
            pm.setComponentEnabledSetting(lockTaskBootCompletedReceiver,
                    COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP);
            return;
        }

        BootUtils.startLockTaskModeAtBoot(context);
        pm.setComponentEnabledSetting(
                new ComponentName(context, LockTaskBootCompletedReceiver.class),
                COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        LogUtil.d(TAG, "Locked Boot completed");
        if (!intent.getAction().equals(Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
            return;
        }

        final boolean isUserProfile =
                context.getSystemService(UserManager.class).isProfile();

        if (isUserProfile) {
            return;
        }

        startLockTaskModeIfApplicable(context);
    }
}
