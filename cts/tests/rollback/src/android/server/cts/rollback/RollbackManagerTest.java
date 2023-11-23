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

package com.android.cts.rollback;

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
 * CTS Tests for RollbackManager APIs.
 */
@RunWith(JUnit4.class)
public class RollbackManagerTest {

    /**
     * Adopts common permissions needed to test rollbacks and uninstalls the
     * test apps.
     */
    @Before
    public void setup() throws InterruptedException, IOException {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

        Utils.uninstall(TestApp.A);
    }

    /**
     * Drops adopted shell permissions and uninstalls the test apps.
     */
    @After
    public void teardown() throws InterruptedException, IOException {
        Utils.uninstall(TestApp.A);

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    /**
     * Test basic rollbacks.
     */
    @Test
    public void testBasic() throws Exception {
        Install.single(TestApp.A1).commit();
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        assertThat(Utils.getAvailableRollback(TestApp.A)).isNull();
        assertThat(Utils.getCommittedRollback(TestApp.A)).isNull();

        Install.single(TestApp.A2).setEnableRollback().commit();
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        RollbackInfo available = Utils.getAvailableRollback(TestApp.A);
        assertThat(available).isNotNull();
        assertThat(available).isNotStaged();
        assertThat(available).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(Utils.getCommittedRollback(TestApp.A)).isNull();

        Utils.rollback(available.getRollbackId(), TestApp.A2);
        assertThat(Utils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        assertThat(Utils.getAvailableRollback(TestApp.A)).isNull();
        RollbackInfo committed = Utils.getCommittedRollback(TestApp.A);
        assertThat(committed).isNotNull();
        assertThat(committed).hasRollbackId(available.getRollbackId());
        assertThat(committed).isNotStaged();
        assertThat(committed).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(committed).causePackagesContainsExactly(TestApp.A2);
    }
}
