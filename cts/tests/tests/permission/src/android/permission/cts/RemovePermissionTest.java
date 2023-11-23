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

package android.permission.cts;

import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PackageManager.GET_PERMISSIONS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.SecurityTest;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.Before;
import org.junit.Test;

@AppModeFull(reason = "Instant apps cannot read state of other packages.")
public class RemovePermissionTest {
    private static final String APP_PKG_NAME_BASE =
            "android.permission.cts.revokepermissionwhenremoved";
    private static final String ADVERSARIAL_PERMISSION_DEFINER_PKG_NAME =
            APP_PKG_NAME_BASE + ".AdversarialPermissionDefinerApp";
    private static final String VICTIM_PERMISSION_DEFINER_PKG_NAME =
            APP_PKG_NAME_BASE + ".VictimPermissionDefinerApp";
    private static final String ADVERSARIAL_PERMISSION_USER_PKG_NAME =
            APP_PKG_NAME_BASE + ".userapp";
    private static final String RUNTIME_PERMISSION_USER_PKG_NAME =
            APP_PKG_NAME_BASE + ".runtimepermissionuserapp";
    private static final String RUNTIME_PERMISSION_DEFINER_PKG_NAME =
            APP_PKG_NAME_BASE + ".runtimepermissiondefinerapp";
    private static final String INSTALLTIME_PERMISSION_USER_PKG_NAME =
            APP_PKG_NAME_BASE + ".installtimepermissionuserapp";
    private static final String INSTALLTIME_PERMISSION_DEFINER_PKG_NAME =
            APP_PKG_NAME_BASE + ".installtimepermissiondefinerapp";

    private static final String TEST_PERMISSION =
            "android.permission.cts.revokepermissionwhenremoved.TestPermission";
    private static final String TEST_RUNTIME_PERMISSION =
            APP_PKG_NAME_BASE + ".TestRuntimePermission";
    private static final String TEST_INSTALLTIME_PERMISSION =
            APP_PKG_NAME_BASE + ".TestInstalltimePermission";

    private static final String ADVERSARIAL_PERMISSION_DEFINER_APK_NAME =
            "CtsAdversarialPermissionDefinerApp";
    private static final String ADVERSARIAL_PERMISSION_USER_APK_NAME =
            "CtsAdversarialPermissionUserApp";
    private static final String VICTIM_PERMISSION_DEFINER_APK_NAME =
            "CtsVictimPermissionDefinerApp";
    private static final String RUNTIME_PERMISSION_DEFINER_APK_NAME =
            "CtsRuntimePermissionDefinerApp";
    private static final String RUNTIME_PERMISSION_USER_APK_NAME =
            "CtsRuntimePermissionUserApp";
    private static final String INSTALLTIME_PERMISSION_DEFINER_APK_NAME =
            "CtsInstalltimePermissionDefinerApp";
    private static final String INSTALLTIME_PERMISSION_USER_APK_NAME =
            "CtsInstalltimePermissionUserApp";

    private Context mContext;
    private Instrumentation mInstrumentation;
    private Object mMySync = new Object();

