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

package com.android.bedstead.nene.users;

import static android.Manifest.permission.CREATE_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.os.Build.VERSION_CODES.R;
import static android.os.Build.VERSION_CODES.S;

import static com.android.bedstead.nene.users.Users.users;

import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.exceptions.AdbException;
import com.android.bedstead.nene.exceptions.NeneException;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.nene.utils.ShellCommand;
import com.android.bedstead.nene.utils.ShellCommandUtils;
import com.android.bedstead.nene.utils.Versions;
import com.android.compatibility.common.util.BlockingBroadcastReceiver;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A representation of a User on device which may or may not exist.
 */
public class UserReference implements AutoCloseable {

    private static final Set<AdbUser.UserState> RUNNING_STATES = new HashSet<>(
            Arrays.asList(AdbUser.UserState.RUNNING_LOCKED,
                    AdbUser.UserState.RUNNING_UNLOCKED,
                    AdbUser.UserState.RUNNING_UNLOCKING)
    );

    private static final String LOG_TAG = "UserReference";

    private final int mId;

    private final UserManager mUserManager;

    private Long mSerialNo;
    private String mName;
    private UserType mUserType;
    private Boolean mIsPrimary;
    private boolean mParentCached = false;
    private UserReference mParent;

    UserReference(int id) {
        mId = id;
        mUserManager = TestApis.context().androidContextAsUser(this)
                .getSystemService(UserManager.class);
    }

    public final int id() {
        return mId;
    }

    /**
     * Get a {@link UserHandle} for the {@link #id()}.
     */
    public final UserHandle userHandle() {
        return UserHandle.of(mId);
    }

    /**
     * Remove the user from the device.
     *
     * <p>If the user does not exist, or the removal fails for any other reason, a
     * {@link NeneException} will be thrown.
     */
    public final void remove() {
        try {
            // Expected success string is "Success: removed user"
            ShellCommand.builder("pm remove-user")
                    .addOperand(mId)
                    .validate(ShellCommandUtils::startsWithSuccess)
                    .execute();

            Poll.forValue("User exists", this::exists)
                    .toBeEqualTo(false)
                    // TODO(b/203630556): Reduce timeout once we have a faster way of removing users
                    .timeout(Duration.ofMinutes(1))
                    .errorOnFail()
                    .await();
        } catch (AdbException e) {
            throw new NeneException("Could not remove user " + this, e);
        }
    }

    /**
     * Start the user.
     *
     * <p>After calling this command, the user will be running unlocked.
     *
     * <p>If the user does not exist, or the start fails for any other reason, a
     * {@link NeneException} will be thrown.
     */
    //TODO(scottjonathan): Deal with users who won't unlock
    public UserReference start() {
        try {
            // Expected success string is "Success: user started"
            ShellCommand.builder("am start-user")
                    .addOperand(mId)
                    .addOperand("-w")
                    .validate(ShellCommandUtils::startsWithSuccess)
                    .execute();

            Poll.forValue("User running unlocked", () -> isRunning() && isUnlocked())
                    .toBeEqualTo(true)
                    .errorOnFail()
                    .timeout(Duration.ofMinutes(1))
                    .await();
        } catch (AdbException e) {
            throw new NeneException("Could not start user " + this, e);
        }

        return this;
    }

    /**
     * Stop the user.
     *
     * <p>After calling this command, the user will be not running.
     */
    public UserReference stop() {
        try {
            // Expects no output on success or failure - stderr output on failure
            ShellCommand.builder("am stop-user")
                    .addOperand("-f") // Force stop
                    .addOperand(mId)
                    .allowEmptyOutput(true)
                    .validate(String::isEmpty)
                    .execute();

            Poll.forValue("User running", this::isRunning)
                    .toBeEqualTo(false)
                    // TODO(b/203630556): Replace stopping with something faster
                    .timeout(Duration.ofMinutes(10))
                    .errorOnFail()
                    .await();
        } catch (AdbException e) {
            throw new NeneException("Could not stop user " + this, e);
        }

        return this;
    }

    /**
     * Make the user the foreground user.
     *
     * <p>If the user is a profile, then this will make the parent the foreground user. It will
     * still return the {@link UserReference} of the profile in that case.
     */
    public UserReference switchTo() {
        UserReference parent = parent();
        if (parent != null) {
            parent.switchTo();
            return this;
        }

        if (TestApis.users().current().equals(this)) {
            // Already switched to
            return this;
        }

        // This is created outside of the try because we don't want to wait for the broadcast
        // on versions less than R
        BlockingBroadcastReceiver broadcastReceiver =
                new BlockingBroadcastReceiver(TestApis.context().instrumentedContext(),
                        Intent.ACTION_USER_FOREGROUND,
                        (intent) ->((UserHandle)
                                intent.getParcelableExtra(Intent.EXTRA_USER))
                                .getIdentifier() == mId);

        try {
            if (Versions.meetsMinimumSdkVersionRequirement(R)) {
                try (PermissionContext p =
                             TestApis.permissions().withPermission(INTERACT_ACROSS_USERS_FULL)) {
                    broadcastReceiver.registerForAllUsers();
                }
            }

            // Expects no output on success or failure
            ShellCommand.builder("am switch-user")
                    .addOperand(mId)
                    .allowEmptyOutput(true)
                    .validate(String::isEmpty)
                    .execute();

            if (Versions.meetsMinimumSdkVersionRequirement(R)) {
                broadcastReceiver.awaitForBroadcast();
            } else {
                Thread.sleep(20000);
            }
        } catch (AdbException e) {
            throw new NeneException("Could not switch to user", e);
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Interrupted while switching user", e);
        } finally {
            broadcastReceiver.unregisterQuietly();
        }

        return this;
    }

