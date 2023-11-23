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

package com.android.devicelockcontroller.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.Nullable;

import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;

/**
 * Stores per-user local parameters.
 * Unlike {@link GlobalParameters}, this class can be directly accessed.
 */
public final class UserParameters {
    private static final String FILENAME = "user-params";
    private static final String KEY_DEVICE_STATE = "device_state";
    private static final String KEY_HOME_PACKAGE_OVERRIDE = "home_override_package";
    private static final String TAG = "UserParameters";

    private UserParameters() {
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        final Context deviceContext = context.createDeviceProtectedStorageContext();

        return deviceContext.getSharedPreferences(FILENAME, Context.MODE_PRIVATE);
    }


    /**
     * Gets the current device state.
     *
     * @param context Context used to get the shared preferences.
     * @return the current device state.
     */
    @DeviceState
    public static int getDeviceState(Context context) {
        return getSharedPreferences(context).getInt(KEY_DEVICE_STATE, DeviceState.UNPROVISIONED);
    }

    /**
     * Sets the current device state.
     *
     * @param context Context used to get the shared preferences.
     * @param state   New state.
     */
    public static void setDeviceState(Context context, @DeviceState int state) {
        getSharedPreferences(context).edit().putInt(KEY_DEVICE_STATE, state).apply();
    }

    /**
     * Sets the current device state and synchronously write to storage. This call will block until
     * write is complete.
     *
     * @param state new {@link DeviceState}
     * @return true if state is set successfully; otherwise, false.
     */
    public static boolean setDeviceStateSync(Context context, @DeviceState int state) {
        return getSharedPreferences(context).edit().putInt(KEY_DEVICE_STATE, state).commit();
    }

    /**
     * Gets the name of the package overriding home.
     *
     * @param context Context used to get the shared preferences.
     * @return Package overriding home.
     */
    @Nullable
    public static String getPackageOverridingHome(Context context) {
        return getSharedPreferences(context).getString(KEY_HOME_PACKAGE_OVERRIDE, null);
    }

    /**
     * Sets the name of the package overriding home.
     *
     * @param context     Context used to get the shared preferences.
     * @param packageName Package overriding home.
     */
    public static void setPackageOverridingHome(Context context, @Nullable String packageName) {
        getSharedPreferences(context).edit()
                .putString(KEY_HOME_PACKAGE_OVERRIDE, packageName).apply();
    }

    /**
     * Clear all user parameters.
     */
    public static void clear(Context context) {
        if (!Build.isDebuggable()) {
            throw new SecurityException("Clear is not allowed in non-debuggable build!");
        }
        getSharedPreferences(context).edit().clear().commit();
    }
}