    @Before
    public void setContextAndInstrumentation() {
        mContext = InstrumentationRegistry.getTargetContext();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Before
    public void wakeUpScreen() {
        SystemUtil.runShellCommand("input keyevent KEYCODE_WAKEUP");
    }

    private boolean permissionGranted(String pkgName, String permName)
            throws PackageManager.NameNotFoundException {
        PackageInfo appInfo = mContext.getPackageManager().getPackageInfo(pkgName,
                GET_PERMISSIONS);

        for (int i = 0; i < appInfo.requestedPermissions.length; i++) {
            if (appInfo.requestedPermissions[i].equals(permName)
                    && ((appInfo.requestedPermissionsFlags[i] & REQUESTED_PERMISSION_GRANTED)
                    != 0)) {
                return true;
            }
        }
        return false;
    }

    private void installApp(String apk) throws InterruptedException {
        String installResult = SystemUtil.runShellCommand(
                "pm install -r -d data/local/tmp/cts/permissions/" + apk + ".apk");
        synchronized (mMySync) {
            mMySync.wait(10000);
        }
        assertEquals("Success", installResult.trim());
    }

    private void uninstallApp(String pkg) throws InterruptedException {
        String uninstallResult = SystemUtil.runShellCommand(
                "pm uninstall " + pkg);
        synchronized (mMySync) {
            mMySync.wait(10000);
        }
        assertEquals("Success", uninstallResult.trim());
    }

    private void grantPermission(String pkg, String permission) {
        mInstrumentation.getUiAutomation().grantRuntimePermission(
                pkg, permission);
    }

    @SecurityTest
    @Test
    public void permissionShouldBeRevokedIfRemoved() throws Throwable {
        installApp(ADVERSARIAL_PERMISSION_DEFINER_APK_NAME);
        installApp(ADVERSARIAL_PERMISSION_USER_APK_NAME);

        grantPermission(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION);
        assertTrue(permissionGranted(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION));

        // Uninstall app which defines a permission with the same name as in victim app.
        // Install the victim app.
        uninstallApp(ADVERSARIAL_PERMISSION_DEFINER_PKG_NAME);
        installApp(VICTIM_PERMISSION_DEFINER_APK_NAME);
        assertFalse(permissionGranted(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION));
        uninstallApp(ADVERSARIAL_PERMISSION_USER_PKG_NAME);
        uninstallApp(VICTIM_PERMISSION_DEFINER_PKG_NAME);
    }

    @Test
    public void permissionShouldRemainGrantedAfterAppUpdate() throws Throwable {
        installApp(RUNTIME_PERMISSION_DEFINER_APK_NAME);
        installApp(RUNTIME_PERMISSION_USER_APK_NAME);

        grantPermission(RUNTIME_PERMISSION_USER_PKG_NAME, TEST_RUNTIME_PERMISSION);
        assertTrue(permissionGranted(RUNTIME_PERMISSION_USER_PKG_NAME, TEST_RUNTIME_PERMISSION));

        // Install app which defines a permission. This is similar to update the app
        // operation
        installApp(RUNTIME_PERMISSION_DEFINER_APK_NAME);
        assertTrue(permissionGranted(RUNTIME_PERMISSION_USER_PKG_NAME, TEST_RUNTIME_PERMISSION));
        uninstallApp(RUNTIME_PERMISSION_USER_PKG_NAME);
        uninstallApp(RUNTIME_PERMISSION_DEFINER_PKG_NAME);
    }

    @Test
    public void runtimePermissionDependencyTest() throws Throwable {
        installApp(ADVERSARIAL_PERMISSION_USER_APK_NAME);
        // Should fail to grant permission because its definer is not installed yet
        try {
            grantPermission(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION);
            fail("Should have thrown security exception above");
        } catch (SecurityException expected) {
        }
        assertFalse(permissionGranted(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION));
        // Now install the permission definer; should be able to grant permission to user package
        installApp(ADVERSARIAL_PERMISSION_DEFINER_APK_NAME);
        grantPermission(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION);
        assertTrue(permissionGranted(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION));
        // Now uninstall the permission definer; the user packages' permission should be revoked
        uninstallApp(ADVERSARIAL_PERMISSION_DEFINER_PKG_NAME);
        assertFalse(permissionGranted(ADVERSARIAL_PERMISSION_USER_PKG_NAME, TEST_PERMISSION));

        uninstallApp(ADVERSARIAL_PERMISSION_USER_PKG_NAME);
    }

    @Test
    public void installtimePermissionDependencyTest() throws Throwable {
        installApp(INSTALLTIME_PERMISSION_USER_APK_NAME);
        // Should not have the permission auto-granted
        assertFalse(permissionGranted(
                INSTALLTIME_PERMISSION_USER_PKG_NAME, TEST_INSTALLTIME_PERMISSION));
        // Now install the permission definer; user package should have the permission auto granted
        installApp(INSTALLTIME_PERMISSION_DEFINER_APK_NAME);
        installApp(INSTALLTIME_PERMISSION_USER_APK_NAME);
        assertTrue(permissionGranted(
                INSTALLTIME_PERMISSION_USER_PKG_NAME, TEST_INSTALLTIME_PERMISSION));
        // Now uninstall the permission definer; the user packages' permission will not be revoked
        uninstallApp(INSTALLTIME_PERMISSION_DEFINER_PKG_NAME);
        assertTrue(permissionGranted(
                INSTALLTIME_PERMISSION_USER_PKG_NAME, TEST_INSTALLTIME_PERMISSION));

        uninstallApp(INSTALLTIME_PERMISSION_USER_PKG_NAME);
    }
}