    /** Get the serial number of the user. */
    public long serialNo() {
        if (mSerialNo == null) {
            mSerialNo = TestApis.context().instrumentedContext().getSystemService(UserManager.class)
                    .getSerialNumberForUser(userHandle());

            if (mSerialNo == -1) {
                mSerialNo = null;
                throw new NeneException("User does not exist " + this);
            }
        }

        return mSerialNo;
    }

    /** Get the name of the user. */
    public String name() {
        if (mName == null) {
            if (!Versions.meetsMinimumSdkVersionRequirement(S)) {
                mName = adbUser().name();
            } else {
                try (PermissionContext p = TestApis.permissions().withPermission(CREATE_USERS)) {
                    mName = TestApis.context().androidContextAsUser(this)
                            .getSystemService(UserManager.class)
                            .getUserName();
                }
                if (mName.equals("")) {
                    if (!exists()) {
                        mName = null;
                        throw new NeneException("User does not exist " + this);
                    }
                }
            }
        }

        return mName;
    }

    /** Is the user running? */
    public boolean isRunning() {
        if (!Versions.meetsMinimumSdkVersionRequirement(S)) {
            AdbUser adbUser = adbUserOrNull();
            if (adbUser == null) {
                return false;
            }
            return RUNNING_STATES.contains(adbUser().state());
        }
        try (PermissionContext p = TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            return mUserManager.isUserRunning(userHandle());
        }
    }

    /** Is the user unlocked? */
    public boolean isUnlocked() {
        if (!Versions.meetsMinimumSdkVersionRequirement(S)) {
            AdbUser adbUser = adbUserOrNull();
            if (adbUser == null) {
                return false;
            }
            return adbUser.state().equals(AdbUser.UserState.RUNNING_UNLOCKED);
        }
        try (PermissionContext p = TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            return mUserManager.isUserUnlocked(userHandle());
        }
    }

    /**
     * Get the user type.
     */
    public UserType type() {
        if (mUserType == null) {
            if (!Versions.meetsMinimumSdkVersionRequirement(S)) {
                mUserType = adbUser().type();
            } else {
                try (PermissionContext p = TestApis.permissions().withPermission(CREATE_USERS)) {
                    String userTypeName = mUserManager.getUserType();
                    if (userTypeName.equals("")) {
                        throw new NeneException("User does not exist " + this);
                    }
                    mUserType = TestApis.users().supportedType(userTypeName);
                }
            }
        }
        return mUserType;
    }

    /**
     * Return {@code true} if this is the primary user.
     */
    public Boolean isPrimary() {
        if (mIsPrimary == null) {
            if (!Versions.meetsMinimumSdkVersionRequirement(S)) {
                mIsPrimary = adbUser().isPrimary();
            } else {
                mIsPrimary = userInfo().isPrimary();
            }
        }

        return mIsPrimary;
    }

    /**
     * Return the parent of this profile.
     *
     * <p>Returns {@code null} if this user is not a profile.
     */
    @Nullable
    public UserReference parent() {
        if (!mParentCached) {
            if (!Versions.meetsMinimumSdkVersionRequirement(S)) {
                mParent = adbUser().parent();
            } else {
                try (PermissionContext p =
                             TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
                    UserHandle parentHandle = mUserManager.getProfileParent(userHandle());
                    if (parentHandle == null) {
                        if (!exists()) {
                            throw new NeneException("User does not exist " + this);
                        }

                        mParent = null;
                    } else {
                        mParent = TestApis.users().find(parentHandle);
                    }
                }
            }
            mParentCached = true;
        }

        return mParent;
    }

    /**
     * Return {@code true} if a user with this ID exists.
     */
    public boolean exists() {
        if (!Versions.meetsMinimumSdkVersionRequirement(S)) {
            return TestApis.users().all().stream().anyMatch(u -> u.equals(this));
        }
        return users().anyMatch(ui -> ui.id == id());
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof UserReference)) {
            return false;
        }

        UserReference other = (UserReference) obj;

        return other.id() == id();
    }

    @Override
    public int hashCode() {
        return id();
    }

    /** See {@link #remove}. */
    @Override
    public void close() {
        remove();
    }

    private AdbUser adbUserOrNull() {
        return TestApis.users().fetchUser(mId);
    }

    private AdbUser adbUser() {
        AdbUser user = adbUserOrNull();
        if (user == null) {
            throw new NeneException("User does not exist " + this);
        }
        return user;
    }

    private UserInfo userInfo() {
        return users().filter(ui -> ui.id == id()).findFirst()
                .orElseThrow(() -> new NeneException("User does not exist " + this));
    }

    @Override
    public String toString() {
        return "User{id=" + id() + "}";
    }
}
