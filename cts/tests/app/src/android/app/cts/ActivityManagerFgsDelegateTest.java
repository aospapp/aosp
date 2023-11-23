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

package android.app.cts;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.cts.android.app.cts.tools.WatchUidRunner;
import android.app.stubs.CommandReceiver;
import android.app.stubs.LocalForegroundService;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.permission.cts.PermissionUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ActivityManagerFgsDelegateTest {
    private static final String TAG = ActivityManagerFgsDelegateTest.class.getName();

    static final String STUB_PACKAGE_NAME = "android.app.stubs";
    static final String PACKAGE_NAME_APP1 = "com.android.app1";

    static final int WAITFOR_MSEC = 10000;

    private static final String[] PACKAGE_NAMES = {
            PACKAGE_NAME_APP1
    };

    private static final String DUMP_COMMAND = "dumpsys activity services " + PACKAGE_NAME_APP1
            + "/SPECIAL_USE:FgsDelegate";
    private static final String DUMP_COMMAND2 = "dumpsys activity services " + PACKAGE_NAME_APP1
            + "/android.app.stubs.LocalForegroundService";

    private Context mContext;
    private Instrumentation mInstrumentation;
    private Context mTargetContext;
    ActivityManager mActivityManager;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mTargetContext = mInstrumentation.getTargetContext();
        CtsAppTestUtils.turnScreenOn(mInstrumentation, mContext);
        cleanupResiduals();
        // Press home key to ensure stopAppSwitches is called so the grace period of
        // the background start will be ignored if there's any.
        UiDevice.getInstance(mInstrumentation).pressHome();
    }

    @After
    public void tearDown() throws Exception {
        cleanupResiduals();
    }

    private void cleanupResiduals() {
        // Stop all the packages to avoid residual impact
        for (int i = 0; i < PACKAGE_NAMES.length; i++) {
            final String pkgName = PACKAGE_NAMES[i];
            SystemUtil.runWithShellPermissionIdentity(() -> {
                mActivityManager.forceStopPackage(pkgName);
            });
        }
        // Make sure we are in Home screen
        mInstrumentation.getUiAutomation().performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_HOME);
    }

    private void prepareProcess(WatchUidRunner uidWatcher) throws Exception {
        // Bypass bg-service-start restriction.
        CtsAppTestUtils.executeShellCmd(mInstrumentation,
                "dumpsys deviceidle whitelist +" + PACKAGE_NAME_APP1);
        // start background service.
        Bundle extras = LocalForegroundService.newCommand(
                LocalForegroundService.COMMAND_START_NO_FOREGROUND);
        CommandReceiver.sendCommand(mContext, CommandReceiver.COMMAND_START_SERVICE,
                PACKAGE_NAME_APP1, PACKAGE_NAME_APP1, 0, extras);
        uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_SERVICE);
        CtsAppTestUtils.executeShellCmd(mInstrumentation,
                "dumpsys deviceidle whitelist -" + PACKAGE_NAME_APP1);
    }

    @Test
    public void testFgsDelegate() throws Exception {
        ApplicationInfo app1Info = mContext.getPackageManager().getApplicationInfo(
                PACKAGE_NAME_APP1, 0);
        WatchUidRunner uidWatcher = new WatchUidRunner(mInstrumentation, app1Info.uid,
                WAITFOR_MSEC);

        String[] dumpLines;
        try {
            prepareProcess(uidWatcher);

            setForegroundServiceDelegate(PACKAGE_NAME_APP1, true);
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_FG_SERVICE);
            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));

            setForegroundServiceDelegate(PACKAGE_NAME_APP1, false);
            // The delegated foreground service is stopped, go back to background service state.
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_SERVICE);
            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));

            // Start delegated foreground service again, the app goes to FGS state.
            setForegroundServiceDelegate(PACKAGE_NAME_APP1, true);
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_FG_SERVICE);
            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));

            // Stop foreground service delegate again, the app goes to background service state.
            setForegroundServiceDelegate(PACKAGE_NAME_APP1, false);
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_SERVICE);
            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));

            CommandReceiver.sendCommand(mContext, CommandReceiver.COMMAND_STOP_SERVICE,
                    PACKAGE_NAME_APP1, PACKAGE_NAME_APP1, 0, null);
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_CACHED_EMPTY);
        } finally {
            uidWatcher.finish();
        }
    }

    @Test
    public void testFgsDelegateNotAllowedWhenAppCanNotStartFGS() throws Exception {
        ApplicationInfo app1Info = mContext.getPackageManager().getApplicationInfo(
                PACKAGE_NAME_APP1, 0);
        WatchUidRunner uidWatcher = new WatchUidRunner(mInstrumentation, app1Info.uid,
                WAITFOR_MSEC);

        String[] dumpLines;
        try {
            prepareProcess(uidWatcher);

            // Disallow app1 to start FGS.
            allowBgFgsStart(PACKAGE_NAME_APP1, false);
            // app1 is in the background, because it can not start FGS from the background, it is
            // also not allowed to start FGS delegate.
            setForegroundServiceDelegate(PACKAGE_NAME_APP1, true);
            try {
                uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_FG_SERVICE);
                fail("Service should not enter foreground service state");
            } catch (Exception e) {
            }
            // Allow app1 to start FGS.
            allowBgFgsStart(PACKAGE_NAME_APP1, true);
            // Now it can start FGS delegate.
            setForegroundServiceDelegate(PACKAGE_NAME_APP1, true);
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_FG_SERVICE);
            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));

            // Stop FGS delegate.
            setForegroundServiceDelegate(PACKAGE_NAME_APP1, false);
            // The delegated foreground service is stopped, go back to background service state.
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_SERVICE);
            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));
            // Stop the background service.
            CommandReceiver.sendCommand(mContext, CommandReceiver.COMMAND_STOP_SERVICE,
                    PACKAGE_NAME_APP1, PACKAGE_NAME_APP1, 0, null);
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_CACHED_EMPTY);
        } finally {
            uidWatcher.finish();
        }
    }

    @Test
    public void testFgsDelegateAfterForceStopPackage() throws Exception {
        ApplicationInfo app1Info = mContext.getPackageManager().getApplicationInfo(
                PACKAGE_NAME_APP1, 0);
        WatchUidRunner uidWatcher = new WatchUidRunner(mInstrumentation, app1Info.uid,
                WAITFOR_MSEC);

        String[] dumpLines;
        try {
            prepareProcess(uidWatcher);

            setForegroundServiceDelegate(PACKAGE_NAME_APP1, true);
            uidWatcher.waitFor(WatchUidRunner.CMD_PROCSTATE, WatchUidRunner.STATE_FG_SERVICE);
            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNotNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));

            SystemUtil.runWithShellPermissionIdentity(() -> {
                mActivityManager.forceStopPackage(PACKAGE_NAME_APP1);
            });

            dumpLines = CtsAppTestUtils.executeShellCmd(
                    mInstrumentation, DUMP_COMMAND).split("\n");
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isForeground=true"));
            assertNull(CtsAppTestUtils.findLine(dumpLines, "isFgsDelegate=true"));
        } finally {
            uidWatcher.finish();
        }
    }

    private void setForegroundServiceDelegate(String packageName, boolean isStart)
            throws Exception {
        CtsAppTestUtils.executeShellCmd(mInstrumentation,
                "am set-foreground-service-delegate --user "
                + UserHandle.getUserId(android.os.Process.myUid())
                + " " + packageName
                + (isStart ? " start" : " stop"));
    }

    /**
     * SYSTEM_ALERT_WINDOW permission will allow both BG-activity start and BG-FGS start.
     * Some cases we want to grant this permission to allow FGS start from the background.
     * Some cases we want to revoke this permission to disallow FGS start from the background..
     *
     * Note: by default the testing apps have SYSTEM_ALERT_WINDOW permission in manifest file.
     */
    private void allowBgFgsStart(String packageName, boolean allow) throws Exception {
        if (allow) {
            PermissionUtils.grantPermission(
                    packageName, android.Manifest.permission.SYSTEM_ALERT_WINDOW);
        } else {
            PermissionUtils.revokePermission(
                    packageName, android.Manifest.permission.SYSTEM_ALERT_WINDOW);
        }
    }
}
