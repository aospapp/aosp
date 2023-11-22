/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.suite.checker;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.suite.checker.StatusCheckerResult.CheckStatus;
import com.android.tradefed.util.UserUtil;
import com.android.tradefed.util.UserUtil.UserSwitchFailedException;

/**
 * Checks if users have changed during the test.
 *
 * <p>Optionally can also setup the current user.
 */
@OptionClass(alias = "user-system-checker")
public class UserChecker implements ISystemStatusChecker {

    @Option(
        name = "user-type",
        description = "The type of user to switch to before each module run."
    )
    private UserUtil.UserType mUserToSwitchTo = UserUtil.UserType.CURRENT;

    public static final String DEFAULT_NAME = "TFauto";

    private DeviceUserState mPreExecutionUserState;

    /** {@inheritDoc} */
    @Override
    public StatusCheckerResult preExecutionCheck(ITestDevice device)
            throws DeviceNotAvailableException {

        String userSwitchErrorMsg = null;
        try {
            switchToExistingOrCreateUserType(device);
        } catch (UserSwitchFailedException err) {
            userSwitchErrorMsg = err.toString();
        }

        mPreExecutionUserState = new DeviceUserState(device);
        CLog.d("preExecutionUsers=" + mPreExecutionUserState);

        if (userSwitchErrorMsg == null) {
            return new StatusCheckerResult(CheckStatus.SUCCESS);
        } else {
            StatusCheckerResult result = new StatusCheckerResult(CheckStatus.FAILED);
            result.setErrorMessage(userSwitchErrorMsg);
            return result;
        }
    }

    /** {@inheritDoc} */
    @Override
    public StatusCheckerResult postExecutionCheck(ITestDevice device)
            throws DeviceNotAvailableException {
        DeviceUserState postDeviceUserState = new DeviceUserState(device);
        CLog.d("postExecutionUsers=" + postDeviceUserState);

        ArrayList<String> errors = new ArrayList<>();

        for (Integer removedUser : mPreExecutionUserState.findRemovedUsers(postDeviceUserState)) {
            errors.add(String.format("User %d no longer exists after test", removedUser));
        }

        for (Integer addedUser : mPreExecutionUserState.findAddedUsers(postDeviceUserState)) {
            errors.add(
                    String.format(
                            "User %d was created during the test and not deleted", addedUser));
        }

        if (mPreExecutionUserState.currentUserChanged(postDeviceUserState)) {
            errors.add(
                    String.format(
                            "User %d was the currentUser before, has changed to %d",
                            mPreExecutionUserState.getCurrentUser(),
                            postDeviceUserState.getCurrentUser()));
        }

        for (int userId : mPreExecutionUserState.findStoppedUsers(postDeviceUserState)) {
            CLog.w("User %d was running but is now stopped.", userId);
        }

        for (int userId : mPreExecutionUserState.findStartedUsers(postDeviceUserState)) {
            CLog.w("User %d was stopped but is now running.", userId);
        }

        if (errors.size() > 0) {
            StatusCheckerResult result = new StatusCheckerResult(CheckStatus.FAILED);
            result.setErrorMessage(String.join("\n", errors));
            return result;
        } else {
            return new StatusCheckerResult(CheckStatus.SUCCESS);
        }
    }

    /**
     * Switches to the mUserType, creating if necessary.
     *
     * <p>Returns null if success, the error string if there is an error.
     */
    private void switchToExistingOrCreateUserType(ITestDevice device)
            throws DeviceNotAvailableException, UserSwitchFailedException {
        try {
            UserUtil.switchToUserType(device, mUserToSwitchTo);
        } catch (UserUtil.SecondaryUserNotFoundException attemptCreate) {
            CLog.d("No secondary users exist, creating one.");
            int secondary = device.createUserNoThrow(DEFAULT_NAME);
            if (secondary <= 0) {
                throw new UserSwitchFailedException("Failed to create secondary user");
            }
            UserUtil.switchToUserType(device, mUserToSwitchTo);
        }
    }

    /** Class for monitoring changes to the user state between pre/post check. */
    static class DeviceUserState {
        private final int mCurrentUser;
        private final ArrayList<Integer> mUsers;
        private final HashMap<Integer, Boolean> mUserRunningStates;

        DeviceUserState(ITestDevice device) throws DeviceNotAvailableException {
            mCurrentUser = device.getCurrentUser();
            mUsers = device.listUsers();
            mUserRunningStates = new HashMap<>(mUsers.size());
            for (Integer userId : mUsers) {
                mUserRunningStates.put(userId, device.isUserRunning(userId));
            }
        }

        public int getCurrentUser() {
            return mCurrentUser;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(String.format("currentUser=%d;", getCurrentUser()));
            for (Integer userId : mUsers) {
                String running = mUserRunningStates.get(userId) ? "running" : "stopped";
                builder.append(String.format(" %d:%s", userId, running));
            }
            return builder.toString();
        }

        List<Integer> findRemovedUsers(DeviceUserState otherState) {
            ArrayList<Integer> removedUsers = new ArrayList<>();
            for (Integer userId : mUsers) {
                if (!otherState.containsUser(userId)) {
                    removedUsers.add(userId);
                }
            }
            return removedUsers;
        }

        List<Integer> findAddedUsers(DeviceUserState otherState) {
            ArrayList<Integer> addedUsers = new ArrayList<>();
            for (Integer userId : otherState.mUsers) {
                if (!this.containsUser(userId)) {
                    addedUsers.add(userId);
                }
            }
            return addedUsers;
        }

        boolean currentUserChanged(DeviceUserState otherState) {
            return this.getCurrentUser() != otherState.getCurrentUser();
        }

        List<Integer> findStartedUsers(DeviceUserState otherState) {
            ArrayList<Integer> startedUsers = new ArrayList<>();
            for (Integer userId : mUsers) {
                if (!this.isUserRunning(userId) && otherState.isUserRunning(userId)) {
                    startedUsers.add(userId);
                }
            }
            return startedUsers;
        }

        List<Integer> findStoppedUsers(DeviceUserState otherState) {
            ArrayList<Integer> stoppedUsers = new ArrayList<>();
            for (Integer userId : mUsers) {
                if (this.isUserRunning(userId) && !otherState.isUserRunning(userId)) {
                    stoppedUsers.add(userId);
                }
            }
            return stoppedUsers;
        }

        private boolean containsUser(int userId) {
            return mUserRunningStates.containsKey(userId);
        }

        private boolean isUserRunning(int userId) {
            return mUserRunningStates.getOrDefault(userId, /* default= */ false);
        }
    }
}
