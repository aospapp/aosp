/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app.cts;

import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.app.PictureInPictureParams;
import android.app.stubs.PipActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.test.ActivityInstrumentationTestCase2;

import androidx.test.filters.FlakyTest;

import java.util.function.BooleanSupplier;

/**
 * Run: atest android.app.cts.PipActivityTest
 */
@Presubmit
@FlakyTest(detail = "Promote to presubmit when shown to be stable.")
public class PipActivityTest extends ActivityInstrumentationTestCase2<PipActivity> {

    private static final long TIME_SLICE_MS = 250;
    private static final long MAX_WAIT_MS = 5000;

    private Instrumentation mInstrumentation;
    private PipActivity mActivity;

    public PipActivityTest() {
        super("android.app.stubs", PipActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mInstrumentation = getInstrumentation();
        mActivity = getActivity();
    }

    public void testLaunchPipActivity() throws Throwable {
        final boolean supportsPip =
                mActivity.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE);

        if (supportsPip) {
            mActivity.enterPictureInPictureMode();

            // Entering PIP mode is not synchronous, so wait for the expected callbacks
            waitAndAssertCondition(() -> {
                return mActivity.getMultiWindowChangedCount() == 1 &&
                        mActivity.getPictureInPictureModeChangedCount() == 1;
            }, "Pip/mw changed when going into picture-in-picture mode");

            // Ensure that the current state and also the last reported callback values match
            assertTrue(mActivity.isInMultiWindowMode());
            assertTrue(mActivity.isInPictureInPictureMode());
            assertTrue(mActivity.getLastReportedMultiWindowMode());
            assertTrue(mActivity.getLastReporterPictureInPictureMode());
        } else {
            assertTrue(!mActivity.enterPictureInPictureMode(
                    new PictureInPictureParams.Builder().build()));

            // Entering PIP mode is not synchronous, so waiting for completion of all work
            SystemClock.sleep(5000);

            // Ensure that the current state and also the last reported callback
            // values match
            assertFalse(mActivity.isInMultiWindowMode());
            assertFalse(mActivity.isInPictureInPictureMode());
            assertFalse(mActivity.getLastReportedMultiWindowMode());
            assertFalse(mActivity.getLastReporterPictureInPictureMode());
        }
        mInstrumentation.waitForIdleSync();

        if (supportsPip) {
            // Relaunch the activity to make it fullscreen
            Intent intent = Intent.makeMainActivity(new ComponentName("android.app.stubs",
                    "android.app.stubs.PipActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mInstrumentation.getContext().startActivity(intent);

            // Exiting PIP mode is not synchronous, so wait for the expected callbacks
            waitAndAssertCondition(() -> {
                return mActivity.getMultiWindowChangedCount() == 2 &&
                        mActivity.getPictureInPictureModeChangedCount() == 2;
            }, "Pip/mw callback when going to fullscreen mode");

            // Ensure that the current state and also the last reported callback values match
            assertFalse(mActivity.getLastReportedMultiWindowMode());
            assertFalse(mActivity.getLastReporterPictureInPictureMode());
        }
    }

    private void waitAndAssertCondition(BooleanSupplier condition, String failMsgContext) {
        long startTime = SystemClock.elapsedRealtime();
        while (true) {
            if (condition.getAsBoolean()) {
                // Condition passed
                return;
            } else if (SystemClock.elapsedRealtime() > (startTime + MAX_WAIT_MS)) {
                // Timed out
                fail("Timed out waiting for: " + failMsgContext);
            } else {
                SystemClock.sleep(TIME_SLICE_MS);
            }
        }
    }
}
