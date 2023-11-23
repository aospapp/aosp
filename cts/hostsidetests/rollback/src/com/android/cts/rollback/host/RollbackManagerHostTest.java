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

package com.android.cts.rollback.host;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * CTS host tests for RollbackManager APIs.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class RollbackManagerHostTest extends BaseHostJUnit4Test {

    private static final String SHIM_APEX_PACKAGE_NAME = "com.android.apex.cts.shim";
    private static final String TEST_APK_PACKAGE_NAME = "com.android.cts.rollback.lib.testapp.A";

    /**
     * Runs the helper app test method on device.
     * Throws an exception if the test method fails.
     * <p>
     * For example, <code>run("testApkOnlyEnableRollback");</code>
     */
    private void run(String method) throws Exception {
        assertThat(runDeviceTests("com.android.cts.rollback.host.app",
                "com.android.cts.rollback.host.app.HostTestHelper",
                method)).isTrue();
    }

    /**
     * Return {@code true} if and only if device supports updating apex.
     */
    private boolean isApexUpdateSupported() throws Exception {
        return "true".equals(getDevice().getProperty("ro.apex.updatable"));
    }

    /**
     * Uninstalls a shim apex only if it's latest version is installed on /data partition (i.e.
     * it has a version higher than {@code 1}).
     *
     * <p>This is purely to optimize tests run time, since uninstalling an apex requires a reboot.
     */
    private void uninstallShimApexIfNecessary() throws Exception {
        if (!isApexUpdateSupported()) {
            // Device doesn't support updating apex. Nothing to uninstall.
            return;
        }
        final ITestDevice.ApexInfo shimApex = getShimApex();
        if (shimApex.versionCode == 1) {
            // System version is active, skipping uninstalling active apex and rebooting the device.
            return;
        }
        // Non system version is active, need to uninstall it and reboot the device.
        final String errorMessage = getDevice().uninstallPackage(SHIM_APEX_PACKAGE_NAME);
        if (errorMessage != null) {
            throw new AssertionError("Failed to uninstall " + shimApex);
        }
        getDevice().reboot();
        assertThat(getShimApex().versionCode).isEqualTo(1L);
    }

    /**
     * Get {@link ITestDevice.ApexInfo} for the installed shim apex.
     */
    private ITestDevice.ApexInfo getShimApex() throws DeviceNotAvailableException {
        return getDevice().getActiveApexes().stream().filter(
                apex -> apex.name.equals(SHIM_APEX_PACKAGE_NAME)).findAny().orElseThrow(
                () -> new AssertionError("Can't find " + SHIM_APEX_PACKAGE_NAME));
    }

    /**
     * Uninstalls any version greater than 1 of shim apex and reboots the device if necessary
     * to complete the uninstall.
     *
     * <p>This is needed because the apex cannot be deleted using PackageInstaller API.
     */
    @After
    public void tearDown() throws Exception {
        // uninstalling the APK doesn't have much overhead, so we can do it after every case
        getDevice().uninstallPackage(TEST_APK_PACKAGE_NAME);
        uninstallShimApexIfNecessary();
    }

    /**
     * Tests staged rollbacks involving only apks.
     */
    @Test
    public void testApkOnlyStagedRollback() throws Exception {
        run("testApkOnlyEnableRollback");
        getDevice().reboot();
        run("testApkOnlyCommitRollback");
        getDevice().reboot();
        run("testApkOnlyConfirmRollback");
    }

    /**
     * Tests staged rollbacks involving only apex.
     */
    @Test
    public void testApexOnlyStagedRollback() throws Exception {
        assumeTrue("Device does not support updating APEX", isApexUpdateSupported());

        run("testApexOnlyInstallFirstVersion");
        getDevice().reboot();
        run("testApexOnlyEnableRollback");
        getDevice().reboot();
        run("testApexOnlyCommitRollback");
        getDevice().reboot();
        run("testApexOnlyConfirmRollback");
    }

    /**
     * Tests staged rollbacks involving only apex.
     */
    @Test
    public void testApexAndApkStagedRollback() throws Exception {
        assumeTrue("Device does not support updating APEX", isApexUpdateSupported());

        run("testApexAndApkInstallFirstVersion");
        getDevice().reboot();
        run("testApexAndApkEnableRollback");
        getDevice().reboot();
        run("testApexAndApkCommitRollback");
        getDevice().reboot();
        run("testApexAndApkConfirmRollback");
    }

    /**
     * Tests that apex update expires existing rollbacks for that apex.
     */
    @Test
    public void testApexRollbackExpiration() throws Exception {
        assumeTrue("Device does not support updating APEX", isApexUpdateSupported());

        uninstallShimApexIfNecessary();
        run("testApexRollbackExpirationEnableRollback");
        getDevice().reboot();
        run("testApexRollbackExpirationUpdateApex");
        getDevice().reboot();
        run("testApexRollbackExpirationConfirmExpiration");
    }

}
