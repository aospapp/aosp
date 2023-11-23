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

import static com.google.common.truth.Truth.assertThat;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.IDeviceTest;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test to check that AdExtServicesApk activities are enabled by AdExtBootCompletedReceiver on S
 *
 * <p>AdExtServicesApk activities are disabled by default so that there are no duplicate activities
 * on T+ devices. AdExtBootCompletedReceiver handles the BootCompleted initialization and changes
 * activities to enabled on Android S devices
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class AdExtServicesBootCompleteReceiverHostTest implements IDeviceTest {

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
    public void testExtBootCompleteReceiver() throws Exception {
        final int apiLevel = getDevice().getApiLevel();
        Assume.assumeTrue(apiLevel == 31 || apiLevel == 32 /* Build.VERSION_CODES.S or S_V2 */);
        ITestDevice device = getDevice();

        // reboot the device
        device.reboot();
        device.waitForDeviceAvailable();
        // Sleep 30s to wait for AdBootCompletedReceiver execution
        Thread.sleep(30 * 1000);

        String startActivityMsg =
                getDevice().executeShellCommand("am start -a android.adservices.ui.SETTINGS");
        assertThat(startActivityMsg)
                .doesNotContain("Error: Activity not started, unable to resolve Intent");
    }

    private void overrideCompatFlags() throws DeviceNotAvailableException {
        getDevice().executeShellCommand("device_config put adservices global_kill_switch false");
        getDevice().executeShellCommand("device_config put adservices adservice_enabled true");
        getDevice().executeShellCommand("device_config put adservices enable_back_compat true");
    }

    private void resetCompatFlags() throws DeviceNotAvailableException {
        getDevice().executeShellCommand("device_config delete adservices global_kill_switch");
        getDevice().executeShellCommand("device_config delete adservices adservice_enabled");
        getDevice().executeShellCommand("device_config delete adservices enable_back_compat");
    }
}
