/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.server.wm.ActivityAndWindowManagersState.dpToPx;
import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.ComponentNameUtils.getWindowName;
import static android.server.wm.StateLogger.logE;
import static android.server.wm.app.Components.BROADCAST_RECEIVER_ACTIVITY;
import static android.server.wm.app.Components.DIALOG_WHEN_LARGE_ACTIVITY;
import static android.server.wm.app.Components.LANDSCAPE_ORIENTATION_ACTIVITY;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.server.wm.app.Components.NIGHT_MODE_ACTIVITY;
import static android.server.wm.app.Components.PORTRAIT_ORIENTATION_ACTIVITY;
import static android.server.wm.app.Components.RESIZEABLE_ACTIVITY;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.translucentapp.Components.TRANSLUCENT_LANDSCAPE_ACTIVITY;
import static android.server.wm.translucentapp26.Components.SDK26_TRANSLUCENT_LANDSCAPE_ACTIVITY;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.server.wm.CommandSession.SizeInfo;

import androidx.test.filters.FlakyTest;

import org.junit.Ignore;
import org.junit.Test;

import java.util.List;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:AppConfigurationTests
 */
@Presubmit
public class AppConfigurationTests extends ActivityManagerTestBase {

    private static final int SMALL_WIDTH_DP = 426;
    private static final int SMALL_HEIGHT_DP = 320;

    /**
     * Tests that the WindowManager#getDefaultDisplay() and the Configuration of the Activity
     * has an updated size when the Activity is resized from fullscreen to docked state.
     *
     * The Activity handles configuration changes, so it will not be restarted between resizes.
     * On Configuration changes, the Activity logs the Display size and Configuration width
     * and heights. The values reported in fullscreen should be larger than those reported in
     * docked state.
     */
    @Test
    public void testConfigurationUpdatesWhenResizedFromFullscreen() {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        separateTestJournal();
        launchActivity(RESIZEABLE_ACTIVITY, WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        final SizeInfo fullscreenSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);

        separateTestJournal();
        setActivityTaskWindowingMode(RESIZEABLE_ACTIVITY, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final SizeInfo dockedSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);

        assertSizesAreSane(fullscreenSizes, dockedSizes);
    }

    /**
     * Same as {@link #testConfigurationUpdatesWhenResizedFromFullscreen()} but resizing
     * from docked state to fullscreen (reverse).
     */
    @Test
    @FlakyTest(bugId = 71792393)
    public void testConfigurationUpdatesWhenResizedFromDockedStack() {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        separateTestJournal();
        launchActivity(RESIZEABLE_ACTIVITY, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final SizeInfo dockedSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);

        separateTestJournal();
        setActivityTaskWindowingMode(RESIZEABLE_ACTIVITY, WINDOWING_MODE_FULLSCREEN);
        final SizeInfo fullscreenSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);

