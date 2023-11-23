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

package com.android.permissioncontroller.role.ui.behavior;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.android.modules.utils.build.SdkLevel;
import com.android.permissioncontroller.role.ui.TwoTargetPreference;
import com.android.role.controller.model.Role;

/***
 * Interface for UI behavior for roles
 */
public interface RoleUiBehavior {

    /**
     * Check whether this role should be visible to user.
     *
     * @param role the role to check for
     * @param user the user to check for
     * @param context the `Context` to retrieve system services
     *
     * @return whether this role should be visible to user
     */
    default boolean isVisibleAsUser(@NonNull Role role, @NonNull UserHandle user,
            @NonNull Context context) {
        return true;
    }

    /**
     * Get the {@link Intent} to manage this role, or {@code null} to use the default UI.
     *
     * @param role the role to get the intent for
     * @param user the user to manage this role for
     * @param context the {@code Context} to retrieve system services
     *
     * @return the {@link Intent} to manage this role, or {@code null} to use the default UI.
     */
    @Nullable
    default Intent getManageIntentAsUser(@NonNull Role role, @NonNull UserHandle user,
            @NonNull Context context) {
        return null;
    }

    /**
     * Prepare a {@link Preference} for this role.
     *
     * @param role the role to prepare the preference for
     * @param preference the {@link Preference} for this role
     * @param user the user for this role
     * @param context the {@code Context} to retrieve system services
     */
    default void preparePreferenceAsUser(@NonNull Role role,
            @NonNull TwoTargetPreference preference,
            @NonNull UserHandle user,
            @NonNull Context context) {
        if (SdkLevel.isAtLeastU() && role.isExclusive()) {
            final UserManager userManager = context.getSystemService(UserManager.class);
            preference.setEnabled(!userManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_CONFIG_DEFAULT_APPS, user));
        }
    }

    /**
     * Check whether a qualifying application should be visible to user.
     *
     * @param applicationInfo the {@link ApplicationInfo} for the application
     * @param user the user for the application
     * @param context the {@code Context} to retrieve system services
     *
     * @return whether the qualifying application should be visible to user
     */
    default boolean isApplicationVisibleAsUser(@NonNull Role role,
            @NonNull ApplicationInfo applicationInfo, @NonNull UserHandle user,
            @NonNull Context context) {
        return true;
    }

    /**
     * Prepare a {@link Preference} for this role.
     *
     * @param role the role to prepare the preference for
     * @param preference the {@link Preference} for this role
     * @param user the user for this role
     * @param context the {@code Context} to retrieve system services
     */
    default void prepareApplicationPreferenceAsUser(@NonNull Role role,
            @NonNull Preference preference, @NonNull ApplicationInfo applicationInfo,
            @NonNull UserHandle user, @NonNull Context context) {
        if (SdkLevel.isAtLeastU() && role.isExclusive()) {
            final UserManager userManager = context.getSystemService(UserManager.class);
            preference.setEnabled(!userManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_CONFIG_DEFAULT_APPS, user));
        }
    }

    /**
     * Get the confirmation message for adding an application as a holder of this role.
     *
     * @param role the role to get confirmation message for
     * @param packageName the package name of the application to get confirmation message for
     * @param context the {@code Context} to retrieve system services
     *
     * @return the confirmation message, or {@code null} if no confirmation is needed
     */
    @Nullable
    default CharSequence getConfirmationMessage(@NonNull Role role, @NonNull String packageName,
            @NonNull Context context) {
        return null;
    }
}
