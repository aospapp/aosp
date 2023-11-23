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

/**
 * Boot completed broadcast receiver to start lock task mode if applicable. This broadcast receiver
 * runs for every user on the device.
 * Note that this boot completed receiver differs with {@link CheckInBootCompletedReceiver} in the
 * way that it runs for any users.
 */
public final class LockTaskBootCompletedReceiver extends BroadcastReceiver {

    static final String TAG = "LockTaskBootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) return;

        final boolean isUserProfile =
                context.getSystemService(UserManager.class).isProfile();

        if (isUserProfile) {
            return;
        }

        BootUtils.startLockTaskModeAtBoot(context);
    }
}
