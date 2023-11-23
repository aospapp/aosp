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

package com.android.devicelockcontroller.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.ArraySet;

import androidx.annotation.Nullable;

import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState;

import java.util.ArrayList;
import java.util.Set;

/**
 * Stores global parameters.
 * <p>
 * Note that, these parameter values are common across all users which means any users can read or
 * write them. Due to this reason, unlike {@link UserParameters}, they must be accessed all the time
 * via the {@link GlobalParametersClient}.
 */
final class GlobalParameters {
    private static final String FILENAME = "global-params";
    private static final String KEY_KIOSK_SIGNING_CERT = "kiosk_signing_cert";
    private static final String KEY_LOCK_TASK_ALLOWLIST = "lock_task_allowlist";
    private static final String KEY_NEED_CHECK_IN = "need_check_in";
    private static final String KEY_REGISTERED_DEVICE_ID = "registered_device_id";
    private static final String KEY_FORCED_PROVISION = "forced_provision";
    private static final String KEY_ENROLLMENT_TOKEN = "enrollment_token";
    private static final String KEY_LAST_RECEIVED_PROVISION_STATE = "last-received-provision-state";


    private GlobalParameters() {
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        final Context deviceContext = context.createDeviceProtectedStorageContext();

        return deviceContext.getSharedPreferences(FILENAME, Context.MODE_PRIVATE);
    }

    /**
     * Get the kiosk app signature.
     *
     * @param context Context used to get the shared preferences.
     * @return the kiosk app signature.
     */
    @Nullable
    static String getKioskSignature(Context context) {
        return getSharedPreferences(context).getString(KEY_KIOSK_SIGNING_CERT, null);
    }

    /**
     * Sets the kiosk app signature.
     *
     * @param context   Context used to get the shared preferences.
     * @param signature Kiosk app signature.
     */
    static void setKioskSignature(Context context, String signature) {
        getSharedPreferences(context).edit().putString(KEY_KIOSK_SIGNING_CERT, signature).apply();
    }

    /**
     * Gets the list of packages allowlisted in lock task mode.
     *
     * @param context Context used to get the shared preferences.
     * @return List of packages that are allowed in lock task mode.
     */
    static ArrayList<String> getLockTaskAllowlist(Context context) {
        final ArrayList<String> allowlistArray = new ArrayList<>();
        SharedPreferences sharedPreferences = getSharedPreferences(context);
        final Set<String> allowlist =
                sharedPreferences.getStringSet(KEY_LOCK_TASK_ALLOWLIST, /* defValue= */ null);
        if (allowlist != null) {
            allowlistArray.addAll(allowlist);
        }

        return allowlistArray;
    }

    /**
     * Sets the list of packages allowlisted in lock task mode.
     *
     * @param context   Context used to get the shared preferences.
     * @param allowlist List of packages that are allowed in lock task mode.
     */
    static void setLockTaskAllowlist(Context context, ArrayList<String> allowlist) {
        final Set<String> allowlistSet = new ArraySet<>(allowlist);

        getSharedPreferences(context)
                .edit()
                .putStringSet(KEY_LOCK_TASK_ALLOWLIST, allowlistSet)
                .apply();
    }

    /**
     * Checks if a check-in request needs to be performed.
     *
     * @param context Context used to get the shared preferences.
     * @return true if check-in request needs to be performed.
     */
    static boolean needCheckIn(Context context) {
        return getSharedPreferences(context).getBoolean(KEY_NEED_CHECK_IN, /* defValue= */ true);
    }

    /**
     * Sets the value of whether this device needs to perform check-in request.
     *
     * @param context     Context used to get the shared preferences.
     * @param needCheckIn new state of whether the device needs to perform check-in request.
     */
    static void setNeedCheckIn(Context context, boolean needCheckIn) {
        getSharedPreferences(context)
                .edit()
                .putBoolean(KEY_NEED_CHECK_IN, needCheckIn)
                .apply();
    }

    /**
     * Gets the unique identifier that is regisered to DeviceLock backend server.
     *
     * @param context Context used to get the shared preferences.
     * @return The registered device unique identifier; null if device has never checked in with
     * backed server.
     */
    @Nullable
    static String getRegisteredDeviceId(Context context) {
        SharedPreferences preferences = getSharedPreferences(context);
        return preferences.getString(KEY_REGISTERED_DEVICE_ID, null);
    }

    /**
     * Set the unique identifier that is registered to DeviceLock backend server.
     *
     * @param context            Context used to get the shared preferences.
     * @param registeredDeviceId The registered device unique identifier.
     */
    static void setRegisteredDeviceId(Context context, String registeredDeviceId) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_REGISTERED_DEVICE_ID, registeredDeviceId)
                .apply();
    }

    /**
     * Check if provision should be forced.
     *
     * @param context Context used to get the shared preferences.
     * @return True if the provision should be forced without any delays.
     */
    static boolean isProvisionForced(Context context) {
        return getSharedPreferences(context).getBoolean(KEY_FORCED_PROVISION, false);
    }

    /**
     * Set provision is forced
     *
     * @param context  Context used to get the shared preferences.
     * @param isForced The new value of the forced provision flag.
     */
    static void setProvisionForced(Context context, boolean isForced) {
        getSharedPreferences(context)
                .edit()
                .putBoolean(KEY_FORCED_PROVISION, isForced)
                .apply();
    }

    /**
     * Get the enrollment token assigned by the Device Lock backend server.
     *
     * @param context Context used to get the shared preferences.
     * @return A string value of the enrollment token.
     */
    @Nullable
    static String getEnrollmentToken(Context context) {
        return getSharedPreferences(context).getString(KEY_ENROLLMENT_TOKEN, null);
    }

    /**
     * Set the enrollment token assigned by the Device Lock backend server.
     *
     * @param context Context used to get the shared preferences.
     * @param token   The string value of the enrollment token.
     */
    static void setEnrollmentToken(Context context, String token) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_ENROLLMENT_TOKEN, token)
                .apply();
    }

    @DeviceProvisionState
    static int getLastReceivedProvisionState(Context context) {
        return getSharedPreferences(context).getInt(KEY_LAST_RECEIVED_PROVISION_STATE,
                DeviceProvisionState.PROVISION_STATE_UNSPECIFIED);
    }

    static void setLastReceivedProvisionState(Context context,
            @DeviceProvisionState int provisionState) {
        getSharedPreferences(context)
                .edit()
                .putInt(KEY_LAST_RECEIVED_PROVISION_STATE, provisionState)
                .apply();
    }

    static void clear(Context context) {
        if (!Build.isDebuggable()) {
            throw new SecurityException("Clear is not allowed in non-debuggable build!");
        }
        getSharedPreferences(context).edit().clear().commit();
    }
}
