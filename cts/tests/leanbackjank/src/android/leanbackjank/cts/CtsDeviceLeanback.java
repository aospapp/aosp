/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.leanbackjank.cts;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.leanbackjank.app.IntentKeys;
import android.os.SystemClock;
import android.os.SystemProperties;

import androidx.test.jank.GfxMonitor;
import androidx.test.jank.JankTest;
import androidx.test.jank.WindowContentFrameStatsMonitor;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Until;

public class CtsDeviceLeanback extends CtsJankTestBase {
    private static final String TAG = "CtsDeviceLeanback";
    private static final int MILLIS_PER_SECOND = 1000;
    private static final long WAIT_TIMEOUT = 5 * MILLIS_PER_SECOND;
    private static final int SCROLL_COUNT = 100;
    private static final int SCROLL_INTERVAL_MILLIS = 200;
    private static final int PRE_SCROLL_DELAY_MILLIS = 500;
    private static final int PRE_SCROLL_IDLE_TIME = 2 * MILLIS_PER_SECOND;
    private static final int SAMPLING_DURATION_SECONDS = 2;
    private static final int SAMPLING_DURATION_MILLIS =
            SAMPLING_DURATION_SECONDS * MILLIS_PER_SECOND;
    private final static String APP_PACKAGE = "android.leanbackjank.app";
    private final static String JAVA_PACKAGE = "android.leanbackjank.app.ui";
    private final static String CLASS = JAVA_PACKAGE + ".MainActivity";

    private boolean shouldSkip() {
        PackageManager packageManager =
                getInstrumentation().getTargetContext().getPackageManager();
        if (!packageManager.hasSystemFeature(
                PackageManager.FEATURE_LEANBACK)) {
            return true;
        }

        // Emulator is not a performant device, and can't succeed at this test.
        if (SystemProperties.get("ro.build.characteristics").equals("emulator")) {
            return true;
        }

        return false;
    }

    @Override
    protected void runTest() throws Throwable {
        if (shouldSkip()) {
            return;
        }
        super.runTest();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (shouldSkip()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(APP_PACKAGE, CLASS));

        // Trigger automated scroll of the helper app.
        intent.putExtra(IntentKeys.SCROLL_DELAY, PRE_SCROLL_DELAY_MILLIS);
        intent.putExtra(IntentKeys.SCROLL_COUNT, SCROLL_COUNT);
        intent.putExtra(IntentKeys.SCROLL_INTERVAL, SCROLL_INTERVAL_MILLIS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        getInstrumentation().getTargetContext().startActivity(intent);
        if (!getUiDevice().wait(Until.hasObject(By.pkg(APP_PACKAGE)), WAIT_TIMEOUT)) {
            fail("Test helper app package not found on device");
        }

        // Wait until scroll animation starts.
        SystemClock.sleep(PRE_SCROLL_IDLE_TIME);
    }

    @Override
    protected void tearDown() throws Exception {
        getUiDevice().pressHome();
        super.tearDown();
    }

    // Requires at least 50 fps on average to pass the test.
    @JankTest(expectedFrames = 50 * SAMPLING_DURATION_SECONDS, defaultIterationCount = 2)
    @GfxMonitor(processName = APP_PACKAGE)
    @WindowContentFrameStatsMonitor
    public void testScrollingByTimer() {
        SystemClock.sleep(SAMPLING_DURATION_MILLIS);
    }
}