        assertSizesAreSane(fullscreenSizes, dockedSizes);
    }

    /**
     * Tests whether the Display sizes change when rotating the device.
     */
    @Test
    public void testConfigurationUpdatesWhenRotatingWhileFullscreen() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        try (final RotationSession rotationSession = new RotationSession()) {
            rotationSession.set(ROTATION_0);

            separateTestJournal();
            launchActivity(RESIZEABLE_ACTIVITY,
                    WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
            final SizeInfo initialSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);

            rotateAndCheckSizes(rotationSession, initialSizes);
        }
    }

    /**
     * Same as {@link #testConfigurationUpdatesWhenRotatingWhileFullscreen()} but when the Activity
     * is in the docked stack.
     */
    @Test
    public void testConfigurationUpdatesWhenRotatingWhileDocked() throws Exception {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        try (final RotationSession rotationSession = new RotationSession()) {
            rotationSession.set(ROTATION_0);

            separateTestJournal();
            // Launch our own activity to side in case Recents (or other activity to side) doesn't
            // support rotation.
            launchActivitiesInSplitScreen(
                    getLaunchActivityBuilder().setTargetActivity(LAUNCHING_ACTIVITY),
                    getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY));
            // Launch target activity in docked stack.
            getLaunchActivityBuilder().setTargetActivity(RESIZEABLE_ACTIVITY).execute();
            final SizeInfo initialSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);

            rotateAndCheckSizes(rotationSession, initialSizes);
        }
    }

    /**
     * Same as {@link #testConfigurationUpdatesWhenRotatingWhileDocked()} but when the Activity
     * is launched to side from docked stack.
     */
    @Test
    public void testConfigurationUpdatesWhenRotatingToSideFromDocked() throws Exception {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        try (final RotationSession rotationSession = new RotationSession()) {
            rotationSession.set(ROTATION_0);

            separateTestJournal();
            launchActivitiesInSplitScreen(
                    getLaunchActivityBuilder().setTargetActivity(LAUNCHING_ACTIVITY),
                    getLaunchActivityBuilder().setTargetActivity(RESIZEABLE_ACTIVITY));
            final SizeInfo initialSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);

            rotateAndCheckSizes(rotationSession, initialSizes);
        }
    }

    private void rotateAndCheckSizes(RotationSession rotationSession, SizeInfo prevSizes)
            throws Exception {
        final ActivityManagerState.ActivityTask task =
                mAmWmState.getAmState().getTaskByActivity(RESIZEABLE_ACTIVITY);
        final int displayId = mAmWmState.getAmState().getStackById(task.mStackId).mDisplayId;

        assumeTrue(supportsLockedUserRotation(rotationSession, displayId));

        final int[] rotations = { ROTATION_270, ROTATION_180, ROTATION_90, ROTATION_0 };
        for (final int rotation : rotations) {
            separateTestJournal();
            rotationSession.set(rotation);
            final int newDeviceRotation = getDeviceRotation(displayId);
            if (newDeviceRotation == INVALID_DEVICE_ROTATION) {
                logE("Got an invalid device rotation value. "
                        + "Continuing the test despite of that, but it is likely to fail.");
            }

            final SizeInfo rotatedSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY);
            assertSizesRotate(prevSizes, rotatedSizes,
                    // Skip orientation checks if we are not in fullscreen mode.
                    task.getWindowingMode() != WINDOWING_MODE_FULLSCREEN);
            prevSizes = rotatedSizes;
        }
    }

    /**
     * Tests when activity moved from fullscreen stack to docked and back. Activity will be
     * relaunched twice and it should have same config as initial one.
     */
    @Test
    public void testSameConfigurationFullSplitFullRelaunch() {
        moveActivityFullSplitFull(TEST_ACTIVITY);
    }

    /**
     * Same as {@link #testSameConfigurationFullSplitFullRelaunch} but without relaunch.
     */
    @Test
    public void testSameConfigurationFullSplitFullNoRelaunch() {
        moveActivityFullSplitFull(RESIZEABLE_ACTIVITY);
    }

    /**
     * Launches activity in fullscreen stack, moves to docked stack and back to fullscreen stack.
     * Last operation is done in a way which simulates split-screen divider movement maximizing
     * docked stack size and then moving task to fullscreen stack - the same way it is done when
     * user long-presses overview/recents button to exit split-screen.
     * Asserts that initial and final reported sizes in fullscreen stack are the same.
     */
    private void moveActivityFullSplitFull(ComponentName activityName) {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        // Launch to fullscreen stack and record size.
        separateTestJournal();
        launchActivity(activityName, WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        final SizeInfo initialFullscreenSizes = getActivityDisplaySize(activityName);
        final Rect displayRect = getDisplayRect(activityName);

        // Move to docked stack.
        separateTestJournal();
        setActivityTaskWindowingMode(activityName, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final SizeInfo dockedSizes = getActivityDisplaySize(activityName);
        assertSizesAreSane(initialFullscreenSizes, dockedSizes);
        // Make sure docked stack is focused. This way when we dismiss it later fullscreen stack
        // will come up.
        launchActivity(activityName, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        mAmWmState.computeState(false /* compareTaskAndStackBounds */,
                new WaitForValidActivityState.Builder(activityName).build());
        final ActivityManagerState.ActivityStack stack = mAmWmState.getAmState()
                .getStandardStackByWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);

        // Resize docked stack to fullscreen size. This will trigger activity relaunch with
        // non-empty override configuration corresponding to fullscreen size.
        separateTestJournal();
        resizeStack(stack.mStackId, displayRect.left, displayRect.top, displayRect.width(),
                displayRect.height());

        // Move activity back to fullscreen stack.
        setActivityTaskWindowingMode(activityName,
                WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        final SizeInfo finalFullscreenSizes = getActivityDisplaySize(activityName);

        // After activity configuration was changed twice it must report same size as original one.
        assertSizesAreSame(initialFullscreenSizes, finalFullscreenSizes);
    }

    /**
     * Tests when activity moved from docked stack to fullscreen and back. Activity will be
     * relaunched twice and it should have same config as initial one.
     */
    @Test
    @FlakyTest
    public void testSameConfigurationSplitFullSplitRelaunch() {
        moveActivitySplitFullSplit(TEST_ACTIVITY);
    }

    /**
     * Same as {@link #testSameConfigurationSplitFullSplitRelaunch} but without relaunch.
     */
    @Test
    @FlakyTest
    public void testSameConfigurationSplitFullSplitNoRelaunch() {
        moveActivitySplitFullSplit(RESIZEABLE_ACTIVITY);
    }

    /**
     * Tests that an activity with the DialogWhenLarge theme can transform properly when in split
     * screen.
     */
    @Test
    @FlakyTest(bugId = 110276714)
    public void testDialogWhenLargeSplitSmall() {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        launchActivity(DIALOG_WHEN_LARGE_ACTIVITY, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final ActivityManagerState.ActivityStack stack = mAmWmState.getAmState()
                .getStandardStackByWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final WindowManagerState.Display display =
                mAmWmState.getWmState().getDisplay(stack.mDisplayId);
        final int density = display.getDpi();
        final int smallWidthPx = dpToPx(SMALL_WIDTH_DP, density);
        final int smallHeightPx = dpToPx(SMALL_HEIGHT_DP, density);

        resizeStack(stack.mStackId, 0, 0, smallWidthPx, smallHeightPx);
        mAmWmState.waitForValidState(
                new WaitForValidActivityState.Builder(DIALOG_WHEN_LARGE_ACTIVITY)
                        .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
                        .setActivityType(ACTIVITY_TYPE_STANDARD)
                        .build());
    }

    /**
     * Test that device handles consequent requested orientations and displays the activities.
     */
    @Test
    @FlakyTest(bugId = 71875755)
    public void testFullscreenAppOrientationRequests() {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        separateTestJournal();
        launchActivity(PORTRAIT_ORIENTATION_ACTIVITY);
        mAmWmState.assertVisibility(PORTRAIT_ORIENTATION_ACTIVITY, true /* visible */);
        SizeInfo reportedSizes = getLastReportedSizesForActivity(PORTRAIT_ORIENTATION_ACTIVITY);
        assertEquals("portrait activity should be in portrait",
                1 /* portrait */, reportedSizes.orientation);
        separateTestJournal();

        launchActivity(LANDSCAPE_ORIENTATION_ACTIVITY);
        mAmWmState.assertVisibility(LANDSCAPE_ORIENTATION_ACTIVITY, true /* visible */);
        reportedSizes = getLastReportedSizesForActivity(LANDSCAPE_ORIENTATION_ACTIVITY);
        assertEquals("landscape activity should be in landscape",
                2 /* landscape */, reportedSizes.orientation);
        separateTestJournal();

        launchActivity(PORTRAIT_ORIENTATION_ACTIVITY);
        mAmWmState.assertVisibility(PORTRAIT_ORIENTATION_ACTIVITY, true /* visible */);
        reportedSizes = getLastReportedSizesForActivity(PORTRAIT_ORIENTATION_ACTIVITY);
        assertEquals("portrait activity should be in portrait",
                1 /* portrait */, reportedSizes.orientation);
    }

    @Test
    public void testNonfullscreenAppOrientationRequests() {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        separateTestJournal();
        launchActivity(PORTRAIT_ORIENTATION_ACTIVITY, WINDOWING_MODE_FULLSCREEN);
        final SizeInfo initialReportedSizes =
                getLastReportedSizesForActivity(PORTRAIT_ORIENTATION_ACTIVITY);
        assertEquals("portrait activity should be in portrait",
                1 /* portrait */, initialReportedSizes.orientation);
        separateTestJournal();

        launchActivity(SDK26_TRANSLUCENT_LANDSCAPE_ACTIVITY, WINDOWING_MODE_FULLSCREEN);
        assertEquals("Legacy non-fullscreen activity requested landscape orientation",
                0 /* landscape */, mAmWmState.getWmState().getLastOrientation());

        // TODO(b/36897968): uncomment once we can suppress unsupported configurations
        // final ReportedSizes updatedReportedSizes =
        //      getLastReportedSizesForActivity(PORTRAIT_ACTIVITY_NAME, logSeparator);
        // assertEquals("portrait activity should not have moved from portrait",
        //         1 /* portrait */, updatedReportedSizes.orientation);
    }

    /**
     * Test that device handles consequent requested orientations and will not report a config
     * change to an invisible activity.
     */
    @Test
    @FlakyTest
    public void testAppOrientationRequestConfigChanges() {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        separateTestJournal();
        launchActivity(PORTRAIT_ORIENTATION_ACTIVITY, WINDOWING_MODE_FULLSCREEN);
        mAmWmState.assertVisibility(PORTRAIT_ORIENTATION_ACTIVITY, true /* visible */);

        assertLifecycleCounts(PORTRAIT_ORIENTATION_ACTIVITY,
                1 /* create */, 1 /* start */, 1 /* resume */,
                0 /* pause */, 0 /* stop */, 0 /* destroy */, 0 /* config */);

        launchActivity(LANDSCAPE_ORIENTATION_ACTIVITY, WINDOWING_MODE_FULLSCREEN);
        mAmWmState.assertVisibility(LANDSCAPE_ORIENTATION_ACTIVITY, true /* visible */);

        assertLifecycleCounts(PORTRAIT_ORIENTATION_ACTIVITY,
                1 /* create */, 1 /* start */, 1 /* resume */,
                1 /* pause */, 1 /* stop */, 0 /* destroy */, 0 /* config */);
        assertLifecycleCounts(LANDSCAPE_ORIENTATION_ACTIVITY,
                1 /* create */, 1 /* start */, 1 /* resume */,
                0 /* pause */, 0 /* stop */, 0 /* destroy */, 0 /* config */);

        launchActivity(PORTRAIT_ORIENTATION_ACTIVITY, WINDOWING_MODE_FULLSCREEN);
        mAmWmState.assertVisibility(PORTRAIT_ORIENTATION_ACTIVITY, true /* visible */);

        assertLifecycleCounts(PORTRAIT_ORIENTATION_ACTIVITY,
                2 /* create */, 2 /* start */, 2 /* resume */,
                1 /* pause */, 1 /* stop */, 0 /* destroy */, 0 /* config */);
        assertLifecycleCounts(LANDSCAPE_ORIENTATION_ACTIVITY,
                1 /* create */, 1 /* start */, 1 /* resume */,
                1 /* pause */, 1 /* stop */, 0 /* destroy */, 0 /* config */);
    }

    /**
     * Test that device orientation is restored when an activity that requests it is no longer
     * visible.
     */
    @Test
    public void testAppOrientationRequestConfigClears() {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        separateTestJournal();
        launchActivity(TEST_ACTIVITY);
        mAmWmState.assertVisibility(TEST_ACTIVITY, true /* visible */);
        final SizeInfo initialReportedSizes = getLastReportedSizesForActivity(TEST_ACTIVITY);
        final int initialOrientation = initialReportedSizes.orientation;

        // Launch an activity that requests different orientation and check that it will be applied
        final boolean launchingPortrait;
        if (initialOrientation == 2 /* landscape */) {
            launchingPortrait = true;
        } else if (initialOrientation == 1 /* portrait */) {
            launchingPortrait = false;
        } else {
            fail("Unexpected orientation value: " + initialOrientation);
            return;
        }
        final ComponentName differentOrientationActivity = launchingPortrait
                ? PORTRAIT_ORIENTATION_ACTIVITY : LANDSCAPE_ORIENTATION_ACTIVITY;
        separateTestJournal();
        launchActivity(differentOrientationActivity);
        mAmWmState.assertVisibility(differentOrientationActivity, true /* visible */);
        final SizeInfo rotatedReportedSizes =
                getLastReportedSizesForActivity(differentOrientationActivity);
        assertEquals("Applied orientation must correspond to activity request",
                launchingPortrait ? 1 : 2, rotatedReportedSizes.orientation);

        // Launch another activity on top and check that its orientation is not affected by previous
        // activity.
        separateTestJournal();
        launchActivity(RESIZEABLE_ACTIVITY);
        mAmWmState.assertVisibility(RESIZEABLE_ACTIVITY, true /* visible */);
        final SizeInfo finalReportedSizes = getLastReportedSizesForActivity(RESIZEABLE_ACTIVITY);
        assertEquals("Applied orientation must not be influenced by previously visible activity",
                initialOrientation, finalReportedSizes.orientation);
    }

    // TODO(b/70870253): This test seems malfunction.
    @Ignore("b/70870253")
    @Test
    public void testNonFullscreenActivityProhibited() {
        // We do not wait for the activity as it should not launch based on the restrictions around
        // specifying orientation. We instead start an activity known to launch immediately after
        // so that we can ensure processing the first activity occurred.
        launchActivityNoWait(TRANSLUCENT_LANDSCAPE_ACTIVITY);
        launchActivity(PORTRAIT_ORIENTATION_ACTIVITY);

        assertFalse("target SDK > 26 non-fullscreen activity should not reach onResume",
                mAmWmState.getAmState().containsActivity(TRANSLUCENT_LANDSCAPE_ACTIVITY));
    }

    @Test
    public void testNonFullscreenActivityPermitted() throws Exception {
        if(!supportsRotation()) {
            //cannot physically rotate the screen on automotive device, skip
            return;
        }
        try (final RotationSession rotationSession = new RotationSession()) {
            rotationSession.set(ROTATION_0);

            launchActivity(SDK26_TRANSLUCENT_LANDSCAPE_ACTIVITY);
            mAmWmState.assertResumedActivity(
                    "target SDK <= 26 non-fullscreen activity should be allowed to launch",
                    SDK26_TRANSLUCENT_LANDSCAPE_ACTIVITY);
            assertEquals("non-fullscreen activity requested landscape orientation",
                    0 /* landscape */, mAmWmState.getWmState().getLastOrientation());
        }
    }

    /**
     * Test that device handles moving between two tasks with different orientations.
     */
    @Test
    public void testTaskCloseRestoreFixedOrientation() {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        // Start landscape activity.
        launchActivity(LANDSCAPE_ORIENTATION_ACTIVITY);
        mAmWmState.assertVisibility(LANDSCAPE_ORIENTATION_ACTIVITY, true /* visible */);
        assertEquals("Fullscreen app requested landscape orientation",
                0 /* landscape */, mAmWmState.getWmState().getLastOrientation());

        // Start another activity in a different task.
        launchActivityInNewTask(BROADCAST_RECEIVER_ACTIVITY);

        // Request portrait
        mBroadcastActionTrigger.requestOrientation(SCREEN_ORIENTATION_PORTRAIT);
        mAmWmState.waitForLastOrientation(SCREEN_ORIENTATION_PORTRAIT);
        waitForBroadcastActivityReady(Configuration.ORIENTATION_PORTRAIT);

        // Finish activity
        mBroadcastActionTrigger.finishBroadcastReceiverActivity();

        // Verify that activity brought to front is in originally requested orientation.
        mAmWmState.computeState(LANDSCAPE_ORIENTATION_ACTIVITY);
        assertEquals("Should return to app in landscape orientation",
                0 /* landscape */, mAmWmState.getWmState().getLastOrientation());
    }

    /**
     * Test that device handles moving between two tasks with different orientations.
     */
    @Test
    public void testTaskCloseRestoreFreeOrientation() {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        // Start landscape activity.
        launchActivity(RESIZEABLE_ACTIVITY);
        mAmWmState.assertVisibility(RESIZEABLE_ACTIVITY, true /* visible */);
        final int initialServerOrientation = mAmWmState.getWmState().getLastOrientation();

        // Verify fixed-landscape
        separateTestJournal();
        launchActivityInNewTask(BROADCAST_RECEIVER_ACTIVITY);
        mBroadcastActionTrigger.requestOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mAmWmState.waitForLastOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        waitForBroadcastActivityReady(Configuration.ORIENTATION_LANDSCAPE);
        mBroadcastActionTrigger.finishBroadcastReceiverActivity();

        // Verify that activity brought to front is in originally requested orientation.
        mAmWmState.waitForActivityState(RESIZEABLE_ACTIVITY, STATE_RESUMED);
        SizeInfo reportedSizes = getLastReportedSizesForActivity(RESIZEABLE_ACTIVITY);
        assertNull("Should come back in original orientation", reportedSizes);
        assertEquals("Should come back in original server orientation",
                initialServerOrientation, mAmWmState.getWmState().getLastOrientation());
        assertRelaunchOrConfigChanged(RESIZEABLE_ACTIVITY, 0 /* numRelaunch */,
                0 /* numConfigChange */);

        // Verify fixed-portrait
        separateTestJournal();
        launchActivityInNewTask(BROADCAST_RECEIVER_ACTIVITY);
        mBroadcastActionTrigger.requestOrientation(SCREEN_ORIENTATION_PORTRAIT);
        mAmWmState.waitForLastOrientation(SCREEN_ORIENTATION_PORTRAIT);
        waitForBroadcastActivityReady(Configuration.ORIENTATION_PORTRAIT);
        mBroadcastActionTrigger.finishBroadcastReceiverActivity();

        // Verify that activity brought to front is in originally requested orientation.
        mAmWmState.waitForActivityState(RESIZEABLE_ACTIVITY, STATE_RESUMED);
        reportedSizes = getLastReportedSizesForActivity(RESIZEABLE_ACTIVITY);
        assertNull("Should come back in original orientation", reportedSizes);
        assertEquals("Should come back in original server orientation",
                initialServerOrientation, mAmWmState.getWmState().getLastOrientation());
        assertRelaunchOrConfigChanged(RESIZEABLE_ACTIVITY, 0 /* numRelaunch */,
                0 /* numConfigChange */);
    }

    /**
     * Test that activity orientation will change when device is rotated.
     * Also verify that occluded activity will not get config changes.
     */
    @Test
    @FlakyTest
    public void testAppOrientationWhenRotating() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        // Start resizeable activity that handles configuration changes.
        separateTestJournal();
        launchActivity(TEST_ACTIVITY);
        launchActivity(RESIZEABLE_ACTIVITY);
        mAmWmState.assertVisibility(RESIZEABLE_ACTIVITY, true /* visible */);

        final int displayId = mAmWmState.getAmState().getDisplayByActivity(RESIZEABLE_ACTIVITY);

        // Rotate the activity and check that it receives configuration changes with a different
        // orientation each time.
        try (final RotationSession rotationSession = new RotationSession()) {
            assumeTrue("Skipping test: no locked user rotation mode support.",
                    supportsLockedUserRotation(rotationSession, displayId));

            rotationSession.set(ROTATION_0);
            SizeInfo reportedSizes = getLastReportedSizesForActivity(RESIZEABLE_ACTIVITY);
            int prevOrientation = reportedSizes.orientation;

            final int[] rotations = { ROTATION_270, ROTATION_180, ROTATION_90, ROTATION_0 };
            for (final int rotation : rotations) {
                separateTestJournal();
                rotationSession.set(rotation);

                // Verify lifecycle count and orientation changes.
                assertRelaunchOrConfigChanged(RESIZEABLE_ACTIVITY, 0 /* numRelaunch */,
                        1 /* numConfigChange */);
                reportedSizes = getLastReportedSizesForActivity(RESIZEABLE_ACTIVITY);
                assertNotEquals(prevOrientation, reportedSizes.orientation);
                assertRelaunchOrConfigChanged(TEST_ACTIVITY, 0 /* numRelaunch */,
                        0 /* numConfigChange */);

                prevOrientation = reportedSizes.orientation;
            }
        }
    }

    /**
     * Test that activity orientation will not change when trying to rotate fixed-orientation
     * activity.
     * Also verify that occluded activity will not get config changes.
     */
    @Test
    public void testFixedOrientationWhenRotating() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());
        // TODO(b/110533226): Fix test on devices with display cutout
        assumeFalse("Skipping test: display cutout present, can't predict exact lifecycle",
                hasDisplayCutout());

        // Start portrait-fixed activity
        separateTestJournal();
        launchActivity(RESIZEABLE_ACTIVITY);
        launchActivity(PORTRAIT_ORIENTATION_ACTIVITY);
        mAmWmState.assertVisibility(PORTRAIT_ORIENTATION_ACTIVITY, true /* visible */);

        final int displayId = mAmWmState.getAmState()
                .getDisplayByActivity(PORTRAIT_ORIENTATION_ACTIVITY);

        // Rotate the activity and check that the orientation doesn't change
        try (final RotationSession rotationSession = new RotationSession()) {
            assumeTrue("Skipping test: no user locked rotation support.",
                    supportsLockedUserRotation(rotationSession, displayId));

            rotationSession.set(ROTATION_0);

            final int[] rotations = { ROTATION_270, ROTATION_180, ROTATION_90, ROTATION_0 };
            for (final int rotation : rotations) {
                separateTestJournal();
                rotationSession.set(rotation);

                // Verify lifecycle count and orientation changes.
                assertRelaunchOrConfigChanged(PORTRAIT_ORIENTATION_ACTIVITY, 0 /* numRelaunch */,
                        0 /* numConfigChange */);
                final SizeInfo reportedSizes = getLastReportedSizesForActivity(
                        PORTRAIT_ORIENTATION_ACTIVITY);
                assertNull("No new sizes must be reported", reportedSizes);
                assertRelaunchOrConfigChanged(RESIZEABLE_ACTIVITY, 0 /* numRelaunch */,
                        0 /* numConfigChange */);
            }
        }
    }

    /**
     * Test that device handles moving between two tasks with different orientations.
     */
    @Test
    @FlakyTest(bugId = 71792393)
    public void testTaskMoveToBackOrientation() {
        assumeTrue("Skipping test: no rotation support", supportsRotation());

        // Start landscape activity.
        launchActivity(LANDSCAPE_ORIENTATION_ACTIVITY);
        mAmWmState.assertVisibility(LANDSCAPE_ORIENTATION_ACTIVITY, true /* visible */);
        assertEquals("Fullscreen app requested landscape orientation",
                0 /* landscape */, mAmWmState.getWmState().getLastOrientation());

        // Start another activity in a different task.
        launchActivityInNewTask(BROADCAST_RECEIVER_ACTIVITY);

        // Request portrait
        mBroadcastActionTrigger.requestOrientation(SCREEN_ORIENTATION_PORTRAIT);
        mAmWmState.waitForLastOrientation(SCREEN_ORIENTATION_PORTRAIT);
        waitForBroadcastActivityReady(Configuration.ORIENTATION_PORTRAIT);

        // Finish activity
        mBroadcastActionTrigger.moveTopTaskToBack();

        // Verify that activity brought to front is in originally requested orientation.
        mAmWmState.waitForValidState(LANDSCAPE_ORIENTATION_ACTIVITY);
        assertEquals("Should return to app in landscape orientation",
                0 /* landscape */, mAmWmState.getWmState().getLastOrientation());
    }

    /**
     * Test that device doesn't change device orientation by app request while in multi-window.
     */
    @FlakyTest(bugId = 71918731)
    @Test
    public void testSplitscreenPortraitAppOrientationRequests() throws Exception {
        assumeTrue("Skipping test: no rotation support", supportsRotation());
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        try (final RotationSession rotationSession = new RotationSession()) {
            requestOrientationInSplitScreen(rotationSession,
                    ROTATION_90 /* portrait */, LANDSCAPE_ORIENTATION_ACTIVITY);
        }
    }

    /**
     * Test that device doesn't change device orientation by app request while in multi-window.
     */
    @Test
    public void testSplitscreenLandscapeAppOrientationRequests() throws Exception {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        try (final RotationSession rotationSession = new RotationSession()) {
            requestOrientationInSplitScreen(rotationSession,
                    ROTATION_0 /* landscape */, PORTRAIT_ORIENTATION_ACTIVITY);
        }
    }

    /**
     * Rotate the device and launch specified activity in split-screen, checking if orientation
     * didn't change.
     */
    private void requestOrientationInSplitScreen(RotationSession rotationSession, int orientation,
            ComponentName activity) throws Exception {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        // Set initial orientation.
        rotationSession.set(orientation);

        // Launch activities that request orientations and check that device doesn't rotate.
        launchActivitiesInSplitScreen(
                getLaunchActivityBuilder().setTargetActivity(LAUNCHING_ACTIVITY),
                getLaunchActivityBuilder().setTargetActivity(activity).setMultipleTask(true));

        mAmWmState.assertVisibility(activity, true /* visible */);
        assertEquals("Split-screen apps shouldn't influence device orientation",
                orientation, mAmWmState.getWmState().getRotation());

        getLaunchActivityBuilder().setMultipleTask(true).setTargetActivity(activity).execute();
        mAmWmState.computeState(activity);
        mAmWmState.assertVisibility(activity, true /* visible */);
        assertEquals("Split-screen apps shouldn't influence device orientation",
                orientation, mAmWmState.getWmState().getRotation());
    }

    /**
     * Launches activity in docked stack, moves to fullscreen stack and back to docked stack.
     * Asserts that initial and final reported sizes in docked stack are the same.
     */
    private void moveActivitySplitFullSplit(ComponentName activityName) {
        assumeTrue("Skipping test: no multi-window support", supportsSplitScreenMultiWindow());

        // Launch to docked stack and record size.
        separateTestJournal();
        launchActivityInSplitScreenWithRecents(activityName);
        final SizeInfo initialDockedSizes = getActivityDisplaySize(activityName);
        // Make sure docked stack is focused. This way when we dismiss it later fullscreen stack
        // will come up.
        launchActivity(activityName, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        mAmWmState.computeState(false /* compareTaskAndStackBounds */,
                new WaitForValidActivityState.Builder(activityName).build());

        // Move to fullscreen stack.
        separateTestJournal();
        setActivityTaskWindowingMode(
                activityName, WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        final SizeInfo fullscreenSizes = getActivityDisplaySize(activityName);
        assertSizesAreSane(fullscreenSizes, initialDockedSizes);

        // Move activity back to docked stack.
        separateTestJournal();
        setActivityTaskWindowingMode(activityName, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final SizeInfo finalDockedSizes = getActivityDisplaySize(activityName);

        // After activity configuration was changed twice it must report same size as original one.
        assertSizesAreSame(initialDockedSizes, finalDockedSizes);
    }

    /**
     * Asserts that after rotation, the aspect ratios of display size, metrics, and configuration
     * have flipped.
     */
    private static void assertSizesRotate(SizeInfo rotationA, SizeInfo rotationB,
            boolean skipOrientationCheck) {
        assertEquals(rotationA.displayWidth, rotationA.metricsWidth);
        assertEquals(rotationA.displayHeight, rotationA.metricsHeight);
        assertEquals(rotationB.displayWidth, rotationB.metricsWidth);
        assertEquals(rotationB.displayHeight, rotationB.metricsHeight);

        if (skipOrientationCheck) {
            // All done if we are not doing orientation check.
            return;
        }
        final boolean beforePortrait = rotationA.displayWidth < rotationA.displayHeight;
        final boolean afterPortrait = rotationB.displayWidth < rotationB.displayHeight;
        assertFalse(beforePortrait == afterPortrait);

        final boolean beforeConfigPortrait = rotationA.widthDp < rotationA.heightDp;
        final boolean afterConfigPortrait = rotationB.widthDp < rotationB.heightDp;
        assertEquals(beforePortrait, beforeConfigPortrait);
        assertEquals(afterPortrait, afterConfigPortrait);

        assertEquals(rotationA.smallestWidthDp, rotationB.smallestWidthDp);
    }

    /**
     * Throws an AssertionError if fullscreenSizes has widths/heights (depending on aspect ratio)
     * that are smaller than the dockedSizes.
     */
    private static void assertSizesAreSane(SizeInfo fullscreenSizes, SizeInfo dockedSizes) {
        final boolean portrait = fullscreenSizes.displayWidth < fullscreenSizes.displayHeight;
        if (portrait) {
            assertThat(dockedSizes.displayHeight, lessThan(fullscreenSizes.displayHeight));
            assertThat(dockedSizes.heightDp, lessThan(fullscreenSizes.heightDp));
            assertThat(dockedSizes.metricsHeight, lessThan(fullscreenSizes.metricsHeight));
        } else {
            assertThat(dockedSizes.displayWidth, lessThan(fullscreenSizes.displayWidth));
            assertThat(dockedSizes.widthDp, lessThan(fullscreenSizes.widthDp));
            assertThat(dockedSizes.metricsWidth, lessThan(fullscreenSizes.metricsWidth));
        }
    }

    /**
     * Throws an AssertionError if sizes are different.
     */
    private static void assertSizesAreSame(SizeInfo firstSize, SizeInfo secondSize) {
        assertEquals(firstSize.widthDp, secondSize.widthDp);
        assertEquals(firstSize.heightDp, secondSize.heightDp);
        assertEquals(firstSize.displayWidth, secondSize.displayWidth);
        assertEquals(firstSize.displayHeight, secondSize.displayHeight);
        assertEquals(firstSize.metricsWidth, secondSize.metricsWidth);
        assertEquals(firstSize.metricsHeight, secondSize.metricsHeight);
        assertEquals(firstSize.smallestWidthDp, secondSize.smallestWidthDp);
    }

    private SizeInfo getActivityDisplaySize(ComponentName activityName) {
        mAmWmState.computeState(false /* compareTaskAndStackBounds */,
                new WaitForValidActivityState(activityName));
        final SizeInfo details = getLastReportedSizesForActivity(activityName);
        assertNotNull(details);
        return details;
    }

    private Rect getDisplayRect(ComponentName activityName) {
        final String windowName = getWindowName(activityName);

        mAmWmState.computeState(activityName);
        mAmWmState.assertFocusedWindow("Test window must be the front window.", windowName);

        final List<WindowManagerState.WindowState> windowList =
                mAmWmState.getWmState().getMatchingVisibleWindowState(windowName);

        assertEquals("Should have exactly one window state for the activity.", 1,
                windowList.size());

        WindowManagerState.WindowState windowState = windowList.get(0);
        assertNotNull("Should have a valid window", windowState);

        WindowManagerState.Display display = mAmWmState.getWmState()
                .getDisplay(windowState.getDisplayId());
        assertNotNull("Should be on a display", display);

        return display.getDisplayRect();
    }

    private void waitForBroadcastActivityReady(int orientation) {
        mAmWmState.waitForActivityOrientation(BROADCAST_RECEIVER_ACTIVITY, orientation);
        mAmWmState.waitForActivityState(BROADCAST_RECEIVER_ACTIVITY, STATE_RESUMED);
    }

    /**
     * Test launching an activity which requests specific UI mode during creation.
     */
    @Test
    public void testLaunchWithUiModeChange() {
        // Launch activity that changes UI mode and handles this configuration change.
        launchActivity(NIGHT_MODE_ACTIVITY);
        mAmWmState.waitForActivityState(NIGHT_MODE_ACTIVITY, STATE_RESUMED);

        // Check if activity is launched successfully.
        mAmWmState.assertVisibility(NIGHT_MODE_ACTIVITY, true /* visible */);
        mAmWmState.assertFocusedActivity("Launched activity should be focused",
                NIGHT_MODE_ACTIVITY);
        mAmWmState.assertResumedActivity("Launched activity must be resumed", NIGHT_MODE_ACTIVITY);
    }
}
