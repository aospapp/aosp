/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.content.pm.cts.shortcuthost;


import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;

public class ShortcutManagerBackupTest extends BaseShortcutManagerHostTest {
    private static final String LAUNCHER1_APK = "CtsShortcutBackupLauncher1.apk";
    private static final String LAUNCHER2_APK = "CtsShortcutBackupLauncher2.apk";
    private static final String LAUNCHER3_APK = "CtsShortcutBackupLauncher3.apk";
    private static final String PUBLISHER1_APK = "CtsShortcutBackupPublisher1.apk";
    private static final String PUBLISHER2_APK = "CtsShortcutBackupPublisher2.apk";
    private static final String PUBLISHER3_APK = "CtsShortcutBackupPublisher3.apk";

    private static final String LAUNCHER1_PKG =
            "android.content.pm.cts.shortcut.backup.launcher1";
    private static final String LAUNCHER2_PKG =
            "android.content.pm.cts.shortcut.backup.launcher2";
    private static final String LAUNCHER3_PKG =
            "android.content.pm.cts.shortcut.backup.launcher3";
    private static final String PUBLISHER1_PKG =
            "android.content.pm.cts.shortcut.backup.publisher1";
    private static final String PUBLISHER2_PKG =
            "android.content.pm.cts.shortcut.backup.publisher2";
    private static final String PUBLISHER3_PKG =
            "android.content.pm.cts.shortcut.backup.publisher3";

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        clearShortcuts(LAUNCHER1_PKG, getPrimaryUserId());
        clearShortcuts(LAUNCHER2_PKG, getPrimaryUserId());
        clearShortcuts(LAUNCHER3_PKG, getPrimaryUserId());
        clearShortcuts(PUBLISHER1_PKG, getPrimaryUserId());
        clearShortcuts(PUBLISHER2_PKG, getPrimaryUserId());
        clearShortcuts(PUBLISHER3_PKG, getPrimaryUserId());
    }

    @Override
    protected void tearDown() throws Exception {
        if (DUMPSYS_IN_TEARDOWN) {
            dumpsys("tearDown");
        }

        getDevice().uninstallPackage(LAUNCHER1_PKG);
        getDevice().uninstallPackage(LAUNCHER2_PKG);
        getDevice().uninstallPackage(LAUNCHER3_PKG);

        getDevice().uninstallPackage(PUBLISHER1_PKG);
        getDevice().uninstallPackage(PUBLISHER2_PKG);
        getDevice().uninstallPackage(PUBLISHER3_PKG);

        super.tearDown();
    }

    private void doBackup() throws DeviceNotAvailableException {
        CLog.i("Backing up package android...");
        assertContainsRegex(
                "^Selected transport android/com.android.internal.backup.LocalTransport",
                executeShellCommandWithLog(
                        "bmgr transport android/com.android.internal.backup.LocalTransport"));

        assertContainsRegex(
                "Wiped",
                executeShellCommandWithLog(
                        "bmgr wipe android/com.android.internal.backup.LocalTransport android"));

        assertContainsRegex(
                "Backup finished with result: Success",
                executeShellCommandWithLog("bmgr backupnow android"));

    }

    private void doRestore() throws DeviceNotAvailableException {
        CLog.i("Restoring package android...");

        assertContainsRegex(
                "\\bdone\\b",
                executeShellCommandWithLog("bmgr restore 1 android"));

    }

    public void testBackupAndRestore() throws Exception {
        installAppAsUser(LAUNCHER1_APK, getPrimaryUserId());
        installAppAsUser(LAUNCHER2_APK, getPrimaryUserId());
        installAppAsUser(LAUNCHER3_APK, getPrimaryUserId());

        installAppAsUser(PUBLISHER1_APK, getPrimaryUserId());
        installAppAsUser(PUBLISHER2_APK, getPrimaryUserId());
        installAppAsUser(PUBLISHER3_APK, getPrimaryUserId());

        // Prepare shortcuts
        runDeviceTestsAsUser(PUBLISHER1_PKG, ".ShortcutManagerPreBackupTest", getPrimaryUserId());
        runDeviceTestsAsUser(PUBLISHER2_PKG, ".ShortcutManagerPreBackupTest", getPrimaryUserId());
        runDeviceTestsAsUser(PUBLISHER3_PKG, ".ShortcutManagerPreBackupTest", getPrimaryUserId());

        runDeviceTestsAsUser(LAUNCHER1_PKG, ".ShortcutManagerPreBackupTest", getPrimaryUserId());
        runDeviceTestsAsUser(LAUNCHER2_PKG, ".ShortcutManagerPreBackupTest", getPrimaryUserId());
        runDeviceTestsAsUser(LAUNCHER3_PKG, ".ShortcutManagerPreBackupTest", getPrimaryUserId());

        // Tweak shortcuts a little bit to make disabled shortcuts.
        runDeviceTestsAsUser(PUBLISHER2_PKG, ".ShortcutManagerPreBackup2Test", getPrimaryUserId());

        // Backup
        doBackup();

        // Uninstall all apps
        getDevice().uninstallPackage(LAUNCHER1_PKG);
        getDevice().uninstallPackage(LAUNCHER2_PKG);
        getDevice().uninstallPackage(LAUNCHER3_PKG);

        getDevice().uninstallPackage(PUBLISHER1_PKG);
        getDevice().uninstallPackage(PUBLISHER2_PKG);
        getDevice().uninstallPackage(PUBLISHER3_PKG);

        // Then restore
        doRestore();

        // First, restore launcher 1, which shouldn't see any shortcuts from the packages yet.
        installAppAsUser(LAUNCHER1_APK, getPrimaryUserId());
        runDeviceTestsAsUser(LAUNCHER1_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall_beforeAppRestore",
                getPrimaryUserId());

        // Restore the apps.  Even though launcher 2 hasn't been re-installed yet, they should
        // still have pinned shortcuts by launcher 2.
        installAppAsUser(PUBLISHER1_APK, getPrimaryUserId());
        installAppAsUser(PUBLISHER2_APK, getPrimaryUserId());
        installAppAsUser(PUBLISHER3_APK, getPrimaryUserId());

        runDeviceTestsAsUser(PUBLISHER1_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall",
                getPrimaryUserId());

        runDeviceTestsAsUser(PUBLISHER2_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall",
                getPrimaryUserId());

        runDeviceTestsAsUser(PUBLISHER3_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall",
                getPrimaryUserId());

        // Now launcher 1 should see shortcuts from these packages.
        runDeviceTestsAsUser(LAUNCHER1_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall_afterAppRestore",
                getPrimaryUserId());

        // Then restore launcher 2 and check.
        installAppAsUser(LAUNCHER2_APK, getPrimaryUserId());
        runDeviceTestsAsUser(LAUNCHER2_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall_afterAppRestore",
                getPrimaryUserId());


        // Run the same package side check.  The result should be the same.
        runDeviceTestsAsUser(PUBLISHER1_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall",
                getPrimaryUserId());

        runDeviceTestsAsUser(PUBLISHER2_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall",
                getPrimaryUserId());

        runDeviceTestsAsUser(PUBLISHER3_PKG, ".ShortcutManagerPostBackupTest",
                "testWithUninstall",
                getPrimaryUserId());
    }

    public void testBackupAndRestore_withNoUninstall() throws Exception {

        installAppAsUser(PUBLISHER1_APK, getPrimaryUserId());

        // Prepare shortcuts
        runDeviceTestsAsUser(PUBLISHER1_PKG, ".ShortcutManagerPreBackupTest", getPrimaryUserId());

        // Backup & restore.
        doBackup();
        doRestore();

        // Make sure the manifest shortcuts are re-published.
        runDeviceTestsAsUser(PUBLISHER1_PKG, ".ShortcutManagerPostBackupTest",
                "testWithNoUninstall",
                getPrimaryUserId());
    }
}