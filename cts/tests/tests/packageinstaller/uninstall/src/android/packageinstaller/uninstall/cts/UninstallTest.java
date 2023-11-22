/*
 * Copyright (C) 2018 Google Inc.
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
package android.packageinstaller.uninstall.cts;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.platform.test.annotations.AppModeFull;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.AppOpsUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
@AppModeFull
public class UninstallTest {
    private static final String LOG_TAG = UninstallTest.class.getSimpleName();

    private static final String TEST_APK_PACKAGE_NAME = "android.packageinstaller.emptytestapp.cts";

    private static final long TIMEOUT_MS = 30000;
    private static final String APP_OP_STR = "REQUEST_DELETE_PACKAGES";

    private Context mContext;
    private UiDevice mUiDevice;

    @Before
    public void setup() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();

        // Unblock UI
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        if (!mUiDevice.isScreenOn()) {
            mUiDevice.wakeUp();
        }
        mUiDevice.executeShellCommand("wm dismiss-keyguard");
        AppOpsUtils.reset(mContext.getPackageName());
    }

    private void dumpWindowHierarchy() throws InterruptedException, IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mUiDevice.dumpWindowHierarchy(outputStream);
        String windowHierarchy = outputStream.toString(StandardCharsets.UTF_8.name());

        Log.w(LOG_TAG, "Window hierarchy:");
        for (String line : windowHierarchy.split("\n")) {
            Thread.sleep(10);
            Log.w(LOG_TAG, line);
        }
    }

    @Test
    public void testUninstall() throws Exception {
        assertTrue(isInstalled());

        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        intent.setData(Uri.parse("package:" + TEST_APK_PACKAGE_NAME));
        intent.addFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        if (mUiDevice.wait(Until.findObject(By.text("Do you want to uninstall this app?")),
                TIMEOUT_MS) == null) {
            dumpWindowHierarchy();
        }
        assertNotNull("Uninstall prompt not shown",
                mUiDevice.wait(Until.findObject(By.text("Do you want to uninstall this app?")),
                        TIMEOUT_MS));
        // The app's name should be shown to the user.
        assertNotNull(mUiDevice.findObject(By.text("Empty Test App")));

        // Confirm uninstall
        mUiDevice.findObject(By.text("OK")).click();

        for (int i = 0; i < 30; i++) {
            // We can't detect the confirmation Toast with UiAutomator, so we'll poll
            Thread.sleep(500);
            if (!isInstalled()) {
                break;
            }
        }
        assertFalse("Package wasn't uninstalled.", isInstalled());
        assertTrue(AppOpsUtils.allowedOperationLogged(mContext.getPackageName(), APP_OP_STR));
    }

    private boolean isInstalled() {
        try {
            mContext.getPackageManager().getPackageInfo(TEST_APK_PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
