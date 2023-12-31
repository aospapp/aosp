/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.cts.emptydeviceowner;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class DeviceOwnerChangedReceiver extends DeviceAdminReceiver {

    static final String ACTION_TRANSFER_DEVICE_OWNER =
            "com.android.cts.emptydeviceowner.action.TRANSFER_DEVICE_OWNER";

    private static final String TAG = DeviceOwnerChangedReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "onReceive(user " + context.getUserId() + "): action=" + action);
        if (!DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED.equals(action)
                && !ACTION_TRANSFER_DEVICE_OWNER.equals(action)) {
            Log.e(TAG, "Received invalid intent: " + intent);
            return;
        }
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        String packageName = context.getPackageName();

        // If the request comes from EmptyDeviceAdmin.onEnabled, wait until
        // we become DO: This is because EmptyDeviceAdmin.onEnabled is triggered
        // as soon as we are set as active admin, and not necessarily DO yet.
        if (ACTION_TRANSFER_DEVICE_OWNER.equals(action)) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!dpm.isDeviceOwnerApp(packageName) && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) { }
            }
        }

        if (!dpm.isDeviceOwnerApp(packageName)) {
            Log.w(TAG, packageName + " is not the device owner");
            return;
        }
        ComponentName newAdmin = new ComponentName("com.android.cts.verifier",
        "com.android.cts.verifier.managedprovisioning.DeviceAdminTestReceiver");
        Log.d(TAG, "transferring ownership to " + newAdmin);
        dpm.transferOwnership(EmptyDeviceAdmin.getComponentName(context), newAdmin,
                /* bundle= */ null);
        Log.d(TAG, "ownership transferred to " + newAdmin.flattenToShortString());
    }
}
