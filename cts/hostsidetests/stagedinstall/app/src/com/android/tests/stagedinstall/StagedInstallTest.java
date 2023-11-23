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

package com.android.tests.stagedinstall;

import static com.android.tests.stagedinstall.PackageInstallerSessionInfoSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Pair;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * This series of tests are meant to be driven by a host, since some of the interactions being
 * tested require the device to be rebooted, and some assertions to be verified post-reboot.
 * The convention used here (not enforced) is that the test methods in this file will be named
 * the same way as the test methods in the "host" class (see e.g.
 * {@code com.android.test.stagedinstall.host.StagedInstallTest}), with an optional suffix preceded
 * by an underscore, in case of multiple phases.
 * Example:
 * - In {@code com.android.test.stagedinstall.host.StagedInstallTest}:
 *
 * @Test
 * public void testInstallStagedApk() throws Exception {
 *  runPhase("testInstallStagedApk_Commit");
 *  getDevice().reboot();
 *  runPhase("testInstallStagedApk_VerifyPostReboot");
 * }
 * - In this class:
 * @Test public void testInstallStagedApk_Commit() throws Exception;
 * @Test public void testInstallStagedApk_VerifyPostReboot() throws Exception;
 */
@RunWith(JUnit4.class)
public class StagedInstallTest {

    private static final String TAG = "StagedInstallTest";

    private static final String TEST_APP_A = "com.android.tests.stagedinstall.testapp.A";
    private static final String TEST_APP_B = "com.android.tests.stagedinstall.testapp.B";
    private static final String SHIM_APEX_PACKAGE_NAME = "com.android.apex.cts.shim";
    private static final String NOT_PRE_INSTALLED_SHIM_APEX_PACKAGE_NAME =
            "com.android.apex.cts.shim_not_pre_installed";

    private File mTestStateFile = new File(
            InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(),
            "ctsstagedinstall_state");

    private static final Duration WAIT_FOR_SESSION_REMOVED_TTL = Duration.ofSeconds(10);
    private static final Duration SLEEP_DURATION = Duration.ofMillis(200);

