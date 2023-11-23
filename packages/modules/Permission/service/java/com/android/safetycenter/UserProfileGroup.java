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

package com.android.safetycenter;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.permission.util.UserUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class that represent all the enabled profiles (profile parent and managed profile(s))
 * associated with a user id.
 *
 * @hide
 */
@RequiresApi(TIRAMISU)
public final class UserProfileGroup {

    private static final String TAG = "UserProfileGroup";

    @UserIdInt private final int mProfileParentUserId;
    private final int[] mManagedProfilesUserIds;
    private final int[] mManagedRunningProfilesUserIds;

    private UserProfileGroup(
            @UserIdInt int profileParentUserId,
            int[] managedProfilesUserIds,
            int[] managedRunningProfilesUserIds) {
        mProfileParentUserId = profileParentUserId;
        mManagedProfilesUserIds = managedProfilesUserIds;
        mManagedRunningProfilesUserIds = managedRunningProfilesUserIds;
    }

    /** Returns all the alive {@link UserProfileGroup}s. */
    public static List<UserProfileGroup> getAllUserProfileGroups(Context context) {
        List<UserProfileGroup> userProfileGroups = new ArrayList<>();
        List<UserHandle> userHandles = UserUtils.getUserHandles(context);
        for (int i = 0; i < userHandles.size(); i++) {
            UserHandle userHandle = userHandles.get(i);
            int userId = userHandle.getIdentifier();

            if (userProfileGroupsContain(userProfileGroups, userId)) {
                continue;
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.fromUser(context, userId);
            if (!userProfileGroup.contains(userId)) {
                continue;
            }

            userProfileGroups.add(userProfileGroup);
        }
        return userProfileGroups;
    }

    private static boolean userProfileGroupsContain(
            List<UserProfileGroup> userProfileGroups, @UserIdInt int userId) {
        for (int i = 0; i < userProfileGroups.size(); i++) {
            UserProfileGroup userProfileGroup = userProfileGroups.get(i);

            if (userProfileGroup.contains(userId)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the {@link UserProfileGroup} associated with the given {@code userId}.
     *
     * <p>The given {@code userId} could be related to the profile parent or any of its associated
     * profile(s).
     *
     * <p>It is possible for the {@code userId} to not be contained within the returned {@link
     * UserProfileGroup}. This can happen if the {@code userId} is a profile that is not managed or
     * is disabled.
     */
    public static UserProfileGroup fromUser(Context context, @UserIdInt int userId) {
        UserManager userManager = getUserManagerForUser(userId, context);
        List<UserHandle> userProfiles = getEnabledUserProfiles(userManager);
        UserHandle profileParent = getProfileParent(userManager, userId);
        int profileParentUserId = userId;
        if (profileParent != null) {
            profileParentUserId = profileParent.getIdentifier();
        }

        int[] managedProfilesUserIds = new int[userProfiles.size()];
        int[] managedRunningProfilesUserIds = new int[userProfiles.size()];
        int managedProfilesUserIdsLen = 0;
        int managedRunningProfilesUserIdsLen = 0;
        for (int i = 0; i < userProfiles.size(); i++) {
            UserHandle userProfileHandle = userProfiles.get(i);
            int userProfileId = userProfileHandle.getIdentifier();

            if (UserUtils.isManagedProfile(userProfileId, context)) {
                managedProfilesUserIds[managedProfilesUserIdsLen++] = userProfileId;
                if (UserUtils.isProfileRunning(userProfileId, context)) {
                    managedRunningProfilesUserIds[managedRunningProfilesUserIdsLen++] =
                            userProfileId;
                }
            }
        }

        UserProfileGroup userProfileGroup =
                new UserProfileGroup(
                        profileParentUserId,
                        Arrays.copyOf(managedProfilesUserIds, managedProfilesUserIdsLen),
                        Arrays.copyOf(
                                managedRunningProfilesUserIds, managedRunningProfilesUserIdsLen));
        if (!userProfileGroup.contains(userId)) {
            Log.w(
                    TAG,
                    "User id " + userId + " does not belong to " + userProfileGroup,
                    new Exception());
        }
        return userProfileGroup;
    }

    /** Returns whether the given {@code userId} is supported by {@link UserProfileGroup}. */
    public static boolean isSupported(@UserIdInt int userId, Context context) {
        if (!isProfile(userId, context)) {
            return true;
        }
        return UserUtils.isManagedProfile(userId, context);
    }

    private static UserManager getUserManagerForUser(@UserIdInt int userId, Context context) {
        Context userContext = getUserContext(context, UserHandle.of(userId));
        return requireNonNull(userContext.getSystemService(UserManager.class));
    }

    private static Context getUserContext(Context context, UserHandle userHandle) {
        if (Process.myUserHandle().equals(userHandle)) {
            return context;
        } else {
            try {
                return context.createPackageContextAsUser(context.getPackageName(), 0, userHandle);
            } catch (PackageManager.NameNotFoundException doesNotHappen) {
                throw new IllegalStateException(doesNotHappen);
            }
        }
    }

    private static boolean isProfile(@UserIdInt int userId, Context context) {
        // This call requires the INTERACT_ACROSS_USERS permission.
        final long callingId = Binder.clearCallingIdentity();
        try {
            UserManager userManager = getUserManagerForUser(userId, context);
            return userManager.isProfile();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private static List<UserHandle> getEnabledUserProfiles(UserManager userManager) {
        // This call requires the QUERY_USERS permission.
        final long callingId = Binder.clearCallingIdentity();
        try {
            return userManager.getUserProfiles();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Nullable
    private static UserHandle getProfileParent(UserManager userManager, @UserIdInt int userId) {
        // This call requires the INTERACT_ACROSS_USERS permission.
        final long callingId = Binder.clearCallingIdentity();
        try {
            return userManager.getProfileParent(UserHandle.of(userId));
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /** Returns the profile parent user id of the {@link UserProfileGroup}. */
    public int getProfileParentUserId() {
        return mProfileParentUserId;
    }

    /** Returns the managed profile user ids of the {@link UserProfileGroup}. */
    public int[] getManagedProfilesUserIds() {
        return mManagedProfilesUserIds;
    }

    /** Returns the running managed profile user ids of the {@link UserProfileGroup}. */
    public int[] getManagedRunningProfilesUserIds() {
        return mManagedRunningProfilesUserIds;
    }

    /**
     * Convenience method that combines the results of {@link
     * UserProfileGroup#getProfileParentUserId()} and {@link
     * UserProfileGroup#getManagedRunningProfilesUserIds()}.
     */
    public int[] getProfileParentAndManagedRunningProfilesUserIds() {
        int[] profileParentAndManagedRunningProfilesUserIds =
                new int[mManagedRunningProfilesUserIds.length + 1];
        profileParentAndManagedRunningProfilesUserIds[0] = mProfileParentUserId;
        System.arraycopy(
                mManagedRunningProfilesUserIds,
                0,
                profileParentAndManagedRunningProfilesUserIds,
                1,
                mManagedRunningProfilesUserIds.length);
        return profileParentAndManagedRunningProfilesUserIds;
    }

    /** Returns whether the {@link UserProfileGroup} contains the given {@code userId}. */
    public boolean contains(@UserIdInt int userId) {
        if (userId == mProfileParentUserId) {
            return true;
        }

        for (int i = 0; i < mManagedProfilesUserIds.length; i++) {
            if (userId == mManagedProfilesUserIds[i]) {
                return true;
            }
        }

        return false;
    }

    /** Returns whether the given {@code userId} is associated with a running managed profile. */
    boolean isManagedUserRunning(@UserIdInt int userId) {
        for (int i = 0; i < mManagedRunningProfilesUserIds.length; i++) {
            if (userId == mManagedRunningProfilesUserIds[i]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserProfileGroup)) return false;
        UserProfileGroup that = (UserProfileGroup) o;
        return mProfileParentUserId == that.mProfileParentUserId
                && Arrays.equals(mManagedProfilesUserIds, that.mManagedProfilesUserIds)
                && Arrays.equals(
                        mManagedRunningProfilesUserIds, that.mManagedRunningProfilesUserIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mProfileParentUserId,
                Arrays.hashCode(mManagedProfilesUserIds),
                Arrays.hashCode(mManagedRunningProfilesUserIds));
    }

    @Override
    public String toString() {
        return "UserProfileGroup{"
                + "mProfileParentUserId="
                + mProfileParentUserId
                + ", mManagedProfilesUserIds="
                + Arrays.toString(mManagedProfilesUserIds)
                + ", mManagedRunningProfilesUserIds="
                + Arrays.toString(mManagedRunningProfilesUserIds)
                + '}';
    }
}
