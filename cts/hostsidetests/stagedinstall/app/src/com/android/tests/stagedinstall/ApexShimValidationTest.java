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

package com.android.tests.stagedinstall;

import static com.android.tests.stagedinstall.PackageInstallerSessionInfoSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * These tests use a similar structure to {@link StagedInstallTest}. See
 * {@link StagedInstallTest} documentation for reference.
 *
 * @see StagedInstallTest
 */
@RunWith(JUnit4.class)
public class ApexShimValidationTest {

    private static final String SHIM_APEX_PACKAGE_NAME = "com.android.apex.cts.shim";

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

    @Test
    public void testRejectsApexWithAdditionalFile_Commit() throws Exception {
        int sessionId = stageApex("com.android.apex.cts.shim.v2_additional_file.apex");
        PackageInstaller.SessionInfo info =
                SessionUpdateBroadcastReceiver.sessionBroadcasts.poll(60, TimeUnit.SECONDS);
        assertThat(info.getSessionId()).isEqualTo(sessionId);
        assertThat(info).isStagedSessionFailed();
    }

    @Test
    public void testRejectsApexWithAdditionalFolder_Commit() throws Exception {
        int sessionId = stageApex("com.android.apex.cts.shim.v2_additional_folder.apex");
        PackageInstaller.SessionInfo info =
                SessionUpdateBroadcastReceiver.sessionBroadcasts.poll(60, TimeUnit.SECONDS);
        assertThat(info.getSessionId()).isEqualTo(sessionId);
        assertThat(info).isStagedSessionFailed();
    }

    @Test
    public void testRejectsApexWithPostInstallHook_Commit() throws Exception {
        int sessionId = stageApex("com.android.apex.cts.shim.v2_with_post_install_hook.apex");
        PackageInstaller.SessionInfo info =
                SessionUpdateBroadcastReceiver.sessionBroadcasts.poll(60, TimeUnit.SECONDS);
        assertThat(info.getSessionId()).isEqualTo(sessionId);
        assertThat(info).isStagedSessionFailed();
    }

    @Test
    public void testRejectsApexWithPreInstallHook_Commit() throws Exception {
        int sessionId = stageApex("com.android.apex.cts.shim.v2_with_pre_install_hook.apex");
        PackageInstaller.SessionInfo info =
                SessionUpdateBroadcastReceiver.sessionBroadcasts.poll(60, TimeUnit.SECONDS);
        assertThat(info.getSessionId()).isEqualTo(sessionId);
        assertThat(info).isStagedSessionFailed();
    }

    @Test
    public void testRejectsApexWrongSHA_Commit() throws Exception {
        int sessionId = stageApex("com.android.apex.cts.shim.v2_wrong_sha.apex");
        PackageInstaller.SessionInfo info =
                SessionUpdateBroadcastReceiver.sessionBroadcasts.poll(60, TimeUnit.SECONDS);
        assertThat(info.getSessionId()).isEqualTo(sessionId);
        assertThat(info).isStagedSessionFailed();
    }

    @Test
    public void testInstallRejected_VerifyPostReboot() throws Exception {
        assertThat(getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
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

    private static int stageApex(String apexFileName) throws Exception {
        PackageInstaller installer = getPackageInstaller();

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallAsApex();
        params.setStaged();
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        writeApex(session, apexFileName);
        session.commit(LocalIntentSender.getIntentSender());
        Intent result = LocalIntentSender.getIntentSenderResult();
        assertStatusSuccess(result);
        return sessionId;
    }

    private static void writeApex(PackageInstaller.Session session, String apkFileName)
            throws Exception {
        try (OutputStream packageInSession = session.openWrite(apkFileName, 0, -1);
             InputStream is =
                     ApexShimValidationTest.class.getClassLoader().getResourceAsStream(
                             apkFileName)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                packageInSession.write(buffer, 0, n);
            }
        }
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
}
