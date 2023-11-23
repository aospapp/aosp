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

package com.android.tests.atomicinstall;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Tests for multi-package (a.k.a. atomic) installs.
 */
@RunWith(JUnit4.class)
public class AtomicInstallTest {

    private static final String TEST_APP_A = "com.android.tests.atomicinstall.testapp.A";
    private static final String TEST_APP_B = "com.android.tests.atomicinstall.testapp.B";
    public static final String TEST_APP_A_FILENAME = "AtomicInstallTestAppAv1.apk";
    public static final String TEST_APP_A_V2_FILENAME = "AtomicInstallTestAppAv2.apk";
    public static final String TEST_APP_B_FILENAME = "AtomicInstallTestAppBv1.apk";
    public static final String TEST_APP_CORRUPT_FILENAME = "corrupt.apk";

    private void adoptShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES);
    }

    @Before
    public void setup() throws Exception {
        adoptShellPermissions();

        uninstall(TEST_APP_A);
        uninstall(TEST_APP_B);
    }

    @After
    public void dropShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testInstallTwoApks() throws Exception {
        installMultiPackage(TEST_APP_A_FILENAME, TEST_APP_B_FILENAME);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(1);
        assertThat(getInstalledVersion(TEST_APP_B)).isEqualTo(1);
    }

    @Test
    public void testInstallTwoApksDowngradeFail() throws Exception {
        installMultiPackage(TEST_APP_A_V2_FILENAME, TEST_APP_B_FILENAME);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(2);
        assertThat(getInstalledVersion(TEST_APP_B)).isEqualTo(1);

        final PackageInstaller.SessionParams parentSessionParams =
                createSessionParams(/*staged*/false, /*multipackage*/true,
                        /*enableRollback*/ false, /*inherit*/false);
        final int parentSessionId = createSessionId(/*apkFileName*/null, parentSessionParams);
        final PackageInstaller.Session parentSession = getSessionOrFail(parentSessionId);
        for (String apkFile : new String[]{
                TEST_APP_A_FILENAME, TEST_APP_B_FILENAME}) {
            final PackageInstaller.SessionParams childSessionParams =
                    createSessionParams(/*staged*/false, /*multipackage*/false,
                            /*enableRollback*/ false, /*inherit*/false);
            final int childSessionId =
                    createSessionId(apkFile, childSessionParams);
            parentSession.addChildSessionId(childSessionId);
        }
        parentSession.commit(LocalIntentSender.getIntentSender());

        final Intent intent = LocalIntentSender.getIntentSenderResult();
        assertStatusFailure(intent, "INSTALL_FAILED_VERSION_DOWNGRADE");
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(2);
        assertThat(getInstalledVersion(TEST_APP_B)).isEqualTo(1);
    }

    @Test
    public void testFailInconsistentMultiPackageCommit() throws Exception {
        // Test inconsistency in staged settings
        for (boolean staged : new boolean[]{false, true}) {
            final PackageInstaller.SessionParams parentSessionParams =
                    createSessionParams(/*staged*/staged, /*multipackage*/true,
                            /*enableRollback*/false, /*inherit*/false);
            final int parentSessionId =
                    createSessionId(/*apkFileName*/null, parentSessionParams);
            // Create a child session that differs in the staged parameter
            final PackageInstaller.SessionParams childSessionParams =
                    createSessionParams(/*staged*/!staged, /*multipackage*/false,
                            /*enableRollback*/false, /*inherit*/false);
            final int childSessionId =
                    createSessionId("AtomicInstallTestAppAv1.apk", childSessionParams);

            final PackageInstaller.Session parentSession = getSessionOrFail(parentSessionId);
            parentSession.addChildSessionId(childSessionId);
            parentSession.commit(LocalIntentSender.getIntentSender());
            assertStatusFailure(LocalIntentSender.getIntentSenderResult(),
                    "inconsistent staged settings");
            assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        }

        // Test inconsistency in rollback settings
        for (boolean enableRollback : new boolean[]{false, true}) {
            final PackageInstaller.SessionParams parentSessionParams =
                    createSessionParams(/*staged*/false, /*multipackage*/true,
                            /*enableRollback*/enableRollback, /*inherit*/false);
            final int parentSessionId =
                    createSessionId(/*apkFileName*/null, parentSessionParams);
            // Create a child session that differs in the staged parameter
            final PackageInstaller.SessionParams childSessionParams =
                    createSessionParams(/*staged*/false, /*multipackage*/false,
                            /*enableRollback*/!enableRollback, /*inherit*/false);
            final int childSessionId =
                    createSessionId("AtomicInstallTestAppAv1.apk", childSessionParams);

            final PackageInstaller.Session parentSession = getSessionOrFail(parentSessionId);
            parentSession.addChildSessionId(childSessionId);
            parentSession.commit(LocalIntentSender.getIntentSender());
            assertStatusFailure(LocalIntentSender.getIntentSenderResult(),
                    "inconsistent rollback settings");
            assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        }
    }

    @Test
    public void testChildFailurePropagated() throws Exception {
        final PackageInstaller.SessionParams parentSessionParams =
                createSessionParams(/*staged*/false, /*multipackage*/true,
                        /*enableRollback*/ false, /*inherit*/false);
        final int parentSessionId =
                createSessionId(/*apkFileName*/null, parentSessionParams);
        final PackageInstaller.Session parentSession = getSessionOrFail(parentSessionId);

        // Create a child session that "inherits" from a non-existent package. This
        // causes the session commit to fail with a PackageManagerException.
        final PackageInstaller.SessionParams childSessionParams =
                createSessionParams(/*staged*/false, /*multipackage*/false,
                        /*enableRollback*/ false, /*inherit*/true);
        final int childSessionId =
                createSessionId("AtomicInstallTestAppAv1.apk", childSessionParams);
        parentSession.addChildSessionId(childSessionId);
        parentSession.commit(LocalIntentSender.getIntentSender());

        final Intent intent = LocalIntentSender.getIntentSenderResult();
        assertStatusFailure(intent, "Missing existing base package");
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
    }

    @Test
    public void testEarlyFailureFailsAll() throws Exception {
        final PackageInstaller.SessionParams parentSessionParams =
                createSessionParams(/*staged*/false, /*multipackage*/true,
                        /*enableRollback*/ false, /*inherit*/false);
        final int parentSessionId = createSessionId(/*apkFileName*/null, parentSessionParams);
        final PackageInstaller.Session parentSession = getSessionOrFail(parentSessionId);
        for (String apkFile : new String[]{
                TEST_APP_A_FILENAME, TEST_APP_B_FILENAME, TEST_APP_CORRUPT_FILENAME}) {
            final PackageInstaller.SessionParams childSessionParams =
                    createSessionParams(/*staged*/false, /*multipackage*/false,
                            /*enableRollback*/ false, /*inherit*/false);
            final int childSessionId =
                    createSessionId(apkFile, childSessionParams);
            parentSession.addChildSessionId(childSessionId);
        }
        parentSession.commit(LocalIntentSender.getIntentSender());

        final Intent intent = LocalIntentSender.getIntentSenderResult();
        assertStatusFailure(intent, "Failed to parse");
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        assertThat(getInstalledVersion(TEST_APP_B)).isEqualTo(-1);
    }

    @Test
    public void testInvalidStateScenarios() throws Exception {
        final PackageInstaller.SessionParams parentSessionParams =
                createSessionParams(/*staged*/false, /*multipackage*/true,
                        /*enableRollback*/false, /*inherit*/ false);
        final int parentSessionId = createSessionId(/*apkFileName*/null, parentSessionParams);
        final PackageInstaller.Session parentSession = getSessionOrFail(parentSessionId);

        final PackageInstaller.SessionParams childSessionParams =
                createSessionParams(/*staged*/false, /*multipackage*/false,
                        /*enableRollback*/false, /*inherit*/ false);
        for (String apkFileName : new String[]{TEST_APP_A_FILENAME, TEST_APP_B_FILENAME}) {
            final int childSessionId = createSessionId(apkFileName, childSessionParams);
            parentSession.addChildSessionId(childSessionId);
            PackageInstaller.Session childSession = getSessionOrFail(childSessionId);
            try {
                childSession.commit(LocalIntentSender.getIntentSender());
                fail("Should not be able to commit a child session!");
            } catch (IllegalStateException e) {
                // ignore
            }
            try {
                childSession.abandon();
                fail("Should not be able to abandon a child session!");
            } catch (IllegalStateException e) {
                // ignore
            }
        }
        int toAbandonSessionId = createSessionId(TEST_APP_A_FILENAME, childSessionParams);
        PackageInstaller.Session toAbandonSession = getSessionOrFail(toAbandonSessionId);
        toAbandonSession.abandon();
        try {
            parentSession.addChildSessionId(toAbandonSessionId);
            fail("Should not be able to add abandoned child session!");
        } catch (RuntimeException e) {
            // ignore
        }

        // Commit the session (this will start the installation workflow).
        parentSession.commit(LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    private static long getInstalledVersion(String packageName) {
        Context context = InstrumentationRegistry.getContext();
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static void installMultiPackage(String... resources) throws Exception {
        final PackageInstaller.SessionParams parentSessionParams =
                createSessionParams(/*staged*/false, /*multipackage*/true,
                        /*enableRollback*/false, /*inherit*/ false);
        final int parentSessionId = createSessionId(/*apkFileName*/null, parentSessionParams);
        final PackageInstaller.Session parentSession = getSessionOrFail(parentSessionId);

        ArrayList<Integer> childSessionIds = new ArrayList<>(resources.length);
        for (String apkFileName : resources) {
            final PackageInstaller.SessionParams childSessionParams =
                    createSessionParams(/*staged*/false, /*multipackage*/false,
                            /*enableRollback*/false, /*inherit*/ false);
            final int childSessionId = createSessionId(apkFileName, childSessionParams);
            childSessionIds.add(childSessionId);
            parentSession.addChildSessionId(childSessionId);

            PackageInstaller.Session childSession = getSessionOrFail(childSessionId);
            assertThat(childSession.getParentSessionId()).isEqualTo(parentSessionId);
            assertThat(getSessionInfoOrFail(childSessionId).getParentSessionId())
                    .isEqualTo(parentSessionId);
        }
        assertThat(parentSession.getChildSessionIds()).asList().containsAllIn(childSessionIds);
        assertThat(getSessionInfoOrFail(parentSessionId).getChildSessionIds()).asList()
                .containsAllIn(childSessionIds);

        // Commit the session (this will start the installation workflow).
        parentSession.commit(LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    private static PackageInstaller.SessionParams createSessionParams(
            boolean staged, boolean multiPackage, boolean enableRollback, boolean inherit) {
        final int sessionMode = inherit
                ? PackageInstaller.SessionParams.MODE_INHERIT_EXISTING
                : PackageInstaller.SessionParams.MODE_FULL_INSTALL;
        final PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(sessionMode);
        if (staged) {
            params.setStaged();
        }
        if (multiPackage) {
            params.setMultiPackage();
        }
        params.setEnableRollback(enableRollback);
        return params;
    }

    private static int createSessionId(String apkFileName, PackageInstaller.SessionParams params)
            throws Exception {
        final PackageInstaller packageInstaller = InstrumentationRegistry.getContext()
                .getPackageManager().getPackageInstaller();
        final int sessionId = packageInstaller.createSession(params);
        if (apkFileName == null) {
            return sessionId;
        }
        final PackageInstaller.Session session = packageInstaller.openSession(sessionId);
        try (OutputStream packageInSession = session.openWrite(apkFileName, 0, -1);
            final InputStream is =
                AtomicInstallTest.class.getClassLoader().getResourceAsStream(
                    apkFileName)) {
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                packageInSession.write(buffer, 0, n);
            }
        }
        return sessionId;
    }

    private static PackageInstaller.Session getSessionOrFail(int sessionId) throws Exception {
        final PackageInstaller packageInstaller = InstrumentationRegistry.getContext()
                .getPackageManager().getPackageInstaller();
        return packageInstaller.openSession(sessionId);
    }

    private static PackageInstaller.SessionInfo getSessionInfoOrFail(int sessionId)
            throws Exception {
        final PackageInstaller packageInstaller = InstrumentationRegistry.getContext()
                .getPackageManager().getPackageInstaller();
        return packageInstaller.getSessionInfo(sessionId);
    }

    private static void assertStatusSuccess(Intent result) {
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == -1) {
            throw new AssertionError("PENDING USER ACTION");
        } else if (status > 0) {
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            throw new AssertionError(message == null ? "UNKNOWN FAILURE" : message);
        }
    }

    private static void assertStatusFailure(Intent result, String errorMessage) {
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE);
        if (status == -1) {
            throw new AssertionError("PENDING USER ACTION");
        } else if (status == 0) {
            throw new AssertionError("Installation unexpectedly succeeded!");
        }
        final String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (message == null || !message.contains(errorMessage)) {
            throw new AssertionError("Unexpected failure:" +
                    (message == null ? "UNKNOWN" : message));
        }
    }

    private static void uninstall(String packageName) throws Exception {
        // No need to uninstall if the package isn't installed.
        if (getInstalledVersion(packageName) == -1) {
            return;
        }

        final PackageInstaller packageInstaller = InstrumentationRegistry.getContext()
                .getPackageManager().getPackageInstaller();
        packageInstaller.uninstall(packageName, LocalIntentSender.getIntentSender());
        // Don't care about status; this is just cleanup
        LocalIntentSender.getIntentSenderResult();
    }
}