    @Before
    public void adoptShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        Manifest.permission.INSTALL_PACKAGES,
                        Manifest.permission.DELETE_PACKAGES);
    }

    @After
    public void dropShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Before
    public void clearBroadcastReceiver() {
        SessionUpdateBroadcastReceiver.sessionBroadcasts.clear();
    }

    // This is marked as @Test to take advantage of @Before/@After methods of this class. Actual
    // purpose of this method to be called before and after each test case of
    // com.android.test.stagedinstall.host.StagedInstallTest to reduce tests flakiness.
    @Test
    public void cleanUp() throws Exception {
        PackageInstaller packageInstaller = getPackageInstaller();
        List<PackageInstaller.SessionInfo> stagedSessions = packageInstaller.getStagedSessions();
        for (PackageInstaller.SessionInfo sessionInfo : stagedSessions) {
            try {
                Log.i(TAG, "abandoning session " + sessionInfo.getSessionId());
                packageInstaller.abandonSession(sessionInfo.getSessionId());
            } catch (Exception e) {
                Log.e(TAG, "Failed to abandon session " + sessionInfo.getSessionId(), e);
            }
        }
        uninstall(TEST_APP_A);
        uninstall(TEST_APP_B);
        Files.deleteIfExists(mTestStateFile.toPath());
    }

    @Test
    public void testFailInstallIfNoPermission() throws Exception {
        dropShellPermissions();
        try {
            createStagedSession();
            fail(); // Should have thrown SecurityException.
        } catch (SecurityException e) {
            // This would be a better version, but it requires a version of truth not present in the
            // tree yet.
            // assertThat(e).hasMessageThat().containsMatch(...);
            assertThat(e.getMessage()).containsMatch(
                    "Neither user [0-9]+ nor current process has "
                    + "android.permission.INSTALL_PACKAGES");
        }
    }

    @Test
    public void testInstallStagedApk_Commit() throws Exception {
        int sessionId = stageSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
    }

    @Test
    public void testInstallStagedApk_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(1);
    }

    @Test
    public void testInstallStagedApk_AbandonSessionIsNoop() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        // Session is in a final state. Test that abandoning the session doesn't remove it from the
        // session database.
        getPackageInstaller().abandonSession(sessionId);
        assertSessionApplied(sessionId);
    }

    @Test
    public void testInstallMultipleStagedApks_Commit() throws Exception {
        int sessionId = stageMultipleApks(
                "StagedInstallTestAppAv1.apk",
                "StagedInstallTestAppBv1.apk")
                .assertSuccessful().getSessionId();
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        assertThat(getInstalledVersion(TEST_APP_B)).isEqualTo(-1);
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        assertThat(getInstalledVersion(TEST_APP_B)).isEqualTo(-1);
    }

    @Test
    public void testInstallMultipleStagedApks_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(1);
        assertThat(getInstalledVersion(TEST_APP_B)).isEqualTo(1);
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_BothSinglePackage()
            throws Exception {
        int sessionId = stageSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        StageSessionResult failedSessionResult = stageSingleApk("StagedInstallTestAppAv1.apk");
        assertThat(failedSessionResult.getErrorMessage()).contains(
                "There is already in-progress committed staged session");
        getPackageInstaller().abandonSession(sessionId);
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_SinglePackageMultiPackage()
            throws Exception {
        int sessionId = stageSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        StageSessionResult failedSessionResult = stageMultipleApks(
                "StagedInstallTestAppAv1.apk",
                "StagedInstallTestAppBv1.apk");
        assertThat(failedSessionResult.getErrorMessage()).contains(
                "There is already in-progress committed staged session");
        getPackageInstaller().abandonSession(sessionId);
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_MultiPackageSinglePackage()
            throws Exception {
        int sessionId = stageMultipleApks(
                "StagedInstallTestAppAv1.apk",
                "StagedInstallTestAppBv1.apk").assertSuccessful().getSessionId();
        StageSessionResult failedSessionResult = stageSingleApk(
                "StagedInstallTestAppAv1.apk");
        assertThat(failedSessionResult.getErrorMessage()).contains(
                "There is already in-progress committed staged session");
        getPackageInstaller().abandonSession(sessionId);
    }

    @Test
    public void testFailInstallAnotherSessionAlreadyInProgress_BothMultiPackage()
            throws Exception {
        int sessionId = stageMultipleApks(
                "StagedInstallTestAppAv1.apk",
                "StagedInstallTestAppBv1.apk").assertSuccessful().getSessionId();
        StageSessionResult failedSessionResult = stageMultipleApks(
                "StagedInstallTestAppAv1.apk",
                "StagedInstallTestAppBv1.apk");
        assertThat(failedSessionResult.getErrorMessage()).contains(
                "There is already in-progress committed staged session");
        getPackageInstaller().abandonSession(sessionId);
    }

    @Test
    public void testAbandonStagedApkBeforeReboot_CommitAndAbandon() throws Exception {
        int sessionId = stageSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        waitForIsReadyBroadcast(sessionId);
        PackageInstaller.SessionInfo session = getStagedSessionInfo(sessionId);
        assertSessionReady(sessionId);
        abandonSession(sessionId);
        assertThat(getStagedSessionInfo(sessionId)).isNull();
        // Allow the session to be removed from PackageInstaller
        Duration spentWaiting = Duration.ZERO;
        while (spentWaiting.compareTo(WAIT_FOR_SESSION_REMOVED_TTL) < 0) {
            session = getSessionInfo(sessionId);
            if (session == null) {
                Log.i(TAG, "Done waiting after " + spentWaiting);
                break;
            }
            try {
                Thread.sleep(SLEEP_DURATION.toMillis());
                spentWaiting = spentWaiting.plus(SLEEP_DURATION);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        assertThat(session).isNull();
    }

    @Test
    public void testAbandonStagedApkBeforeReboot_VerifyPostReboot() throws Exception {
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
    }

    @Test
    public void testGetActiveStagedSession() throws Exception {
        PackageInstaller packageInstaller = getPackageInstaller();
        int sessionId = stageSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        PackageInstaller.SessionInfo session = packageInstaller.getActiveStagedSession();
        assertThat(session.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    public void testGetActiveStagedSessionNoSessionActive() throws Exception {
        PackageInstaller packageInstaller = getPackageInstaller();
        PackageInstaller.SessionInfo session = packageInstaller.getActiveStagedSession();
        assertThat(session).isNull();
    }

    @Test
    public void testGetGetActiveStagedSession_MultiApkSession() throws Exception {
        int sessionId = stageMultipleApks(
                "StagedInstallTestAppAv1.apk",
                "StagedInstallTestAppBv1.apk")
                .assertSuccessful().getSessionId();
        PackageInstaller.SessionInfo session = getPackageInstaller().getActiveStagedSession();
        assertThat(session.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    public void testStagedInstallDowngrade_DowngradeNotRequested_Fails_Commit()  throws Exception {
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        installNonStaged("StagedInstallTestAppAv2.apk");
        int sessionId = stageSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(2);
        PackageInstaller.SessionInfo sessionInfo = waitForBroadcast(sessionId);
        assertThat(sessionInfo).isStagedSessionFailed();
    }

    @Test
    public void testStagedInstallDowngrade_DowngradeRequested_Commit() throws Exception {
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        installNonStaged("StagedInstallTestAppAv2.apk");
        int sessionId = stageDowngradeSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(2);
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testStagedInstallDowngrade_DowngradeRequested_Fails_Commit() throws Exception {
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        installNonStaged("StagedInstallTestAppAv2.apk");
        int sessionId = stageDowngradeSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(2);
        PackageInstaller.SessionInfo sessionInfo = waitForBroadcast(sessionId);
        assertThat(sessionInfo).isStagedSessionFailed();
    }

    @Test
    public void testStagedInstallDowngrade_DowngradeRequested_DebugBuild_VerifyPostReboot()
            throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        // App should be downgraded.
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(1);
    }

    @Test
    public void testInstallStagedApex_Commit() throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        int sessionId = stageSingleApk(
                "com.android.apex.cts.shim.v2.apex").assertSuccessful().getSessionId();
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
        // Version shouldn't change before reboot.
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
    }

    @Test
    public void testInstallStagedApex_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(2);
    }

    @Test
    public void testInstallStagedApexAndApk_Commit() throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        int sessionId = stageMultipleApks(
                "com.android.apex.cts.shim.v2.apex",
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
        // Version shouldn't change before reboot.
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
    }

    @Test
    public void testInstallStagedApexAndApk_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(2);
        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(1);
    }

    @Test
    public void testsFailsNonStagedApexInstall() throws Exception {
        PackageInstaller installer = getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallAsApex();
        try {
            installer.createSession(params);
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains(
                    "APEX files can only be installed as part of a staged session");
        }
    }

    @Test
    public void testInstallStagedNonPreInstalledApex_Fails() throws Exception {
        assertThat(getInstalledVersion(NOT_PRE_INSTALLED_SHIM_APEX_PACKAGE_NAME)).isEqualTo(-1);
        int sessionId = stageSingleApk(
                "com.android.apex.cts.shim_not_pre_installed.apex")
                .assertSuccessful().getSessionId();
        PackageInstaller.SessionInfo sessionInfo = waitForBroadcast(sessionId);
        assertThat(sessionInfo).isStagedSessionFailed();
    }

    @Test
    public void testStageApkWithSameNameAsApexShouldFail_Commit() throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        int sessionId = stageSingleApk(
                "StagedInstallTestAppSamePackageNameAsApex.apk").assertSuccessful().getSessionId();
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testStageApkWithSameNameAsApexShouldFail_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionFailed(sessionId);
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
    }

    @Test
    public void testNonStagedInstallApkWithSameNameAsApexShouldFail() throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        PackageInstaller packageInstaller = getPackageInstaller();
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = packageInstaller.createSession(sessionParams);
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);
        writeApk(session, "StagedInstallTestAppSamePackageNameAsApex.apk");
        session.commit(LocalIntentSender.getIntentSender());
        final String errorMessage = extractErrorMessage(LocalIntentSender.getIntentSenderResult());
        assertThat(errorMessage).contains("is an APEX package and can't be installed as an APK");
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
    }

    @Test
    public void testInstallV3Apex_Commit() throws Exception {
        int sessionId = stageSingleApk(
                "com.android.apex.cts.shim.v3.apex").assertSuccessful().getSessionId();
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testInstallV3Apex_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(3);
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeNotRequested_Fails_Commit()
            throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(3);
        int sessionId = stageSingleApk(
                "com.android.apex.cts.shim.v2.apex").assertSuccessful().getSessionId();
        PackageInstaller.SessionInfo sessionInfo = waitForBroadcast(sessionId);
        assertThat(sessionInfo).isStagedSessionFailed();
        // Also verify that correct session info is reported by PackageManager.
        assertSessionFailed(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeNotRequested_Fails_VerifyPostReboot()
            throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionFailed(sessionId);
        // INSTALL_REQUEST_DOWNGRADE wasn't set, so apex shouldn't be downgraded.
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(3);
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeRequested_DebugBuild_Commit()
            throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(3);
        int sessionId = stageDowngradeSingleApk(
                "com.android.apex.cts.shim.v2.apex").assertSuccessful().getSessionId();
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeRequested_DebugBuild_VerifyPostReboot()
            throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        // Apex should be downgraded.
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(2);
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeRequested_UserBuild_Fails_Commit()
            throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(3);
        int sessionId = stageDowngradeSingleApk(
                "com.android.apex.cts.shim.v2.apex").assertSuccessful().getSessionId();
        PackageInstaller.SessionInfo sessionInfo = waitForBroadcast(sessionId);
        assertThat(sessionInfo).isStagedSessionFailed();
        // Also verify that correct session info is reported by PackageManager.
        assertSessionFailed(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testStagedInstallDowngradeApex_DowngradeRequested_UserBuild_Fails_VerifyPostReboot()
            throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionFailed(sessionId);
        // Apex shouldn't be downgraded.
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(3);
    }

    @Test
    public void testInstallApex_DeviceDoesNotSupportApex_Fails() throws Exception {
        try {
            stageSingleApk("com.android.apex.cts.shim.v2.apex");
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains(
                    "This device doesn't support the installation of APEX files");
        }
    }

    @Test
    public void testFailsInvalidApexInstall_Commit() throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        int sessionId = stageSingleApk(
                "com.android.apex.cts.shim.v2_wrong_sha.apex").assertSuccessful()
                .getSessionId();
        waitForIsFailedBroadcast(sessionId);
        assertSessionFailed(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testFailsInvalidApexInstall_AbandonSessionIsNoop() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionFailed(sessionId);
        // Session is in a final state. Test that abandoning the session doesn't remove it from the
        // session database.
        getPackageInstaller().abandonSession(sessionId);
        assertSessionFailed(sessionId);
    }

    @Test
    public void testStagedApkSessionCallbacks() throws Exception {

        List<Integer> created = new ArrayList<Integer>();
        List<Integer> finished = new ArrayList<Integer>();

        HandlerThread handlerThread = new HandlerThread(
                "StagedApkSessionCallbacksTestHandlerThread");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        PackageInstaller.SessionCallback callback = new PackageInstaller.SessionCallback() {

            @Override
            public void onCreated(int sessionId) {
                synchronized (created) {
                    created.add(sessionId);
                }
            }

            @Override public void onBadgingChanged(int sessionId) { }
            @Override public void onActiveChanged(int sessionId, boolean active) { }
            @Override public void onProgressChanged(int sessionId, float progress) { }

            @Override
            public void onFinished(int sessionId, boolean success) {
                synchronized (finished) {
                    finished.add(sessionId);
                }
            }
        };

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        packageInstaller.registerSessionCallback(callback, handler);

        int sessionId = stageSingleApk(
                "StagedInstallTestAppAv1.apk").assertSuccessful().getSessionId();

        assertThat(getInstalledVersion(TEST_APP_A)).isEqualTo(-1);
        waitForIsReadyBroadcast(sessionId);
        assertSessionReady(sessionId);

        packageInstaller.unregisterSessionCallback(callback);

        handlerThread.quitSafely();
        handlerThread.join();

        synchronized (created) {
            assertThat(created).containsExactly(sessionId);
        }
        synchronized (finished) {
            assertThat(finished).containsExactly(sessionId);
        }
        packageInstaller.abandonSession(sessionId);
    }

    private static PackageInstaller getPackageInstaller() {
        return InstrumentationRegistry.getInstrumentation().getContext().getPackageManager()
                .getPackageInstaller();
    }

    private static long getInstalledVersion(String packageName) {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.MATCH_APEX);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    // It becomes harder to maintain this variety of install-related helper methods.
    // TODO(ioffe): refactor install-related helper methods into a separate utility.
    private static int createStagedSession() throws Exception {
        return createStagedSession(getPackageInstaller(), false, false, false);
    }

    private static int createStagedSession(
            PackageInstaller packageInstaller,
            boolean multiPackage, boolean isDowngrade, boolean isApexSession) throws Exception {
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (multiPackage) {
            sessionParams.setMultiPackage();
        }
        sessionParams.setStaged();
        sessionParams.setRequestDowngrade(isDowngrade);
        if (isApexSession) {
            sessionParams.setInstallAsApex();
        }

        return packageInstaller.createSession(sessionParams);
    }

    private static StageSessionResult stageDowngradeSingleApk(String apkFileName) throws Exception {
        Log.i(TAG, "Staging a downgrade of " + apkFileName);
        PackageInstaller packageInstaller = getPackageInstaller();

        Pair<Integer, PackageInstaller.Session> sessionPair =
                prepareSingleApkStagedSession(packageInstaller, apkFileName, true);
        // Commit the session (this will start the installation workflow).
        Log.i(TAG, "Committing downgrade session for apk: " + apkFileName);
        sessionPair.second.commit(LocalIntentSender.getIntentSender());
        return new StageSessionResult(sessionPair.first, LocalIntentSender.getIntentSenderResult());
    }

    private static StageSessionResult stageSingleApk(String apkFileName) throws Exception {
        Log.i(TAG, "Staging an install of " + apkFileName);
        PackageInstaller packageInstaller = getPackageInstaller();

        Pair<Integer, PackageInstaller.Session> sessionPair =
                prepareSingleApkStagedSession(packageInstaller, apkFileName, false);
        // Commit the session (this will start the installation workflow).
        Log.i(TAG, "Committing session for apk: " + apkFileName);
        sessionPair.second.commit(LocalIntentSender.getIntentSender());
        return new StageSessionResult(sessionPair.first, LocalIntentSender.getIntentSenderResult());
    }

    private static Pair<Integer, PackageInstaller.Session>
            prepareSingleApkStagedSession(PackageInstaller packageInstaller, String apkFileName,
            boolean isDowngrade)
            throws Exception {
        int sessionId = createStagedSession(packageInstaller, false, isDowngrade,
                apkFileName.endsWith(".apex"));
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);
        writeApk(session, apkFileName);
        return new Pair<>(sessionId, session);
    }

    private static StageSessionResult stageMultipleApks(String... apkFileNames) throws Exception {
        Log.i(TAG, "Staging an install of " + Arrays.toString(apkFileNames));
        PackageInstaller packageInstaller = getPackageInstaller();
        int multiPackageSessionId = createStagedSession(packageInstaller, true, false, false);
        PackageInstaller.Session multiPackageSession = packageInstaller.openSession(
                multiPackageSessionId);
        for (String apkFileName : apkFileNames) {
            Pair<Integer, PackageInstaller.Session> sessionPair =
                    prepareSingleApkStagedSession(packageInstaller, apkFileName, false);
            multiPackageSession.addChildSessionId(sessionPair.first);
        }
        multiPackageSession.commit(LocalIntentSender.getIntentSender());
        return new StageSessionResult(
                multiPackageSessionId, LocalIntentSender.getIntentSenderResult());
    }

    private static void assertSessionApplied(int sessionId) {
        assertSessionState(sessionId,
                (session) -> assertThat(session).isStagedSessionApplied());
    }

    private static void assertSessionReady(int sessionId) {
        assertSessionState(sessionId,
                (session) -> assertThat(session).isStagedSessionReady());
    }

    private static void assertSessionFailed(int sessionId) {
        assertSessionState(sessionId,
                (session) -> assertThat(session).isStagedSessionFailed());
    }

    private static void assertSessionState(
            int sessionId, Consumer<PackageInstaller.SessionInfo> assertion) {
        PackageInstaller packageInstaller = getPackageInstaller();

        List<PackageInstaller.SessionInfo> sessions = packageInstaller.getStagedSessions();
        boolean found = false;
        for (PackageInstaller.SessionInfo session : sessions) {
            if (session.getSessionId() == sessionId) {
                assertion.accept(session);
                found = true;
            }
        }
        assertWithMessage("Expecting to find session in getStagedSession()")
                .that(found).isTrue();

        // Test also that getSessionInfo correctly returns the session.
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        assertion.accept(sessionInfo);
    }

    private void storeSessionId(int sessionId) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(mTestStateFile))) {
            writer.write("" + sessionId);
        }
    }

    private int retrieveLastSessionId() throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(mTestStateFile))) {
            return Integer.parseInt(reader.readLine());
        }
    }

    private static void installNonStaged(String apkFileName) throws Exception {
        PackageInstaller packageInstaller = getPackageInstaller();
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = packageInstaller.createSession(sessionParams);
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);
        writeApk(session, apkFileName);
        session.commit(LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    private static void writeApk(PackageInstaller.Session session, String apkFileName)
            throws Exception {
        try (OutputStream packageInSession = session.openWrite(apkFileName, 0, -1);
             InputStream is =
                     StagedInstallTest.class.getClassLoader().getResourceAsStream(apkFileName)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                packageInSession.write(buffer, 0, n);
            }
        }
    }

    // TODO(ioffe): not really-tailored to staged install, rename to InstallResult?
    private static final class StageSessionResult {
        private final int sessionId;
        private final Intent result;

        private StageSessionResult(int sessionId, Intent result) {
            this.sessionId = sessionId;
            this.result = result;
        }

        public int getSessionId() {
            return sessionId;
        }

        public String getErrorMessage() {
            return extractErrorMessage(result);
        }

        public StageSessionResult assertSuccessful() {
            assertStatusSuccess(result);
            return this;
        }
    }

    private static String extractErrorMessage(Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == -1) {
            throw new AssertionError("PENDING USER ACTION");
        }
        if (status == 0) {
            throw new AssertionError("Result was successful");
        }
        return result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
    }

    private static void abandonSession(int sessionId) {
        getPackageInstaller().abandonSession(sessionId);
    }

    /**
     * Returns the session by session Id, or null if no session is found.
     */
    private static PackageInstaller.SessionInfo getStagedSessionInfo(int sessionId) {
        PackageInstaller packageInstaller = getPackageInstaller();
        for (PackageInstaller.SessionInfo session : packageInstaller.getStagedSessions()) {
            if (session.getSessionId() == sessionId) {
                return session;
            }
        }
        return null;
    }

    private static PackageInstaller.SessionInfo getSessionInfo(int sessionId) {
        return getPackageInstaller().getSessionInfo(sessionId);
    }

    private static void uninstall(String packageName) throws Exception {
        // No need to uninstall if the package isn't installed.
        if (getInstalledVersion(packageName) == -1) {
            return;
        }

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        PackageManager packageManager = context.getPackageManager();
        PackageInstaller packageInstaller = packageManager.getPackageInstaller();
        packageInstaller.uninstall(packageName, LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    private static void assertStatusSuccess(Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == -1) {
            throw new AssertionError("PENDING USER ACTION");
        } else if (status > 0) {
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            throw new AssertionError(message == null ? "UNKNOWN FAILURE" : message);
        }
    }

    private void waitForIsFailedBroadcast(int sessionId) {
        Log.i(TAG, "Waiting for session " + sessionId + " to be marked as failed");
        try {

            PackageInstaller.SessionInfo info = waitForBroadcast(sessionId);
            assertThat(info).isStagedSessionFailed();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void waitForIsReadyBroadcast(int sessionId) {
        Log.i(TAG, "Waiting for session " + sessionId + " to be ready");
        try {
            PackageInstaller.SessionInfo info = waitForBroadcast(sessionId);
            assertThat(info).isStagedSessionReady();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private PackageInstaller.SessionInfo waitForBroadcast(int sessionId) throws Exception {
        PackageInstaller.SessionInfo info =
                SessionUpdateBroadcastReceiver.sessionBroadcasts.poll(60, TimeUnit.SECONDS);
        assertThat(info).isNotNull();
        assertThat(info.getSessionId()).isEqualTo(sessionId);
        return info;
    }
}
