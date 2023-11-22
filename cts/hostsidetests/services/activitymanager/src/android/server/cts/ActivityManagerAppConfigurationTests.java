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
package android.server.cts;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

public class ActivityManagerAppConfigurationTests extends ActivityManagerTestBase {
    private static final String RESIZEABLE_ACTIVITY_NAME = "ResizeableActivity";
    private static final String TEST_ACTIVITY_NAME = "TestActivity";

    /**
     * Tests that the WindowManager#getDefaultDisplay() and the Configuration of the Activity
     * has an updated size when the Activity is resized from fullscreen to docked state.
     *
     * The Activity handles configuration changes, so it will not be restarted between resizes.
     * On Configuration changes, the Activity logs the Display size and Configuration width
     * and heights. The values reported in fullscreen should be larger than those reported in
     * docked state.
     */
    public void testConfigurationUpdatesWhenResizedFromFullscreen() throws Exception {
        launchActivityInStack(RESIZEABLE_ACTIVITY_NAME, FULLSCREEN_WORKSPACE_STACK_ID);
        final ReportedSizes fullscreenSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                FULLSCREEN_WORKSPACE_STACK_ID);

        moveActivityToStack(RESIZEABLE_ACTIVITY_NAME, DOCKED_STACK_ID);
        final ReportedSizes dockedSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                DOCKED_STACK_ID);

        assertSizesAreSane(fullscreenSizes, dockedSizes);
    }

    /**
     * Same as {@link #testConfigurationUpdatesWhenResizedFromFullscreen()} but resizing
     * from docked state to fullscreen (reverse).
     */
    public void testConfigurationUpdatesWhenResizedFromDockedStack() throws Exception {
        launchActivityInStack(RESIZEABLE_ACTIVITY_NAME, DOCKED_STACK_ID);
        final ReportedSizes dockedSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                DOCKED_STACK_ID);

        moveActivityToStack(RESIZEABLE_ACTIVITY_NAME, FULLSCREEN_WORKSPACE_STACK_ID);
        final ReportedSizes fullscreenSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                FULLSCREEN_WORKSPACE_STACK_ID);

        assertSizesAreSane(fullscreenSizes, dockedSizes);
    }

    /**
     * Tests whether the Display sizes change when rotating the device.
     */
    public void testConfigurationUpdatesWhenRotatingWhileFullscreen() throws Exception {
        setDeviceRotation(0);
        launchActivityInStack(RESIZEABLE_ACTIVITY_NAME, FULLSCREEN_WORKSPACE_STACK_ID);
        final ReportedSizes orientationASizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                FULLSCREEN_WORKSPACE_STACK_ID);

        setDeviceRotation(1);
        final ReportedSizes orientationBSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                FULLSCREEN_WORKSPACE_STACK_ID);
        assertSizesRotate(orientationASizes, orientationBSizes);
    }

    /**
     * Same as {@link #testConfigurationUpdatesWhenRotatingWhileFullscreen()} but when the Activity
     * is in the docked stack.
     */
    public void testConfigurationUpdatesWhenRotatingWhileDocked() throws Exception {
        setDeviceRotation(0);
        launchActivityInStack(RESIZEABLE_ACTIVITY_NAME, DOCKED_STACK_ID);
        final ReportedSizes orientationASizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                DOCKED_STACK_ID);

        setDeviceRotation(1);
        final ReportedSizes orientationBSizes = getActivityDisplaySize(RESIZEABLE_ACTIVITY_NAME,
                DOCKED_STACK_ID);
        assertSizesRotate(orientationASizes, orientationBSizes);
    }

    /**
     * Tests when activity moved from fullscreen stack to docked and back. Activity will be
     * relaunched twice and it should have same config as initial one.
     */
    public void testSameConfigurationFullSplitFullRelaunch() throws Exception {
        moveActivityFullSplitFull(TEST_ACTIVITY_NAME);
    }

    /**
     * Same as {@link #testSameConfigurationFullSplitFullRelaunch} but without relaunch.
     */
    public void testSameConfigurationFullSplitFullNoRelaunch() throws Exception {
        moveActivityFullSplitFull(RESIZEABLE_ACTIVITY_NAME);
    }

    /**
     * Launches activity in fullscreen stack, moves to docked stack and back to fullscreen stack.
     * Last operation is done in a way which simulates split-screen divider movement maximizing
     * docked stack size and then moving task to fullscreen stack - the same way it is done when
     * user long-presses overview/recents button to exit split-screen.
     * Asserts that initial and final reported sizes in fullscreen stack are the same.
     */
    private void moveActivityFullSplitFull(String activityName) throws Exception {
        // Launch to fullscreen stack and record size.
        launchActivityInStack(activityName, FULLSCREEN_WORKSPACE_STACK_ID);
        final ReportedSizes initialFullscreenSizes = getActivityDisplaySize(activityName,
                FULLSCREEN_WORKSPACE_STACK_ID);
        final Rectangle displayRect = getDisplayRect(activityName);

        // Move to docked stack.
        moveActivityToStack(activityName, DOCKED_STACK_ID);
        final ReportedSizes dockedSizes = getActivityDisplaySize(activityName, DOCKED_STACK_ID);
        assertSizesAreSane(initialFullscreenSizes, dockedSizes);
        // Make sure docked stack is focused. This way when we dismiss it later fullscreen stack
        // will come up.
        launchActivityInStack(activityName, DOCKED_STACK_ID);
        mAmWmState.computeState(mDevice, new String[] { activityName },
                false /* compareTaskAndStackBounds */);

        // Resize docked stack to fullscreen size. This will trigger activity relaunch with
        // non-empty override configuration corresponding to fullscreen size.
        runCommandAndPrintOutput("am stack resize " + DOCKED_STACK_ID + " 0 0 "
                + displayRect.width + " " + displayRect.height);
        // Move activity back to fullscreen stack.
        moveActivityToStack(activityName, FULLSCREEN_WORKSPACE_STACK_ID);
        final ReportedSizes finalFullscreenSizes = getActivityDisplaySize(activityName,
                FULLSCREEN_WORKSPACE_STACK_ID);

        // After activity configuration was changed twice it must report same size as original one.
        assertSizesAreSame(initialFullscreenSizes, finalFullscreenSizes);
    }

    /**
     * Tests when activity moved from docked stack to fullscreen and back. Activity will be
     * relaunched twice and it should have same config as initial one.
     */
    public void testSameConfigurationSplitFullSplitRelaunch() throws Exception {
        moveActivitySplitFullSplit(TEST_ACTIVITY_NAME);
    }

    /**
     * Same as {@link #testSameConfigurationSplitFullSplitRelaunch} but without relaunch.
     */
    public void testSameConfigurationSplitFullSplitNoRelaunch() throws Exception {
        moveActivitySplitFullSplit(RESIZEABLE_ACTIVITY_NAME);
    }

    /**
     * Launches activity in docked stack, moves to fullscreen stack and back to docked stack.
     * Asserts that initial and final reported sizes in docked stack are the same.
     */
    private void moveActivitySplitFullSplit(String activityName) throws Exception {
        // Launch to docked stack and record size.
        launchActivityInStack(activityName, DOCKED_STACK_ID);
        final ReportedSizes initialDockedSizes = getActivityDisplaySize(activityName,
                DOCKED_STACK_ID);
        // Make sure docked stack is focused. This way when we dismiss it later fullscreen stack
        // will come up.
        launchActivityInStack(activityName, DOCKED_STACK_ID);
        mAmWmState.computeState(mDevice, new String[] { activityName },
                false /* compareTaskAndStackBounds */);

        // Move to fullscreen stack.
        moveActivityToStack(activityName, FULLSCREEN_WORKSPACE_STACK_ID);
        final ReportedSizes fullscreenSizes = getActivityDisplaySize(activityName,
                FULLSCREEN_WORKSPACE_STACK_ID);
        assertSizesAreSane(fullscreenSizes, initialDockedSizes);

        // Move activity back to docked stack.
        moveActivityToStack(activityName, DOCKED_STACK_ID);
        final ReportedSizes finalDockedSizes = getActivityDisplaySize(activityName,
                DOCKED_STACK_ID);

        // After activity configuration was changed twice it must report same size as original one.
        assertSizesAreSame(initialDockedSizes, finalDockedSizes);
    }

    /**
     * Asserts that after rotation, the aspect ratios of display size, metrics, and configuration
     * have flipped.
     */
    private static void assertSizesRotate(ReportedSizes rotationA, ReportedSizes rotationB)
            throws Exception {
        assertEquals(rotationA.displayWidth, rotationA.metricsWidth);
        assertEquals(rotationA.displayHeight, rotationA.metricsHeight);
        assertEquals(rotationB.displayWidth, rotationB.metricsWidth);
        assertEquals(rotationB.displayHeight, rotationB.metricsHeight);

        final boolean beforePortrait = rotationA.displayWidth < rotationA.displayHeight;
        final boolean afterPortrait = rotationB.displayWidth < rotationB.displayHeight;
        assertFalse(beforePortrait == afterPortrait);

        final boolean beforeConfigPortrait = rotationA.widthDp < rotationA.heightDp;
        final boolean afterConfigPortrait = rotationB.widthDp < rotationB.heightDp;
        assertEquals(beforePortrait, beforeConfigPortrait);
        assertEquals(afterPortrait, afterConfigPortrait);
    }

    /**
     * Throws an AssertionError if fullscreenSizes has widths/heights (depending on aspect ratio)
     * that are smaller than the dockedSizes.
     */
    private static void assertSizesAreSane(ReportedSizes fullscreenSizes, ReportedSizes dockedSizes)
            throws Exception {
        final boolean portrait = fullscreenSizes.displayWidth < fullscreenSizes.displayHeight;
        if (portrait) {
            assertTrue(dockedSizes.displayHeight < fullscreenSizes.displayHeight);
            assertTrue(dockedSizes.heightDp < fullscreenSizes.heightDp);
            assertTrue(dockedSizes.metricsHeight < fullscreenSizes.metricsHeight);
        } else {
            assertTrue(dockedSizes.displayWidth < fullscreenSizes.displayWidth);
            assertTrue(dockedSizes.widthDp < fullscreenSizes.widthDp);
            assertTrue(dockedSizes.metricsWidth < fullscreenSizes.metricsWidth);
        }
    }

    /**
     * Throws an AssertionError if sizes are different.
     */
    private static void assertSizesAreSame(ReportedSizes firstSize, ReportedSizes secondSize)
            throws Exception {
        assertEquals(firstSize.widthDp, secondSize.widthDp);
        assertEquals(firstSize.heightDp, secondSize.heightDp);
        assertEquals(firstSize.displayWidth, secondSize.displayWidth);
        assertEquals(firstSize.displayHeight, secondSize.displayHeight);
        assertEquals(firstSize.metricsWidth, secondSize.metricsWidth);
        assertEquals(firstSize.metricsHeight, secondSize.metricsHeight);
    }

    private ReportedSizes getActivityDisplaySize(String activityName, int stackId)
            throws Exception {
        mAmWmState.computeState(mDevice, new String[] { activityName },
                false /* compareTaskAndStackBounds */);
        mAmWmState.assertContainsStack("Must contain stack " + stackId, stackId);
        final ReportedSizes details = getLastReportedSizesForActivity(activityName);
        assertNotNull(details);
        return details;
    }

    private Rectangle getDisplayRect(String activityName)
            throws Exception {
        final String windowName = getWindowName(activityName);

        mAmWmState.computeState(mDevice, true /* visibleOnly */, new String[] {activityName});

        mAmWmState.assertFocusedWindow("Test window must be the front window.", windowName);

        final List<WindowManagerState.WindowState> tempWindowList = new ArrayList<>();
        mAmWmState.getWmState().getMatchingWindowState(windowName, tempWindowList);

        assertEquals("Should have exactly one window state for the activity.", 1,
                tempWindowList.size());

        WindowManagerState.WindowState windowState = tempWindowList.get(0);
        assertNotNull("Should have a valid window", windowState);

        WindowManagerState.Display display = mAmWmState.getWmState()
                .getDisplay(windowState.getDisplayId());
        assertNotNull("Should be on a display", display);

        return display.getDisplayRect();
    }
}
