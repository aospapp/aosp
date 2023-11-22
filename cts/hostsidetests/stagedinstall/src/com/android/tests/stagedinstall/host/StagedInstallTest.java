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

package com.android.tests.stagedinstall.host;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.android.ddmlib.Log;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedInstallTest extends BaseHostJUnit4Test {

    private static final String TAG = "StagedInstallTest";

    private static final String SHIM_APEX_PACKAGE_NAME = "com.android.apex.cts.shim";

    @Rule
    public final FailedTestLogHook mFailedTestLogHook = new FailedTestLogHook(this);

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testInstallStagedApkCommit");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertThat(runDeviceTests("com.android.tests.stagedinstall",
                "com.android.tests.stagedinstall.StagedInstallTest",
                phase)).isTrue();
    }

    @Before
    public void setUp() throws Exception {
        runPhase("cleanUp");
        uninstallShimApexIfNecessary();
    }

    @After
    public void tearDown() throws Exception {
        runPhase("cleanUp");
        uninstallShimApexIfNecessary();
    }

    /**
     * Tests staged install involving only one apk.
     */
    @Test
    public void testInstallStagedApk() throws Exception {
        runPhase("testInstallStagedApk_Commit");
        getDevice().reboot();
        runPhase("testInstallStagedApk_VerifyPostReboot");
        runPhase("testInstallStagedApk_AbandonSessionIsNoop");
    }

    @Test
    public void testFailInstallIfNoPermission() throws Exception {
        runPhase("testFailInstallIfNoPermission");
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_BothSinglePackage() throws Exception {
        runPhase("testFailInstallAnotherSessionAlreadyInProgress_BothSinglePackage");
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_SinglePackageMultiPackage() throws Exception {
        runPhase("testFailInstallAnotherSessionAlreadyInProgress_SinglePackageMultiPackage");
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_MultiPackageSinglePackage() throws Exception {
        runPhase("testFailInstallAnotherSessionAlreadyInProgress_MultiPackageSinglePackage");
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_BothMultiPackage() throws Exception {
        runPhase("testFailInstallAnotherSessionAlreadyInProgress_BothMultiPackage");
    }

    @Test
    public void testAbandonStagedApkBeforeReboot() throws Exception {
        runPhase("testAbandonStagedApkBeforeReboot_CommitAndAbandon");
        getDevice().reboot();
        runPhase("testAbandonStagedApkBeforeReboot_VerifyPostReboot");
    }

    @Test
    public void testInstallMultipleStagedApks() throws Exception {
        runPhase("testInstallMultipleStagedApks_Commit");
        getDevice().reboot();
        runPhase("testInstallMultipleStagedApks_VerifyPostReboot");
    }

    @Test
    public void testGetActiveStagedSession() throws Exception {
        runPhase("testGetActiveStagedSession");
    }

    @Test
    public void testGetActiveStagedSessionNoSessionActive() throws Exception {
        runPhase("testGetActiveStagedSessionNoSessionActive");
    }

    @Test
    public void getGetActiveStagedSession_MultiApkSession() throws Exception {
        runPhase("testGetGetActiveStagedSession_MultiApkSession");
    }

    @Test
    public void testStagedInstallDowngrade_DowngradeNotRequested_Fails() throws Exception {
        runPhase("testStagedInstallDowngrade_DowngradeNotRequested_Fails_Commit");
    }

    @Test
    public void testStagedInstallDowngrade_DowngradeRequested_DebugBuild() throws Exception {
        assumeThat(getDevice().getBuildFlavor(), not(endsWith("-user")));

        runPhase("testStagedInstallDowngrade_DowngradeRequested_Commit");
        getDevice().reboot();
        runPhase("testStagedInstallDowngrade_DowngradeRequested_DebugBuild_VerifyPostReboot");
    }

    @Test
    public void testStagedInstallDowngrade_DowngradeRequested_UserBuild() throws Exception {
        assumeThat(getDevice().getBuildFlavor(), endsWith("-user"));
        runPhase("testStagedInstallDowngrade_DowngradeRequested_Fails_Commit");
    }

    @Test
    public void testShimApexShouldPreInstalledIfUpdatingApexIsSupported() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        final ITestDevice.ApexInfo shimApex = getShimApex();
        assertThat(shimApex.versionCode).isEqualTo(1);
    }

    @Test
    public void testInstallStagedApex() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        runPhase("testInstallStagedApex_Commit");
        getDevice().reboot();
        runPhase("testInstallStagedApex_VerifyPostReboot");
    }

    @Test
    public void testInstallStagedApexAndApk() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        runPhase("testInstallStagedApexAndApk_Commit");
        getDevice().reboot();
        runPhase("testInstallStagedApexAndApk_VerifyPostReboot");
    }

    @Test
    public void testsFailsNonStagedApexInstall() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        runPhase("testsFailsNonStagedApexInstall");
    }

    @Test
    public void testInstallStagedNonPreInstalledApex_Fails() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        runPhase("testInstallStagedNonPreInstalledApex_Fails");
    }

    @Test
    public void testStageApkWithSameNameAsApexShouldFail() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        runPhase("testStageApkWithSameNameAsApexShouldFail_Commit");
        getDevice().reboot();
        runPhase("testStageApkWithSameNameAsApexShouldFail_VerifyPostReboot");
    }

    @Test
    public void testNonStagedInstallApkWithSameNameAsApexShouldFail() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());
        runPhase("testNonStagedInstallApkWithSameNameAsApexShouldFail");
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeNotRequested_Fails() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        installV3Apex();
        runPhase("testStagedInstallDowngradeApex_DowngradeNotRequested_Fails_Commit");
        getDevice().reboot();
        runPhase("testStagedInstallDowngradeApex_DowngradeNotRequested_Fails_VerifyPostReboot");
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeRequested_DebugBuild() throws Exception {
        assumeThat(getDevice().getBuildFlavor(), not(endsWith("-user")));
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        installV3Apex();
        runPhase("testStagedInstallDowngradeApex_DowngradeRequested_DebugBuild_Commit");
        getDevice().reboot();
        runPhase("testStagedInstallDowngradeApex_DowngradeRequested_DebugBuild_VerifyPostReboot");
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeRequested_UserBuild_Fails()
            throws Exception {
        assumeThat(getDevice().getBuildFlavor(), endsWith("-user"));
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        installV3Apex();
        runPhase("testStagedInstallDowngradeApex_DowngradeRequested_UserBuild_Fails_Commit");
        getDevice().reboot();
        runPhase("testStagedInstallDowngradeApex_DowngradeRequested_UserBuild_Fails_"
                + "VerifyPostReboot");
    }

    @Test
    public void testInstallStagedApex_SameGrade() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());

        installV3Apex();
        installV3Apex();
    }

    @Test
    public void testInstallApex_DeviceDoesNotSupportApex_Fails() throws Exception {
        assumeFalse("Device supports updating APEX", isUpdatingApexSupported());

        runPhase("testInstallApex_DeviceDoesNotSupportApex_Fails");
    }

    private void installV3Apex()throws Exception {
        runPhase("testInstallV3Apex_Commit");
        getDevice().reboot();
        runPhase("testInstallV3Apex_VerifyPostReboot");
    }

    @Test
    public void testFailsInvalidApexInstall() throws Exception {
        assumeTrue("Device does not support updating APEX", isUpdatingApexSupported());
        runPhase("testFailsInvalidApexInstall_Commit");
        runPhase("testFailsInvalidApexInstall_AbandonSessionIsNoop");
    }

    private boolean isUpdatingApexSupported() throws Exception {
        final String updatable = getDevice().getProperty("ro.apex.updatable");
        return updatable != null && updatable.equals("true");
    }

    @Test
    public void testStagedApkSessionCallbacks() throws Exception {
        runPhase("testStagedApkSessionCallbacks");
    }

    /**
     * Uninstalls a shim apex only if it's latest version is installed on /data partition (i.e.
     * it has a version higher than {@code 1}).
     *
     * <p>This is purely to optimize tests run time. Since uninstalling an apex requires a reboot,
     * and only a small subset of tests successfully install an apex, this code avoids ~10
     * unnecessary reboots.
     */
    private void uninstallShimApexIfNecessary() throws Exception {
        if (!isUpdatingApexSupported()) {
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
        Log.i(TAG, "Uninstalling shim apex " + shimApex);
        if (errorMessage != null) {
            throw new AssertionError("Failed to uninstall " + shimApex);
        }
        getDevice().reboot();
        assertThat(getShimApex().versionCode).isEqualTo(1L);
    }

    private ITestDevice.ApexInfo getShimApex() throws DeviceNotAvailableException {
        return getDevice().getActiveApexes().stream().filter(
                apex -> apex.name.equals(SHIM_APEX_PACKAGE_NAME)).findAny().orElseThrow(
                () -> new AssertionError("Can't find " + SHIM_APEX_PACKAGE_NAME));
    }

    private static final class FailedTestLogHook extends TestWatcher {

        private final BaseHostJUnit4Test mInstance;
        private String mStagedSessionsBeforeTest;

        private FailedTestLogHook(BaseHostJUnit4Test instance) {
            this.mInstance = instance;
        }

        @Override
        protected void failed(Throwable e, Description description) {
            String stagedSessionsAfterTest = getStagedSessions();
            Log.e(TAG, "Test " + description + " failed.\n"
                    + "Staged sessions before test started:\n" + mStagedSessionsBeforeTest + "\n"
                    + "Staged sessions after test failed:\n" + stagedSessionsAfterTest);
        }

        @Override
        protected void starting(Description description) {
            mStagedSessionsBeforeTest = getStagedSessions();
        }

        private String getStagedSessions() {
            try {
                return mInstance.getDevice().executeShellV2Command("pm get-stagedsessions").getStdout();
            } catch (DeviceNotAvailableException e) {
                Log.e(TAG, e);
                return "Failed to get staged sessions";
            }
        }

    }
}
