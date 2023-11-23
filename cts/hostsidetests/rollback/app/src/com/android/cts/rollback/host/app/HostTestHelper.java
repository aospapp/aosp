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

package com.android.cts.rollback.host.app;

import static com.android.cts.rollback.lib.RollbackInfoSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.rollback.RollbackInfo;

import androidx.test.InstrumentationRegistry;

import com.android.cts.rollback.lib.Install;
import com.android.cts.rollback.lib.Rollback;
import com.android.cts.rollback.lib.TestApp;
import com.android.cts.rollback.lib.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;

/**
 * On-device helper test methods used for host-driven rollback tests.
 */
@RunWith(JUnit4.class)
public class HostTestHelper {

    /**
     * Adopts common permissions needed to test rollbacks.
     */
    @Before
    public void setup() throws InterruptedException, IOException {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
    }

    /**
     * Drops adopted shell permissions.
     */
    @After
    public void teardown() throws InterruptedException, IOException {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }


    /**
     * Test rollbacks of staged installs involving only apks.
     * Commits TestApp.A2 as a staged install with rollback enabled.
     */
    @Test
    public void testApkOnlyEnableRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(-1);

        Install.single(TestApp.A1).commit();
        Install.single(TestApp.A2).setStaged().setEnableRollback().commit();

        // At this point, the host test driver will reboot the device and run
        // testApkOnlyCommitRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Confirms a staged rollback is available for TestApp.A2 and commits the
     * rollback.
     */
    @Test
    public void testApkOnlyCommitRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        RollbackInfo available = Utils.getAvailableRollback(TestApp.A);
        assertThat(available).isStaged();
        assertThat(available).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(Utils.getCommittedRollback(TestApp.A)).isNull();

        Utils.rollback(available.getRollbackId(), TestApp.A2);
        RollbackInfo committed = Utils.getCommittedRollback(TestApp.A);
        assertThat(committed).hasRollbackId(available.getRollbackId());
        assertThat(committed).isStaged();
        assertThat(committed).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(committed).causePackagesContainsExactly(TestApp.A2);
        assertThat(committed.getCommittedSessionId()).isNotEqualTo(-1);

        // Note: The app is not rolled back until after the rollback is staged
        // and the device has been rebooted.
        Utils.waitForSessionReady(committed.getCommittedSessionId());
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(2);

        // At this point, the host test driver will reboot the device and run
        // testApkOnlyConfirmRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Confirms TestApp.A2 was rolled back.
     */
    @Test
    public void testApkOnlyConfirmRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(1);

