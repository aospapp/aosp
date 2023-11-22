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
package com.android.tradefed.util;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;

public class UserUtil {
    // From the UserInfo class.
    public static final int FLAG_PRIMARY = 0x00000001;
    public static final int FLAG_GUEST = 0x00000004;
    public static final int FLAG_RESTRICTED = 0x00000008;
    public static final int FLAG_MANAGED_PROFILE = 0x00000020;
    public static final int USER_SYSTEM = 0;

    public static final int FLAGS_NOT_SECONDARY =
            FLAG_PRIMARY | FLAG_MANAGED_PROFILE | FLAG_GUEST | FLAG_RESTRICTED;

    /** Thrown if a user switch could not happen. */
    public static class UserSwitchFailedException extends Exception {
        public UserSwitchFailedException(String message) {
            super(message);
        }
    }

    /** Thrown if a user switch could not happen because the secondary user could not be found. */
    public static class SecondaryUserNotFoundException extends UserSwitchFailedException {
        public SecondaryUserNotFoundException() {
            super("Secondary User Not Found");
        }
    }

    /** Parameters that specify which user to run the test module as. */
    public enum UserType {
        // TODO:(b/123077733) Add support for guest

        /** current foreground user of the device */
        CURRENT,
        /** user flagged as primary on the device; most often primary = system user = user 0 */
        PRIMARY,
        /** system user = user 0 */
        SYSTEM,
        /** secondary user, i.e. non-primary and non-system. */
        SECONDARY,
    }

    /**
     * Attempt to switch to a user type.
     *
     * @returns true if successful, false if not.
     */
    public static void switchToUserType(ITestDevice device, UserType userType)
            throws DeviceNotAvailableException, UserSwitchFailedException {
        switch (userType) {
            case CURRENT:
                return; // do nothing
            case SYSTEM:
                switchUser(device, USER_SYSTEM);
                return;
            case PRIMARY:
                switchUser(device, device.getPrimaryUserId());
                return;
            case SECONDARY:
                switchToSecondaryUser(device);
                return;
            default:
                throw new RuntimeException("userType case not covered: " + userType);
        }
    }

    /**
     * Attempt to switch to a secondary user, creating one if necessary.
     *
     * @returns true if successful, false if not.
     */
    private static void switchToSecondaryUser(ITestDevice device)
            throws DeviceNotAvailableException, UserSwitchFailedException {
        int currentUser = device.getCurrentUser();
        if (device.isUserSecondary(currentUser)) {
            CLog.d("currentUser is already secondary, no action.");
            return;
        }

        int secondary = findExistingSecondary(device);
        if (secondary <= 0) {
            throw new SecondaryUserNotFoundException();
        }

        switchUser(device, secondary);
    }

    private static void switchUser(ITestDevice device, int userId)
            throws DeviceNotAvailableException, UserSwitchFailedException {
        if (!device.switchUser(userId)) {
            throw new UserSwitchFailedException("Failed to switch to user " + userId);
        }
    }

    /**
     * Finds an arbitrary secondary user and returns the userId.
     *
     * <p>TODO: evaluate if a more comprehensive API is needed for this or not.
     *
     * @return id of the secondary user or -1 if one could not be found.
     * @throws DeviceNotAvailableException
     */
    private static int findExistingSecondary(ITestDevice device)
            throws DeviceNotAvailableException {
        for (int userId : device.listUsers()) {
            if (device.isUserSecondary(userId)) {
                return userId;
            }
        }
        // Returns a negative id if we couldn't find a proper existing secondary user.
        return -1;
    }
}
