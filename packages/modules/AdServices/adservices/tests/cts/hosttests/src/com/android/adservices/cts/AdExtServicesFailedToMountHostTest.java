/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.adservices.cts;

import com.android.ddmlib.MultiLineReceiver;
import com.android.tradefed.device.BackgroundDeviceAction;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.IDeviceTest;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

/** Test to check if com.google.android.ext.adservices.api failed to mount */
@RunWith(DeviceJUnit4ClassRunner.class)
public class AdExtServicesFailedToMountHostTest implements IDeviceTest {

    private static String sWildcardString = ".*";
    private static String sExtservicesString = "com\\.google\\.android\\.ext\\.adservices\\.api";
    private static String sFailedToMountString = "Failed to mount";
    private static String sNoSuchString = "No such file or directory";

    private ITestDevice mDevice;

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    @Before
    public void setUp() throws Exception {
        overrideCompatFlags();
    }

    @After
    public void tearDown() throws Exception {
        resetCompatFlags();
    }

    @Test
    public void testLogcatDoesNotContainError() throws Exception {
        final int apiLevel = getDevice().getApiLevel();
        Assume.assumeTrue(
                apiLevel == 30
                        || apiLevel == 31
                        || apiLevel == 32 /* Build.VERSION_CODES.R or S/S_V2 */);
        ITestDevice device = getDevice();

        // reboot the device
        device.reboot();
        device.waitForDeviceAvailable();

        AdservicesLogcatReceiver logcatReceiver =
                new AdservicesLogcatReceiver("receiver", " logcat");
        logcatReceiver.start(device);

        // Sleep 5 min to allow time for the error to occur
        Thread.sleep(300 * 1000);
        logcatReceiver.stop();

        Pattern errorPattern =
                Pattern.compile(
                        sExtservicesString
                                + sWildcardString
                                + sFailedToMountString
                                + sWildcardString
                                + sNoSuchString);
        Assert.assertFalse(logcatReceiver.patternMatches(errorPattern));
    }

    private void overrideCompatFlags() throws Exception {
        getDevice().executeShellCommand("device_config put adservices global_kill_switch false");
        getDevice().executeShellCommand("device_config put adservices adservice_enabled true");
        getDevice().executeShellCommand("device_config put adservices enable_back_compat true");
    }

    private void resetCompatFlags() throws Exception {
        getDevice().executeShellCommand("device_config delete adservices global_kill_switch");
        getDevice().executeShellCommand("device_config delete adservices adservice_enabled");
        getDevice().executeShellCommand("device_config delete adservices enable_back_compat");
    }

    // TODO: b/288892905 consolidate with existing logcat receiver
    private static class AdservicesLogcatReceiver extends MultiLineReceiver {
        private volatile boolean mCancelled = false;
        private final StringBuilder mBuilder = new StringBuilder();
        private final String mName;
        private final String mLogcatCmd;
        private BackgroundDeviceAction mBackgroundDeviceAction;

        AdservicesLogcatReceiver(String name, String logcatCmd) {
            this.mName = name;
            this.mLogcatCmd = logcatCmd;
        }

        @Override
        public void processNewLines(String[] lines) {
            if (lines.length == 0) {
                return;
            }
            mBuilder.append(String.join("\n", lines));
        }

        @Override
        public boolean isCancelled() {
            return mCancelled;
        }

        public void start(ITestDevice device) {
            mBackgroundDeviceAction =
                    new BackgroundDeviceAction(mLogcatCmd, mName, device, this, 0);
            mBackgroundDeviceAction.start();
        }

        public void stop() {
            if (mBackgroundDeviceAction != null) mBackgroundDeviceAction.cancel();
            if (isCancelled()) return;
            mCancelled = true;
        }

        public boolean patternMatches(Pattern pattern) {
            return mBuilder.length() > 0 && pattern.matcher(mBuilder).find();
        }
    }
}
