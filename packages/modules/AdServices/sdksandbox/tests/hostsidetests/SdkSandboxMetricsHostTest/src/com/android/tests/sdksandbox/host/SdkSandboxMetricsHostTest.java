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

package com.android.tests.sdksandbox.host;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import com.android.modules.utils.build.testing.DeviceSdkLevel;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class SdkSandboxMetricsHostTest extends BaseHostJUnit4Test {

    private static final String METRICS_TEST_APP_PACKAGE = "com.android.tests.sdksandbox";
    private static final String METRICS_TEST_APP_APK = "SdkSandboxMetricsTestApp.apk";

    private DeviceSdkLevel mDeviceSdkLevel;

    @Before
    public void setUp() throws DeviceNotAvailableException, TargetSetupError {
        mDeviceSdkLevel = new DeviceSdkLevel(getDevice());
        installPackage(METRICS_TEST_APP_APK);
    }

    @After
    public void tearDown() throws DeviceNotAvailableException {
        uninstallPackage(METRICS_TEST_APP_PACKAGE);
    }

    @Test
    public void testSdkCanAccessSdkSandboxExitReasons() throws Exception {
        assumeTrue(mDeviceSdkLevel.isDeviceAtLeastU());
        runPhase("testSdkCanAccessSdkSandboxExitReasons");
    }

    @Test
    public void testAppWithDumpPermissionCanAccessSdkSandboxExitReasons() throws Exception {
        assumeTrue(mDeviceSdkLevel.isDeviceAtLeastU());
        runPhase("testAppWithDumpPermissionCanAccessSdkSandboxExitReasons");
    }

    @Test
    public void testSdkSandboxCrashGeneratesDropboxReport() throws Exception {
        assumeTrue(mDeviceSdkLevel.isDeviceAtLeastU());
        runPhase("testCrashSandboxGeneratesDropboxReport");
    }
    /**
     * Runs the given phase of a test by calling into the device. Throws an exception if the test
     * phase fails.
     *
     * <p>For example, <code>runPhase("testExample");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertThat(
                        runDeviceTests(
                                METRICS_TEST_APP_PACKAGE,
                                METRICS_TEST_APP_PACKAGE + ".SdkSandboxMetricsTestApp",
                                phase))
                .isTrue();
    }
}
