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

package android.server.wm;

import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.StateLogger.log;
import static android.server.wm.StateLogger.logE;
import static android.server.wm.app.Components.FONT_SCALE_ACTIVITY;
import static android.server.wm.app.Components.FONT_SCALE_NO_RELAUNCH_ACTIVITY;
import static android.server.wm.app.Components.FontScaleActivity.EXTRA_FONT_ACTIVITY_DPI;
import static android.server.wm.app.Components.FontScaleActivity.EXTRA_FONT_PIXEL_SIZE;
import static android.server.wm.app.Components.NO_RELAUNCH_ACTIVITY;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.app.Components.TestActivity.EXTRA_CONFIG_ASSETS_SEQ;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.ComponentName;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.server.wm.CommandSession.ActivityCallback;
import android.server.wm.TestJournalProvider.TestJournalContainer;
import android.server.wm.settings.SettingsSession;

import androidx.annotation.IntDef;
import androidx.test.filters.FlakyTest;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:ConfigChangeTests
 */
@Presubmit
public class ConfigChangeTests extends ActivityManagerTestBase {

    private static final float EXPECTED_FONT_SIZE_SP = 10.0f;

    /** Verifies if the count of configuration changes is expected. */
    private static final int TEST_MODE_CONFIGURATION_CHANGE = 1;
    /** Verifies if the count of relaunch is expected. */
    private static final int TEST_MODE_RELAUNCH_OR_CONFIG_CHANGE = 2;
    /** Verifies if sizes match. */
    private static final int TEST_MODE_RESIZE = 3;

    /** Test mode that defines which lifecycle callback is verified in a particular test */
    @IntDef(flag = true, value = {
            TEST_MODE_CONFIGURATION_CHANGE,
            TEST_MODE_RELAUNCH_OR_CONFIG_CHANGE,
            TEST_MODE_RESIZE
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface TestMode {}

    @Test
    public void testRotation90Relaunch() throws Exception{
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        // Should relaunch on every rotation and receive no onConfigurationChanged()
        testRotation(TEST_ACTIVITY, 1, 1, 0);
    }

    @Test
    public void testRotation90NoRelaunch() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        // Should receive onConfigurationChanged() on every rotation and no relaunch
        testRotation(NO_RELAUNCH_ACTIVITY, 1, 0, 1);
    }

    @Test
    public void testRotation180_RegularActivity() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());
        assumeFalse("Skipping test: display cutout present, can't predict exact lifecycle",
                hasDisplayCutout());

