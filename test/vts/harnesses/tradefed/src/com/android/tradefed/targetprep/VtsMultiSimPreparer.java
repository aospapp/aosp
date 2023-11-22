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
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil;

/**
 * Preparer class for mSIM tests.
 *
 * <p>For devices with an mSIM capable modem, this preparer configures the modem to function in mSIM
 * mode so that all slots can be tested, and then reverts the modem back in teardown.
 */
public class VtsMultiSimPreparer extends BaseTargetPreparer implements ITargetCleaner {
    private ITestDevice mDevice;
    private int mOriginalSims;

    // Wait up to 60 seconds for the device to turn off
    private static final int SHUTDOWN_TIMEOUT_MS = 60000;

    @Override
    public void setUp(ITestDevice device, IBuildInfo buildInfo) throws DeviceNotAvailableException {
        mDevice = device;
        mOriginalSims = getNumSims();
        int maxSims = getMaxPhones();
        if (mOriginalSims != maxSims) {
            setSimCount(getMaxPhones());
        }
    }

    @Override
    public void tearDown(ITestDevice device, IBuildInfo buildInfo, Throwable throwable)
            throws DeviceNotAvailableException {
        if (mOriginalSims != getNumSims()) {
            setSimCount(mOriginalSims);
        }
    }

    private int getMaxPhones() throws DeviceNotAvailableException {
        try {
            return Integer.parseInt(executeCmd("telecom get-max-phones"));
        } catch (NumberFormatException e) {
            LogUtil.CLog.w("Cannot get max phones. Assuming 1.");
            return 1;
        }
    }

    private int getNumSims() throws DeviceNotAvailableException {
        String config = executeCmd("telecom get-sim-config");
        if ("SSSS".equals(config) || config.isEmpty()) {
            return 1;
        } else if ("DSDS".equals(config)) {
            return 2;
        } else if ("TSTS".equals(config)) {
            return 3;
        }
        LogUtil.CLog.w("Could not get SIM config, assuming 1 sim");
        return 1;
    }

    private void setSimCount(int sims) throws DeviceNotAvailableException {
        executeCmd("telecom set-sim-count " + sims);
        // Setting the SIM count triggers a reboot
        mDevice.waitForDeviceNotAvailable(SHUTDOWN_TIMEOUT_MS);
        mDevice.waitForDeviceAvailable();
    }

    private String executeCmd(String cmd) throws DeviceNotAvailableException {
        return mDevice.executeShellCommand(cmd).trim();
    }
}
