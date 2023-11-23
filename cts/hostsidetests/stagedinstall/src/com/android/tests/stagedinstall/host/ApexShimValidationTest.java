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
 * limitations under the License
 */

package com.android.tests.stagedinstall.host;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeThat;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests to validate that only what is considered a correct shim apex can be installed.
 *
 * <p>Shim apex is considered correct iff:
 * <ul>
 *     <li>It doesn't have any pre or post install hooks.</li>
 *     <li>It's {@code apex_payload.img} contains only a regular text file called
 *         {@code hash.txt}.</li>
 *     <li>It's {@code sha512} hash is whitelisted in the {@code hash.txt} of pre-installed on the
 *         {@code /system} partition shim apex.</li>
 * </ul>
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class ApexShimValidationTest extends BaseHostJUnit4Test {

    private static final String SHIM_APEX_PACKAGE_NAME = "com.android.apex.cts.shim";

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testInstallStagedApkCommit");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertThat(runDeviceTests("com.android.tests.stagedinstall",
                "com.android.tests.stagedinstall.ApexShimValidationTest",
                phase)).isTrue();
    }

    private void cleanUp() throws Exception {
        assertThat(runDeviceTests("com.android.tests.stagedinstall",
                "com.android.tests.stagedinstall.StagedInstallTest",
                "cleanUp")).isTrue();
    }

    @Before
    public void setUp() throws Exception {
        final String updatable = getDevice().getProperty("ro.apex.updatable");
        assumeThat("Device doesn't support updating APEX", updatable, CoreMatchers.equalTo("true"));
        cleanUp();
    }

    @After
    public void tearDown() throws Exception {
        cleanUp();
    }

    @Test
    public void testShimApexIsPreInstalled() throws Exception {
        boolean isShimApexPreInstalled =
                getDevice().getActiveApexes().stream().anyMatch(
                        apex -> apex.name.equals(SHIM_APEX_PACKAGE_NAME));
        assertWithMessage("Shim APEX is not pre-installed").that(
                isShimApexPreInstalled).isTrue();
    }

    @Test
    public void testRejectsApexWithAdditionalFile() throws Exception {
        runPhase("testRejectsApexWithAdditionalFile_Commit");
        getDevice().reboot();
        runPhase("testInstallRejected_VerifyPostReboot");
    }

    @Test
    public void testRejectsApexWithAdditionalFolder() throws Exception {
        runPhase("testRejectsApexWithAdditionalFolder_Commit");
        getDevice().reboot();
        runPhase("testInstallRejected_VerifyPostReboot");
    }

    @Test
    public void testRejectsApexWithPostInstallHook() throws Exception {
        runPhase("testRejectsApexWithPostInstallHook_Commit");
        getDevice().reboot();
        runPhase("testInstallRejected_VerifyPostReboot");
    }

    @Test
    public void testRejectsApexWithPreInstallHook() throws Exception {
        runPhase("testRejectsApexWithPreInstallHook_Commit");
        getDevice().reboot();
        runPhase("testInstallRejected_VerifyPostReboot");
    }

    @Test
    public void testRejectsApexWrongSHA() throws Exception {
        runPhase("testRejectsApexWrongSHA_Commit");
        getDevice().reboot();
        runPhase("testInstallRejected_VerifyPostReboot");
    }
}