        // Should receive nothing
        testRotation(TEST_ACTIVITY, 2, 0, 0);
    }

    @Test
    public void testRotation180_NoRelaunchActivity() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());
        assumeFalse("Skipping test: display cutout present, can't predict exact lifecycle",
                hasDisplayCutout());

        // Should receive nothing
        testRotation(NO_RELAUNCH_ACTIVITY, 2, 0, 0);
    }

    @Test
    @FlakyTest(bugId = 110533226, detail = "Promote to presubmit once confirm it's not flaky")
    public void testRotation180RelaunchWithCutout() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());
        assumeTrue("Skipping test: no display cutout", hasDisplayCutout());

        testRotation180WithCutout(TEST_ACTIVITY, TEST_MODE_RELAUNCH_OR_CONFIG_CHANGE);
    }

    @Test
    @FlakyTest(bugId = 110533226, detail = "Promote to presubmit once confirm it's not flaky")
    public void testRotation180NoRelaunchWithCutout() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());
        assumeTrue("Skipping test: no display cutout", hasDisplayCutout());

        testRotation180WithCutout(NO_RELAUNCH_ACTIVITY, TEST_MODE_CONFIGURATION_CHANGE);
    }

    /**
     * Test activity configuration changes for devices with cutout(s). Landscape and
     * reverse-landscape rotations should result in same screen space available for apps.
     */
    @Test
    @FlakyTest(bugId = 110533226, detail = "Promote to presubmit once confirm it's not flaky")
    public void testConfigChangeWhenRotatingWithCutout() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());
        assumeTrue("Skipping test: no display cutout", hasDisplayCutout());

        testRotation180WithCutout(TEST_ACTIVITY, TEST_MODE_RESIZE);
    }

    private void testRotation180WithCutout(ComponentName activityName, @TestMode int testMode)
            throws Exception {
        launchActivity(activityName);
        mAmWmState.computeState(activityName);

        try(final RotationSession rotationSession = new RotationSession()) {
            final StateCount count1 = getStateCountForRotation(activityName, rotationSession,
                    ROTATION_0 /* before */, ROTATION_180 /* after */);
            final StateCount count2 = getStateCountForRotation(activityName, rotationSession,
                    ROTATION_90 /* before */, ROTATION_270 /* after */);

            final int configChange = count1.mConfigChangeCount + count2.mConfigChangeCount;
            final int relaunch = count1.mRelaunchCount + count2.mRelaunchCount;
            // There should at least one 180 rotation without resize.
            final boolean sameSize = !count1.mResize || !count2.mResize;

            switch(testMode) {
                case TEST_MODE_CONFIGURATION_CHANGE: {
                    assertTrue("There must be at most one 180 degree rotation that results in the"
                            + " same configuration on device with cutout", configChange <= 1);
                    assertEquals("There must be no relaunch during test", 0, relaunch);
                    break;
                }
                case TEST_MODE_RELAUNCH_OR_CONFIG_CHANGE: {
                    // If the size change does not cross the threshold, the activity will receive
                    // onConfigurationChanged instead of relaunching.
                    assertTrue("There must be at most one 180 degree rotation that results in"
                            + " relaunch or a configuration change on device with cutout",
                            relaunch + configChange <= 1);
                    break;
                }
                case TEST_MODE_RESIZE: {
                    assertTrue("A device with cutout should have the same available screen space"
                            + " in landscape and reverse-landscape", sameSize);
                    break;
                }
                default: {
                    fail("unrecognized test mode: " + testMode);
                }
            }
        }
    }

    private StateCount getStateCountForRotation(ComponentName activityName, RotationSession session,
            int before, int after) throws Exception {
        session.set(before);
        separateTestJournal();
        session.set(after);
        mAmWmState.computeState(activityName);
        final ActivityLifecycleCounts counter = new ActivityLifecycleCounts(activityName);

        int configChangeCount = counter.getCount(ActivityCallback.ON_CONFIGURATION_CHANGED);
        int relaunchCount = counter.getCount(ActivityCallback.ON_CREATE);
        boolean resize = getLastReportedSizesForActivity(activityName) != null;

        return new StateCount(configChangeCount, relaunchCount, resize);
    }

    private final static class StateCount {
        final int mConfigChangeCount;
        final int mRelaunchCount;
        final boolean mResize;

        StateCount(int configChangeCount, int relaunchCount, boolean resize) {
            mConfigChangeCount = configChangeCount;
            mRelaunchCount = relaunchCount;
            mResize = resize;
        }
    }

    @Test
    public void testChangeFontScaleRelaunch() throws Exception {
        // Should relaunch and receive no onConfigurationChanged()
        testChangeFontScale(FONT_SCALE_ACTIVITY, true /* relaunch */);
    }

    @Test
    public void testChangeFontScaleNoRelaunch() throws Exception {
        // Should receive onConfigurationChanged() and no relaunch
        testChangeFontScale(FONT_SCALE_NO_RELAUNCH_ACTIVITY, false /* relaunch */);
    }

    private void testRotation(ComponentName activityName, int rotationStep, int numRelaunch,
            int numConfigChange) throws Exception {
        launchActivity(activityName);

        mAmWmState.computeState(activityName);

        final int initialRotation = 4 - rotationStep;
        try (final RotationSession rotationSession = new RotationSession()) {
            rotationSession.set(initialRotation);
            mAmWmState.computeState(activityName);
            final int actualStackId =
                    mAmWmState.getAmState().getTaskByActivity(activityName).mStackId;
            final int displayId = mAmWmState.getAmState().getStackById(actualStackId).mDisplayId;
            final int newDeviceRotation = getDeviceRotation(displayId);
            if (newDeviceRotation == INVALID_DEVICE_ROTATION) {
                logE("Got an invalid device rotation value. "
                        + "Continuing the test despite of that, but it is likely to fail.");
            } else if (newDeviceRotation != initialRotation) {
                log("This device doesn't support user rotation "
                        + "mode. Not continuing the rotation checks.");
                return;
            }

            for (int rotation = 0; rotation < 4; rotation += rotationStep) {
                separateTestJournal();
                rotationSession.set(rotation);
                mAmWmState.computeState(activityName);
                assertRelaunchOrConfigChanged(activityName, numRelaunch, numConfigChange);
            }
        }
    }

    /** Helper class to save, set, and restore font_scale preferences. */
    private static class FontScaleSession extends SettingsSession<Float> {
        FontScaleSession() {
            super(Settings.System.getUriFor(Settings.System.FONT_SCALE),
                    Settings.System::getFloat,
                    Settings.System::putFloat);
        }
    }

    private void testChangeFontScale(
            ComponentName activityName, boolean relaunch) throws Exception {
        try (final FontScaleSession fontScaleSession = new FontScaleSession()) {
            fontScaleSession.set(1.0f);
            separateTestJournal();
            launchActivity(activityName);
            mAmWmState.computeState(activityName);

            final int densityDpi = getActivityDensityDpi(activityName);

            for (float fontScale = 0.85f; fontScale <= 1.3f; fontScale += 0.15f) {
                separateTestJournal();
                fontScaleSession.set(fontScale);
                mAmWmState.computeState(activityName);
                assertRelaunchOrConfigChanged(activityName, relaunch ? 1 : 0, relaunch ? 0 : 1);

                // Verify that the display metrics are updated, and therefore the text size is also
                // updated accordingly.
                assertExpectedFontPixelSize(activityName,
                        scaledPixelsToPixels(EXPECTED_FONT_SIZE_SP, fontScale, densityDpi));
            }
        }
    }

    /**
     * Test updating application info when app is running. An activity with matching package name
     * must be recreated and its asset sequence number must be incremented.
     */
    @Test
    public void testUpdateApplicationInfo() throws Exception {
        separateTestJournal();

        // Launch an activity that prints applied config.
        launchActivity(TEST_ACTIVITY);
        final int assetSeq = getAssetSeqNumber(TEST_ACTIVITY);

        separateTestJournal();
        // Update package info.
        updateApplicationInfo(Arrays.asList(TEST_ACTIVITY.getPackageName()));
        mAmWmState.waitForWithAmState((amState) -> {
            // Wait for activity to be resumed and asset seq number to be updated.
            try {
                return getAssetSeqNumber(TEST_ACTIVITY) == assetSeq + 1
                        && amState.hasActivityState(TEST_ACTIVITY, STATE_RESUMED);
            } catch (Exception e) {
                logE("Error waiting for valid state: " + e.getMessage());
                return false;
            }
        }, "Waiting asset sequence number to be updated and for activity to be resumed.");

        // Check if activity is relaunched and asset seq is updated.
        assertRelaunchOrConfigChanged(TEST_ACTIVITY, 1 /* numRelaunch */,
                0 /* numConfigChange */);
        final int newAssetSeq = getAssetSeqNumber(TEST_ACTIVITY);
        assertEquals("Asset sequence number must be incremented.", assetSeq + 1, newAssetSeq);
    }

    private static int getAssetSeqNumber(ComponentName activityName) {
        return TestJournalContainer.get(activityName).extras.getInt(EXTRA_CONFIG_ASSETS_SEQ);
    }

    // Calculate the scaled pixel size just like the device is supposed to.
    private static int scaledPixelsToPixels(float sp, float fontScale, int densityDpi) {
        final int DEFAULT_DENSITY = 160;
        float f = densityDpi * (1.0f / DEFAULT_DENSITY) * fontScale * sp;
        return (int) ((f >= 0) ? (f + 0.5f) : (f - 0.5f));
    }

    private static int getActivityDensityDpi(ComponentName activityName)
            throws Exception {
        final Bundle extras = TestJournalContainer.get(activityName).extras;
        if (!extras.containsKey(EXTRA_FONT_ACTIVITY_DPI)) {
            fail("No fontActivityDpi reported from activity " + activityName);
            return -1;
        }
        return extras.getInt(EXTRA_FONT_ACTIVITY_DPI);
    }

    private void assertExpectedFontPixelSize(ComponentName activityName, int fontPixelSize)
            throws Exception {
        final Bundle extras = TestJournalContainer.get(activityName).extras;
        if (!extras.containsKey(EXTRA_FONT_PIXEL_SIZE)) {
            fail("No fontPixelSize reported from activity " + activityName);
        }
        assertEquals("Expected font pixel size does not match", fontPixelSize,
                extras.getInt(EXTRA_FONT_PIXEL_SIZE));
    }

    private void updateApplicationInfo(List<String> packages) {
        SystemUtil.runWithShellPermissionIdentity(
                () -> mAm.scheduleApplicationInfoChanged(packages,
                        android.os.Process.myUserHandle().getIdentifier())
        );
    }
}
