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

package com.android.permission.util;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.util.Preconditions;
import com.android.permission.compat.UserHandleCompat;

import java.util.List;

/** Utility class to deal with Android users. */
public final class UserUtils {

    private UserUtils() {}

    /** Enforces cross user permission for the calling UID and the given {@code userId}. */
    public static void enforceCrossUserPermission(
            @UserIdInt int userId,
            boolean allowAll,
            @NonNull String message,
            @NonNull Context context) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandleCompat.getUserId(callingUid);
        if (userId == callingUserId) {
            return;
        }
        Preconditions.checkArgument(
                userId >= UserHandleCompat.USER_SYSTEM
                        || (allowAll && userId == UserHandleCompat.USER_ALL),
                "Invalid user " + userId);
        context.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
        if (callingUid != Process.SHELL_UID || userId < UserHandleCompat.USER_SYSTEM) {
            return;
        }
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager.hasUserRestrictionForUser(
                UserManager.DISALLOW_DEBUGGING_FEATURES, UserHandle.of(userId))) {
            throw new SecurityException("Shell does not have permission to access user " + userId);
        }
    }

    /** Returns whether a given {@code userId} corresponds to an existing user. */
    public static boolean isUserExistent(@UserIdInt int userId, @NonNull Context context) {
        return getUserHandles(context).contains(UserHandle.of(userId));
    }

    /** Returns all the alive users on the device. */
    @NonNull
    public static List<UserHandle> getUserHandles(@NonNull Context context) {
        UserManager userManager = context.getSystemService(UserManager.class);
        // This call requires the MANAGE_USERS permission.
        final long identity = Binder.clearCallingIdentity();
        try {
            return userManager.getUserHandles(true);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** Returns whether a given {@code userId} corresponds to a managed profile. */
    public static boolean isManagedProfile(@UserIdInt int userId, @NonNull Context context) {
        UserManager userManager = context.getSystemService(UserManager.class);
        // This call requires the QUERY_USERS permission.
        final long identity = Binder.clearCallingIdentity();
        try {
            return userManager.isManagedProfile(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns whether a given {@code userId} corresponds to a running managed profile, i.e. the
     * user is running and the quiet mode is not enabled.
     */
    public static boolean isProfileRunning(@UserIdInt int userId, @NonNull Context context) {
        UserManager userManager = context.getSystemService(UserManager.class);
        // This call requires the QUERY_USERS permission
        final long identity = Binder.clearCallingIdentity();
        try {
            return userManager.isUserRunning(UserHandle.of(userId))
                    && !userManager.isQuietModeEnabled(UserHandle.of(userId));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
