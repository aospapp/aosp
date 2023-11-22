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

package com.android.tradefed.targetprep;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.UserUtil;

/**
 * A {@link ITargetPreparer} that switches to the specified user kind in setUp. By default it
 * remains in the current user, and no switching is performed.
 *
 * <p>Tries to restore device user state by switching back to the pre-execution current user.
 */
@OptionClass(alias = "switch-user-target-preparer")
public class SwitchUserTargetPreparer extends BaseTargetPreparer implements ITargetCleaner {
    @Option(
        name = "user-type",
        description = "The type of user to switch to before the module run."
    )
    private UserUtil.UserType mUserToSwitchTo = UserUtil.UserType.CURRENT;

    private int mPreExecutionCurrentUser;

    @Override
    public void setUp(ITestDevice device, IBuildInfo buildInfo)
            throws TargetSetupError, DeviceNotAvailableException {

        mPreExecutionCurrentUser = device.getCurrentUser();

        try {
            UserUtil.switchToUserType(device, mUserToSwitchTo);
        } catch (UserUtil.UserSwitchFailedException err) {
            throw new TargetSetupError(
                    String.format("Failed switch to user type %s", mUserToSwitchTo),
                    err,
                    device.getDeviceDescriptor());
        }

        CLog.d("Successfully switched to user type %s", mUserToSwitchTo);
    }

    @Override
    public void tearDown(ITestDevice device, IBuildInfo buildInfo, Throwable e)
            throws DeviceNotAvailableException {
        // Restore the previous user as the foreground.
        if (device.switchUser(mPreExecutionCurrentUser)) {
            CLog.d("Successfully switched back to user id: %d", mPreExecutionCurrentUser);
        } else {
            CLog.w("Could not switch back to the user id: %d", mPreExecutionCurrentUser);
        }
    }
}
