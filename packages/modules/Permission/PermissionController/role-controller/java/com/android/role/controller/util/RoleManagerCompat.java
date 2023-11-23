/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.role.controller.util;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;

import androidx.annotation.NonNull;

import com.android.modules.utils.build.SdkLevel;
import com.android.role.controller.model.Role;

/**
 * Helper for accessing features in {@link RoleManager}.
 */
public class RoleManagerCompat {

    /**
     * Key in the generic shared preferences that stores if the user manually selected the "none"
     * role holder for a role.
     */
    private static final String IS_NONE_ROLE_HOLDER_SELECTED_KEY = "is_none_role_holder_selected:";

    /**
     * Name of generic shared preferences file.
     */
    private static final String PREFERENCES_FILE = "preferences";

    private RoleManagerCompat() {}

    /**
     * @see RoleManager#isBypassingRoleQualification()
     */
    public static boolean isBypassingRoleQualification(@NonNull RoleManager roleManager) {
        if (SdkLevel.isAtLeastS()) {
            return roleManager.isBypassingRoleQualification();
        } else {
            return false;
        }
    }

    /**
     * Get a device protected storage based shared preferences. Avoid storing sensitive data in it.
     *
     * @param context the context to get the shared preferences
     * @return a device protected storage based shared preferences
     */
    @NonNull
    private static SharedPreferences getDeviceProtectedSharedPreferences(@NonNull Context context) {
        if (!context.isDeviceProtectedStorage()) {
            context = context.createDeviceProtectedStorageContext();
        }
        return context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Check whether the role has the fallback holder enabled.
     *
     * @return whether the "none" role holder is not selected
     */
    public static boolean isRoleFallbackEnabledAsUser(@NonNull Role role, @NonNull UserHandle user,
            @NonNull Context context) {
        Context userContext = UserUtils.getUserContext(context, user);
        boolean isNoneHolderSelected = getDeviceProtectedSharedPreferences(userContext)
                .getBoolean(IS_NONE_ROLE_HOLDER_SELECTED_KEY + role.getName(), false);
        return !isNoneHolderSelected;
    }

    /**
     * Set whether the role has fallback holder enabled.
     *
     */
    public static void setRoleFallbackEnabledAsUser(@NonNull Role role,
            boolean fallbackEnabled, @NonNull UserHandle user, @NonNull Context context) {
        Context userContext = UserUtils.getUserContext(context, user);
        if (fallbackEnabled) {
            getDeviceProtectedSharedPreferences(userContext).edit()
                    .remove(IS_NONE_ROLE_HOLDER_SELECTED_KEY + role.getName())
                    .apply();
        } else {
            getDeviceProtectedSharedPreferences(userContext).edit()
                    .putBoolean(IS_NONE_ROLE_HOLDER_SELECTED_KEY + role.getName(), true)
                    .apply();
        }
    }
}