        RollbackInfo committed = Utils.getCommittedRollback(TestApp.A);
        assertThat(committed).isStaged();
        assertThat(committed).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(committed).causePackagesContainsExactly(TestApp.A2);
        assertThat(committed.getCommittedSessionId()).isNotEqualTo(-1);
    }

    /**
     * Test rollbacks of staged installs involving only apex.
     * Install first version phase.
     *
     * <p> We can't rollback to version 1, which is already installed, so we start by installing
     * version 2. The test ultimately rolls back from 3 to 2.
     */
    @Test
    public void testApexOnlyInstallFirstVersion() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(1);

        Install.single(TestApp.Apex2).setStaged().commit();

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyEnableRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apex.
     * Enable rollback phase.
     */
    @Test
    public void testApexOnlyEnableRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(2);
        Install.single(TestApp.Apex3).setStaged().setEnableRollback().commit();

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyCommitRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apex.
     * Commit rollback phase.
     */
    @Test
    public void testApexOnlyCommitRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(3);
        RollbackInfo available = Utils.getAvailableRollback(TestApp.Apex);
        assertThat(available).isStaged();
        assertThat(available).packagesContainsExactly(
                Rollback.from(TestApp.Apex3).to(TestApp.Apex2));

        Utils.rollback(available.getRollbackId(), TestApp.Apex3);
        RollbackInfo committed = Utils.getCommittedRollbackById(available.getRollbackId());
        assertThat(committed).isNotNull();
        assertThat(committed).isStaged();
        assertThat(committed).packagesContainsExactly(
                Rollback.from(TestApp.Apex3).to(TestApp.Apex2));
        assertThat(committed).causePackagesContainsExactly(TestApp.Apex3);
        assertThat(committed.getCommittedSessionId()).isNotEqualTo(-1);

        // Note: The app is not rolled back until after the rollback is staged
        // and the device has been rebooted.
        Utils.waitForSessionReady(committed.getCommittedSessionId());
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(3);

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyConfirmRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apex.
     * Confirm rollback phase.
     */
    @Test
    public void testApexOnlyConfirmRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(2);

        // Rollback data for shim apex will remain in storage since the apex cannot be completely
        // removed and thus the rollback data won't be expired. Unfortunately, we can't also delete
        // the rollback data manually from storage.

        // At this point, the host test driver will reboot the device to complete the uninstall.
    }


    /**
     * Test rollbacks of staged installs involving apex and apk.
     * Install first version phase.
     *
     * <p> See {@link #testApexOnlyInstallFirstVersion()}
     */
    @Test
    public void testApexAndApkInstallFirstVersion() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(1);
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(-1);

        Install.multi(TestApp.Apex2, TestApp.A1).setStaged().commit();

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyEnableRollback().
    }

    /**
     * Test rollbacks of staged installs involving apex and apk.
     * Enable rollback phase.
     */
    @Test
    public void testApexAndApkEnableRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(2);
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        Install.multi(TestApp.Apex3, TestApp.A2).setStaged().setEnableRollback().commit();

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyCommitRollback().
    }

    /**
     * Test rollbacks of staged installs involving apex and apk.
     * Commit rollback phase.
     */
    @Test
    public void testApexAndApkCommitRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(3);
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        RollbackInfo available = Utils.getAvailableRollback(TestApp.Apex);
        assertThat(available).isStaged();
        assertThat(available).packagesContainsExactly(
                Rollback.from(TestApp.Apex3).to(TestApp.Apex2),
                Rollback.from(TestApp.A2).to(TestApp.A1));

        Utils.rollback(available.getRollbackId(), TestApp.Apex3, TestApp.A2);
        RollbackInfo committed = Utils.getCommittedRollback(TestApp.A);
        assertThat(committed).isNotNull();
        assertThat(committed).isStaged();
        assertThat(committed).packagesContainsExactly(
                Rollback.from(TestApp.Apex3).to(TestApp.Apex2),
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(committed).causePackagesContainsExactly(TestApp.Apex3, TestApp.A2);
        assertThat(committed.getCommittedSessionId()).isNotEqualTo(-1);

        // Note: The app is not rolled back until after the rollback is staged
        // and the device has been rebooted.
        Utils.waitForSessionReady(committed.getCommittedSessionId());
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(3);
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(2);

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyConfirmRollback().
    }

    /**
     * Test rollbacks of staged installs involving apex and apk.
     * Confirm rollback phase.
     */
    @Test
    public void testApexAndApkConfirmRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(2);

        RollbackInfo committed = Utils.getCommittedRollback(TestApp.A);
        assertThat(committed).isStaged();
        assertThat(committed).packagesContainsExactly(
                Rollback.from(TestApp.Apex3).to(TestApp.Apex2),
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(committed).causePackagesContainsExactly(TestApp.Apex3, TestApp.A2);
        assertThat(committed.getCommittedSessionId()).isNotEqualTo(-1);

        // Rollback data for shim apex will remain in storage since the apex cannot be completely
        // removed and thus the rollback data won't be expired. Unfortunately, we can't also delete
        // the rollback data manually from storage due to SEPolicy rules.

        // At this point, the host test driver will reboot the device to complete the uninstall.
    }

    /**
     * Tests that apex update expires existing rollbacks for that apex.
     * Enable rollback phase.
     */
    @Test
    public void testApexRollbackExpirationEnableRollback() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(1);

        Install.single(TestApp.Apex2).setStaged().setEnableRollback().commit();

        // At this point, the host test driver will reboot the device and run
        // testApexRollbackExpirationUpdateApex().
    }

    /**
     * Tests that apex update expires existing rollbacks for that apex.
     * Update apex phase.
     */
    @Test
    public void testApexRollbackExpirationUpdateApex() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(2);
        assertThat(Utils.getAvailableRollback(TestApp.Apex)).isNotNull();
        Install.single(TestApp.Apex3).setStaged().commit();

        // At this point, the host test driver will reboot the device and run
        // testApexRollbackExpirationConfirmExpiration().
    }

    /**
     * Tests that apex update expires existing rollbacks for that apex.
     * Confirm expiration phase.
     */
    @Test
    public void testApexRollbackExpirationConfirmExpiration() throws Exception {
        assertThat(Utils.getInstalledVersion(TestApp.Apex)).isEqualTo(3);
        assertThat(Utils.getAvailableRollback(TestApp.Apex)).isNull();
    }
}
