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

package android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.ActivityManagerState.STATE_STOPPED;
import static android.server.wm.ComponentNameUtils.getWindowName;
import static android.server.wm.StateLogger.logAlways;
import static android.server.wm.StateLogger.logE;
import static android.server.wm.WindowManagerState.TRANSIT_TASK_CLOSE;
import static android.server.wm.WindowManagerState.TRANSIT_TASK_OPEN;
import static android.server.wm.app.Components.BOTTOM_ACTIVITY;
import static android.server.wm.app.Components.BROADCAST_RECEIVER_ACTIVITY;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.server.wm.app.Components.LAUNCH_TEST_ON_DESTROY_ACTIVITY;
import static android.server.wm.app.Components.RESIZEABLE_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_ATTR_ACTIVITY;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.app.Components.TOAST_ACTIVITY;
import static android.server.wm.app.Components.VIRTUAL_DISPLAY_ACTIVITY;
import static android.server.wm.app27.Components.SDK_27_LAUNCHING_ACTIVITY;
import static android.server.wm.app27.Components.SDK_27_SEPARATE_PROCESS_ACTIVITY;
import static android.server.wm.app27.Components.SDK_27_TEST_ACTIVITY;
import static android.server.wm.lifecycle.ActivityStarterTests.StandardActivity;
import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.ComponentName;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.server.wm.ActivityManagerState.ActivityDisplay;
import android.server.wm.ActivityManagerState.ActivityStack;
import android.server.wm.CommandSession.ActivityCallback;
import android.server.wm.CommandSession.ActivitySession;
import android.server.wm.CommandSession.SizeInfo;
import android.util.SparseArray;

import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:MultiDisplayPolicyTests
 *
 * Tests each expected policy on multi-display environment.
 */
@Presubmit
public class MultiDisplayPolicyTests extends MultiDisplayTestBase {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue(supportsMultiDisplay());
    }
    /**
     * Tests that all activities that were on the private display are destroyed on display removal.
     */
    @Test
    public void testContentDestroyOnDisplayRemoved() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new private virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            // Launch activities on new secondary display.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");

            launchActivityOnDisplay(RESIZEABLE_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");

            separateTestJournal();
            // Destroy the display and check if activities are removed from system.
        }

        mAmWmState.waitForActivityRemoved(TEST_ACTIVITY);
        mAmWmState.waitForActivityRemoved(RESIZEABLE_ACTIVITY);

        // Check AM state.
        assertFalse("Activity from removed display must be destroyed",
                mAmWmState.getAmState().containsActivity(TEST_ACTIVITY));
        assertFalse("Activity from removed display must be destroyed",
                mAmWmState.getAmState().containsActivity(RESIZEABLE_ACTIVITY));
        // Check WM state.
        assertFalse("Activity windows from removed display must be destroyed",
                mAmWmState.getWmState().containsWindow(getWindowName(TEST_ACTIVITY)));
        assertFalse("Activity windows from removed display must be destroyed",
                mAmWmState.getWmState().containsWindow(getWindowName(RESIZEABLE_ACTIVITY)));
        // Check activity logs.
        assertActivityDestroyed(TEST_ACTIVITY);
        assertActivityDestroyed(RESIZEABLE_ACTIVITY);
    }

    /**
     * Tests that newly launched activity will be landing on default display on display removal.
     */
    @Test
    public void testActivityLaunchOnContentDestroyDisplayRemoved() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new private virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            // Launch activities on new secondary display.
            launchActivityOnDisplay(LAUNCH_TEST_ON_DESTROY_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(LAUNCH_TEST_ON_DESTROY_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");

            // Destroy the display
        }

        waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                "Newly launches activity should be landing on default display");
    }

    /**
     * Tests that the update of display metrics updates all its content.
     */
    @Test
    public void testDisplayResize() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            // Launch a resizeable activity on new secondary display.
            separateTestJournal();
            launchActivityOnDisplay(RESIZEABLE_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");

            // Grab reported sizes and compute new with slight size change.
            final SizeInfo initialSize = getLastReportedSizesForActivity(RESIZEABLE_ACTIVITY);

            // Resize the display
            separateTestJournal();
            virtualDisplaySession.resizeDisplay();

            mAmWmState.waitForWithAmState(amState -> {
                try {
                    return readConfigChangeNumber(RESIZEABLE_ACTIVITY) == 1
                            && amState.hasActivityState(RESIZEABLE_ACTIVITY, STATE_RESUMED);
                } catch (Exception e) {
                    logE("Error waiting for valid state: " + e.getMessage());
                    return false;
                }
            }, "Wait for the configuration change to happen and for activity to be resumed.");

            mAmWmState.computeState(false /* compareTaskAndStackBounds */,
                    new WaitForValidActivityState(RESIZEABLE_ACTIVITY),
                    new WaitForValidActivityState(VIRTUAL_DISPLAY_ACTIVITY));
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true);
            mAmWmState.assertVisibility(RESIZEABLE_ACTIVITY, true);

            // Check if activity in virtual display was resized properly.
            assertRelaunchOrConfigChanged(RESIZEABLE_ACTIVITY, 0 /* numRelaunch */,
                    1 /* numConfigChange */);

            final SizeInfo updatedSize = getLastReportedSizesForActivity(RESIZEABLE_ACTIVITY);
            assertTrue(updatedSize.widthDp <= initialSize.widthDp);
            assertTrue(updatedSize.heightDp <= initialSize.heightDp);
            assertTrue(updatedSize.displayWidth == initialSize.displayWidth / 2);
            assertTrue(updatedSize.displayHeight == initialSize.displayHeight / 2);
        }
    }

    /** Read the number of configuration changes sent to activity from logs. */
    private int readConfigChangeNumber(ComponentName activityName) throws Exception {
        return (new ActivityLifecycleCounts(activityName))
                .getCount(ActivityCallback.ON_CONFIGURATION_CHANGED);
    }

    /**
     * Tests that when primary display is rotated secondary displays are not affected.
     */
    @Test
    public void testRotationNotAffectingSecondaryScreen() throws Exception {
        try (final VirtualDisplayLauncher virtualLauncher = new VirtualDisplayLauncher()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualLauncher.setResizeDisplay(false)
                    .createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            // Launch activity on new secondary display.
            final ActivitySession resizeableActivitySession =
                    virtualLauncher.launchActivityOnDisplay(RESIZEABLE_ACTIVITY, newDisplay);
            waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "Top activity must be on secondary display");
            final SizeInfo initialSize = resizeableActivitySession.getConfigInfo().sizeInfo;

            assertNotNull("Test activity must have reported initial size on launch", initialSize);

            try (final RotationSession rotationSession = new RotationSession()) {
                // Rotate primary display and check that activity on secondary display is not
                // affected.
                rotateAndCheckSameSizes(rotationSession, resizeableActivitySession, initialSize);

                // Launch activity to secondary display when primary one is rotated.
                final int initialRotation = mAmWmState.getWmState().getRotation();
                rotationSession.set((initialRotation + 1) % 4);

                final ActivitySession testActivitySession =
                        virtualLauncher.launchActivityOnDisplay(TEST_ACTIVITY, newDisplay);
                waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                        "Top activity must be on secondary display");
                final SizeInfo testActivitySize = testActivitySession.getConfigInfo().sizeInfo;

                assertEquals("Sizes of secondary display must not change after rotation of primary"
                        + " display", initialSize, testActivitySize);
            }
        }
    }

    private void rotateAndCheckSameSizes(RotationSession rotationSession,
            ActivitySession activitySession, SizeInfo initialSize) throws Exception {
        for (int rotation = 3; rotation >= 0; --rotation) {
            rotationSession.set(rotation);
            final SizeInfo rotatedSize = activitySession.getConfigInfo().sizeInfo;

            assertEquals("Sizes must not change after rotation", initialSize, rotatedSize);
        }
    }

    /**
     * Tests that turning the primary display off does not affect the activity running
     * on an external secondary display.
     */
    @Test
    public void testExternalDisplayActivityTurnPrimaryOff() throws Exception {
        // Launch something on the primary display so we know there is a resumed activity there
        launchActivity(RESIZEABLE_ACTIVITY);
        waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, DEFAULT_DISPLAY,
                "Activity launched on primary display must be resumed");

        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession();
             final PrimaryDisplayStateSession displayStateSession =
                     new PrimaryDisplayStateSession()) {
            final ActivityDisplay newDisplay = externalDisplaySession
                    .setCanShowWithInsecureKeyguard(true).createVirtualDisplay();

            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            // Check that the activity is launched onto the external display
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on external display must be resumed");
            mAmWmState.assertFocusedAppOnDisplay("App on default display must still be focused",
                    RESIZEABLE_ACTIVITY, DEFAULT_DISPLAY);

            separateTestJournal();
            displayStateSession.turnScreenOff();

            // Wait for the fullscreen stack to start sleeping, and then make sure the
            // test activity is still resumed.
            int retry = 0;
            int stopCount = 0;
            do {
                stopCount = (new ActivityLifecycleCounts(RESIZEABLE_ACTIVITY))
                        .getCount(ActivityCallback.ON_STOP);
                if (stopCount == 1) {
                    break;
                }
                logAlways("***testExternalDisplayActivityTurnPrimaryOff... retry=" + retry);
                SystemClock.sleep(TimeUnit.SECONDS.toMillis(1));
            } while (retry++ < 5);

            if (stopCount != 1) {
                fail(RESIZEABLE_ACTIVITY + " has received " + stopCount
                        + " onStop() calls, expecting 1");
            }
            // For this test we create this virtual display with flag showContentWhenLocked, so it
            // cannot be effected when default display screen off.
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on external display must be resumed");
        }
    }

    /**
     * Tests that turning the secondary display off stops activities running and makes invisible
     * on that display.
     */
    @Test
    public void testExternalDisplayToggleState() throws Exception {
        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            final ActivityDisplay newDisplay = externalDisplaySession.createVirtualDisplay();

            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            // Check that the test activity is resumed on the external display
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on external display must be resumed");

            externalDisplaySession.turnDisplayOff();

            // Check that turning off the external display stops the activity, and makes it
            // invisible.
            waitAndAssertActivityState(TEST_ACTIVITY, STATE_STOPPED,
                    "Activity launched on external display must be stopped after turning off");
            mAmWmState.assertVisibility(TEST_ACTIVITY, false /* visible */);

            externalDisplaySession.turnDisplayOn();

            // Check that turning on the external display resumes the activity
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on external display must be resumed");
        }
    }

    /**
     * Tests no leaking after external display removed.
     */
    @Test
    public void testNoLeakOnExternalDisplay() throws Exception {
        // How this test works:
        // When receiving the request to remove a display and some activities still exist on that
        // display, it will finish those activities first, so the display won't be removed
        // immediately. Then, when all activities were destroyed, the display removes itself.

        // Get display count before testing, as some devices may have more than one built-in
        // display.
        mAmWmState.getAmState().computeState();
        final int displayCount = mAmWmState.getAmState().getDisplayCount();
        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            final ActivityDisplay newDisplay = externalDisplaySession.createVirtualDisplay();
            launchActivityOnDisplay(VIRTUAL_DISPLAY_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(VIRTUAL_DISPLAY_ACTIVITY, newDisplay.mId,
                    "Virtual activity should be Top Resumed Activity.");
            mAmWmState.assertFocusedAppOnDisplay("Activity on second display must be focused.",
                    VIRTUAL_DISPLAY_ACTIVITY, newDisplay.mId);
        }
        mAmWmState.waitFor((amState, wmState) -> amState.getDisplayCount() == displayCount,
                "Waiting for external displays to be removed");
        assertEquals(displayCount, mAmWmState.getAmState().getDisplayCount());
        assertEquals(displayCount, mAmWmState.getAmState().getKeyguardControllerState().
                mKeyguardOccludedStates.size());
    }

    /**
     * Tests launching activities on secondary and then on primary display to see if the stack
     * visibility is not affected.
     */
    @Test
    public void testLaunchActivitiesAffectsVisibility() throws Exception {
        // Start launching activity.
        launchActivity(LAUNCHING_ACTIVITY);

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            // Launch activity on new secondary display.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);
            mAmWmState.assertVisibility(TEST_ACTIVITY, true /* visible */);
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            // Launch activity on primary display and check if it doesn't affect activity on
            // secondary display.
            getLaunchActivityBuilder().setTargetActivity(RESIZEABLE_ACTIVITY).execute();
            mAmWmState.waitForValidState(RESIZEABLE_ACTIVITY);
            mAmWmState.assertVisibility(TEST_ACTIVITY, true /* visible */);
            mAmWmState.assertVisibility(RESIZEABLE_ACTIVITY, true /* visible */);
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, RESIZEABLE_ACTIVITY);
                        put(newDisplay.mId, TEST_ACTIVITY);
                    }}
            );
        }
    }

    /**
     * Test that move-task works when moving between displays.
     */
    @Test
    public void testMoveTaskBetweenDisplays() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);
            mAmWmState.assertFocusedActivity("Virtual display activity must be on top",
                    VIRTUAL_DISPLAY_ACTIVITY);
            final int defaultDisplayStackId = mAmWmState.getAmState().getFocusedStackId();
            ActivityStack frontStack = mAmWmState.getAmState().getStackById(
                    defaultDisplayStackId);
            assertEquals("Top stack must remain on primary display",
                    DEFAULT_DISPLAY, frontStack.mDisplayId);

            // Launch activity on new secondary display.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Top activity must be on secondary display");
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, VIRTUAL_DISPLAY_ACTIVITY);
                        put(newDisplay.mId, TEST_ACTIVITY);
                    }}
            );

            // Move activity from secondary display to primary.
            moveActivityToStack(TEST_ACTIVITY, defaultDisplayStackId);
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                    "Moved activity must be on top");
        }
    }

    /**
     * Tests launching activities on secondary display and then removing it to see if stack focus
     * is moved correctly.
     * This version launches virtual display creator to fullscreen stack in split-screen.
     */
    @Test
    public void testStackFocusSwitchOnDisplayRemoved() throws Exception {
        assumeTrue(supportsSplitScreenMultiWindow());

        // Start launching activity into docked stack.
        launchActivitiesInSplitScreen(
                getLaunchActivityBuilder().setTargetActivity(LAUNCHING_ACTIVITY),
                getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY));
        mAmWmState.assertVisibility(LAUNCHING_ACTIVITY, true /* visible */);

        tryCreatingAndRemovingDisplayWithActivity(true /* splitScreen */,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
    }

    /**
     * Tests launching activities on secondary display and then removing it to see if stack focus
     * is moved correctly.
     * This version launches virtual display creator to docked stack in split-screen.
     */
    @Test
    public void testStackFocusSwitchOnDisplayRemoved2() throws Exception {
        assumeTrue(supportsSplitScreenMultiWindow());

        // Setup split-screen.
        launchActivitiesInSplitScreen(
                getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY),
                getLaunchActivityBuilder().setTargetActivity(LAUNCHING_ACTIVITY));
        mAmWmState.assertVisibility(LAUNCHING_ACTIVITY, true /* visible */);

        tryCreatingAndRemovingDisplayWithActivity(true /* splitScreen */,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
    }

    /**
     * Tests launching activities on secondary display and then removing it to see if stack focus
     * is moved correctly.
     * This version works without split-screen.
     */
    @Test
    public void testStackFocusSwitchOnDisplayRemoved3() throws Exception {
        // Start an activity on default display to determine default stack.
        launchActivity(BROADCAST_RECEIVER_ACTIVITY);
        final int focusedStackWindowingMode = mAmWmState.getAmState().getFrontStackWindowingMode(
                DEFAULT_DISPLAY);
        // Finish probing activity.
        mBroadcastActionTrigger.finishBroadcastReceiverActivity();

        tryCreatingAndRemovingDisplayWithActivity(false /* splitScreen */,
                focusedStackWindowingMode);
    }

    /**
     * Create a virtual display, launch a test activity there, destroy the display and check if test
     * activity is moved to a stack on the default display.
     */
    private void tryCreatingAndRemovingDisplayWithActivity(boolean splitScreen, int windowingMode)
            throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession
                    .setPublicDisplay(true)
                    .setLaunchInSplitScreen(splitScreen)
                    .createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);
            if (splitScreen) {
                mAmWmState.assertVisibility(LAUNCHING_ACTIVITY, true /* visible */);
            }

            // Launch activity on new secondary display.
            launchActivityOnDisplay(RESIZEABLE_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "Top activity must be on secondary display");
            final int frontStackId = mAmWmState.getAmState().getFrontStackId(newDisplay.mId);
            mAmWmState.assertFocusedStack("Top stack must be on secondary display", frontStackId);

            separateTestJournal();
            // Destroy virtual display.
        }

        mAmWmState.computeState(true);
        assertActivityLifecycle(RESIZEABLE_ACTIVITY, false /* relaunched */);
        mAmWmState.waitForValidState(new WaitForValidActivityState.Builder(RESIZEABLE_ACTIVITY)
                .setWindowingMode(windowingMode)
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .build());
        mAmWmState.assertSanity();
        mAmWmState.assertValidBounds(true /* compareTaskAndStackBounds */);

        // Check if the top activity is now back on primary display.
        mAmWmState.assertVisibility(RESIZEABLE_ACTIVITY, true /* visible */);
        mAmWmState.assertFocusedStack(
                "Default stack on primary display must be focused after display removed",
                windowingMode, ACTIVITY_TYPE_STANDARD);
        mAmWmState.assertFocusedActivity(
                "Focus must be switched back to activity on primary display",
                RESIZEABLE_ACTIVITY);
    }

    /**
     * Tests launching activities on secondary display and then removing it to see if stack focus
     * is moved correctly.
     */
    @Test
    public void testStackFocusSwitchOnStackEmptiedInSleeping() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession();
             final LockScreenSession lockScreenSession = new LockScreenSession()) {
            validateStackFocusSwitchOnStackEmptied(virtualDisplaySession, lockScreenSession);
        }
    }

    /**
     * Tests launching activities on secondary display and then finishing it to see if stack focus
     * is moved correctly.
     */
    @Test
    public void testStackFocusSwitchOnStackEmptied() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            validateStackFocusSwitchOnStackEmptied(virtualDisplaySession,
                    null /* lockScreenSession */);
        }
    }

    private void validateStackFocusSwitchOnStackEmptied(VirtualDisplaySession virtualDisplaySession,
            LockScreenSession lockScreenSession) throws Exception {
        // Create new virtual display.
        final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
        mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);
        final int focusedStackId = mAmWmState.getAmState().getFrontStackId(DEFAULT_DISPLAY);

        // Launch activity on new secondary display.
        launchActivityOnDisplay(BROADCAST_RECEIVER_ACTIVITY, newDisplay.mId);
        waitAndAssertTopResumedActivity(BROADCAST_RECEIVER_ACTIVITY, newDisplay.mId,
                "Top activity must be on secondary display");

        if (lockScreenSession != null) {
            // Lock the device, so that activity containers will be detached.
            lockScreenSession.sleepDevice();
        }

        // Finish activity on secondary display.
        mBroadcastActionTrigger.finishBroadcastReceiverActivity();

        if (lockScreenSession != null) {
            // Unlock and check if the focus is switched back to primary display.
            lockScreenSession.wakeUpDevice().unlockDevice();
        }

        waitAndAssertTopResumedActivity(VIRTUAL_DISPLAY_ACTIVITY, DEFAULT_DISPLAY,
                "Top activity must be switched back to primary display");
    }

    /**
     * Tests that input events on the primary display take focus from the virtual display.
     */
    @Test
    public void testStackFocusSwitchOnTouchEvent() throws Exception {
        // If config_perDisplayFocusEnabled, the focus will not move even if touching on
        // the Activity in the different display.
        assumeFalse(perDisplayFocusEnabled());

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            mAmWmState.computeState(VIRTUAL_DISPLAY_ACTIVITY);
            mAmWmState.assertFocusedActivity("Top activity must be the latest launched one",
                    VIRTUAL_DISPLAY_ACTIVITY);

            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be on top");

            tapOnDisplayCenter(DEFAULT_DISPLAY);

            waitAndAssertTopResumedActivity(VIRTUAL_DISPLAY_ACTIVITY, DEFAULT_DISPLAY,
                    "Top activity must be on the primary display");
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, VIRTUAL_DISPLAY_ACTIVITY);
                        put(newDisplay.mId, TEST_ACTIVITY);
                    }}
            );
            mAmWmState.assertFocusedAppOnDisplay("App on secondary display must still be focused",
                    TEST_ACTIVITY, newDisplay.mId);
        }
    }


    /**
     * Tests that tapping on the primary display after showing the keyguard resumes the
     * activity on the primary display.
     */
    @Test
    public void testStackFocusSwitchOnTouchEventAfterKeyguard() throws Exception {
        assumeFalse(perDisplayFocusEnabled());

        // Launch something on the primary display so we know there is a resumed activity there
        launchActivity(RESIZEABLE_ACTIVITY);
        waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, DEFAULT_DISPLAY,
                "Activity launched on primary display must be resumed");

        try (final LockScreenSession lockScreenSession = new LockScreenSession();
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            lockScreenSession.sleepDevice();

            // Make sure there is no resumed activity when the primary display is off
            waitAndAssertActivityState(RESIZEABLE_ACTIVITY, STATE_STOPPED,
                    "Activity launched on primary display must be stopped after turning off");
            assertEquals("Unexpected resumed activity",
                    0, mAmWmState.getAmState().getResumedActivitiesCount());

            final ActivityDisplay newDisplay = externalDisplaySession
                    .setCanShowWithInsecureKeyguard(true).createVirtualDisplay();

            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            // Unlock the device and tap on the middle of the primary display
            lockScreenSession.wakeUpDevice();
            executeShellCommand("wm dismiss-keyguard");
            mAmWmState.waitForKeyguardGone();
            mAmWmState.waitForValidState(RESIZEABLE_ACTIVITY, TEST_ACTIVITY);

            // Check that the test activity is resumed on the external display and is on top
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity on external display must be resumed and on top");
            mAmWmState.assertResumedActivities("Both displays should have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, RESIZEABLE_ACTIVITY);
                        put(newDisplay.mId, TEST_ACTIVITY);
                    }}
            );

            tapOnDisplayCenter(DEFAULT_DISPLAY);

            // Check that the activity on the primary display is the topmost resumed
            waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity on primary display must be resumed and on top");
            mAmWmState.assertResumedActivities("Both displays should have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, RESIZEABLE_ACTIVITY);
                        put(newDisplay.mId, TEST_ACTIVITY);
                    }}
            );
            mAmWmState.assertFocusedAppOnDisplay("App on external display must still be focused",
                    TEST_ACTIVITY, newDisplay.mId);
        }
    }

    /**
     * Tests that showWhenLocked works on a secondary display.
     */
    @Test
    public void testSecondaryDisplayShowWhenLocked() throws Exception {
        assumeTrue(supportsSecureLock());

        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession();
             final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential();

            launchActivity(TEST_ACTIVITY);

            final ActivityDisplay newDisplay = externalDisplaySession.createVirtualDisplay();
            launchActivityOnDisplay(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, newDisplay.mId);

            lockScreenSession.gotoKeyguard();

            waitAndAssertActivityState(TEST_ACTIVITY, STATE_STOPPED,
                    "Expected stopped activity on default display");
            waitAndAssertTopResumedActivity(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, newDisplay.mId,
                    "Expected resumed activity on secondary display");
        }
    }

    /**
     * Tests tap and set focus between displays.
     */
    @Test
    public void testSecondaryDisplayFocus() throws Exception {
        assumeFalse(perDisplayFocusEnabled());

        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            launchActivity(TEST_ACTIVITY);
            mAmWmState.waitForActivityState(TEST_ACTIVITY, STATE_RESUMED);

            final ActivityDisplay newDisplay = externalDisplaySession.createVirtualDisplay();
            launchActivityOnDisplay(VIRTUAL_DISPLAY_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(VIRTUAL_DISPLAY_ACTIVITY, newDisplay.mId,
                    "Virtual activity should be Top Resumed Activity.");
            mAmWmState.assertFocusedAppOnDisplay("Activity on second display must be focused.",
                    VIRTUAL_DISPLAY_ACTIVITY, newDisplay.mId);

            tapOnDisplayCenter(DEFAULT_DISPLAY);

            waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity should be top resumed when tapped.");
            mAmWmState.assertFocusedActivity("Activity on default display must be top focused.",
                    TEST_ACTIVITY);

            tapOnDisplayCenter(newDisplay.mId);

            waitAndAssertTopResumedActivity(VIRTUAL_DISPLAY_ACTIVITY, newDisplay.mId,
                    "Virtual display activity should be top resumed when tapped.");
            mAmWmState.assertFocusedActivity("Activity on second display must be top focused.",
                    VIRTUAL_DISPLAY_ACTIVITY);
            mAmWmState.assertFocusedAppOnDisplay(
                    "Activity on default display must be still focused.",
                    TEST_ACTIVITY, DEFAULT_DISPLAY);
        }
    }

    /**
     * Tests that toast works on a secondary display.
     */
    @Test
    @FlakyTest(bugId = 131005232)
    public void testSecondaryDisplayShowToast() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay =
                    virtualDisplaySession.setPublicDisplay(true).createDisplay();
            final String TOAST_NAME = "Toast";
            launchActivityOnDisplay(TOAST_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(TOAST_ACTIVITY, newDisplay.mId,
                    "Activity launched on external display must be resumed");
            mAmWmState.waitForWithWmState((state) -> state.containsWindow(TOAST_NAME),
                    "Waiting for toast window to show");

            assertTrue("Toast window must be shown",
                    mAmWmState.getWmState().containsWindow(TOAST_NAME));
            assertTrue("Toast window must be visible",
                    mAmWmState.getWmState().isWindowVisible(TOAST_NAME));
        }
    }

    /**
     * Tests that the surface size of a fullscreen task is same as its display's surface size.
     * Also check that the surface size has updated after reparenting to other display.
     */
    @Test
    public void testTaskSurfaceSizeAfterReparentDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display and launch an activity on it.
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Top activity must be the newly launched one");
            assertTopTaskSameSurfaceSizeWithDisplay(newDisplay.mId);

            separateTestJournal();
            // Destroy the display.
        }

        // Activity must be reparented to default display and relaunched.
        assertActivityLifecycle(TEST_ACTIVITY, true /* relaunched */);
        waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                "Top activity must be reparented to default display");

        // Check the surface size after task was reparented to default display.
        assertTopTaskSameSurfaceSizeWithDisplay(DEFAULT_DISPLAY);
    }

    private void assertTopTaskSameSurfaceSizeWithDisplay(int displayId) {
        final WindowManagerState.Display display = mAmWmState.getWmState().getDisplay(displayId);
        final int stackId = mAmWmState.getWmState().getFrontStackId(displayId);
        final WindowManagerState.WindowTask task =
                mAmWmState.getWmState().getStack(stackId).mTasks.get(0);

        assertEquals("Task must have same surface width with its display",
                display.getSurfaceSize(), task.getSurfaceWidth());
        assertEquals("Task must have same surface height with its display",
                display.getSurfaceSize(), task.getSurfaceHeight());
    }

    @Test
    public void testAppTransitionForActivityOnDifferentDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession();
             final TestActivitySession<StandardActivity> transitionActivitySession = new
                     TestActivitySession<>()) {
            // Create new simulated display.
            final ActivityDisplay newDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).createDisplay();

            // Launch BottomActivity on top of launcher activity to prevent transition state
            // affected by wallpaper theme.
            launchActivityOnDisplay(BOTTOM_ACTIVITY, DEFAULT_DISPLAY);
            waitAndAssertTopResumedActivity(BOTTOM_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity must be resumed");

            // Launch StandardActivity on default display, verify last transition if is correct.
            transitionActivitySession.launchTestActivityOnDisplaySync(StandardActivity.class,
                    DEFAULT_DISPLAY);
            mAmWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);
            mAmWmState.assertSanity();
            assertEquals(TRANSIT_TASK_OPEN,
                    mAmWmState.getWmState().getDisplay(DEFAULT_DISPLAY).getLastTransition());

            // Finish current activity & launch another TestActivity in virtual display in parallel.
            transitionActivitySession.finishCurrentActivityNoWait();
            launchActivityOnDisplayNoWait(TEST_ACTIVITY, newDisplay.mId);
            mAmWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);
            mAmWmState.waitForAppTransitionIdleOnDisplay(newDisplay.mId);
            mAmWmState.assertSanity();

            // Verify each display's last transition if is correct as expected.
            assertEquals(TRANSIT_TASK_CLOSE,
                    mAmWmState.getWmState().getDisplay(DEFAULT_DISPLAY).getLastTransition());
            assertEquals(TRANSIT_TASK_OPEN,
                    mAmWmState.getWmState().getDisplay(newDisplay.mId).getLastTransition());
        }
    }

    @Test
    public void testNoTransitionWhenMovingActivityToDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display & capture new display's transition state.
            final ActivityDisplay newDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).createDisplay();

            // Launch TestActivity in virtual display & capture its transition state.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);
            mAmWmState.waitForAppTransitionIdleOnDisplay(newDisplay.mId);
            mAmWmState.assertSanity();
            final String lastTranstionOnVirtualDisplay = mAmWmState.getWmState()
                    .getDisplay(newDisplay.mId).getLastTransition();

            // Move TestActivity from virtual display to default display.
            getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY)
                    .allowMultipleInstances(false).setNewTask(true)
                    .setDisplayId(DEFAULT_DISPLAY).execute();

            // Verify TestActivity moved to virtual display.
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                    "Existing task must be brought to front");

            // Make sure last transition will not change when task move to another display.
            assertEquals(lastTranstionOnVirtualDisplay,
                    mAmWmState.getWmState().getDisplay(newDisplay.mId).getLastTransition());
        }
    }

    @Test
    public void testPreQTopProcessResumedActivity() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay =
                    virtualDisplaySession.setSimulateDisplay(true).createDisplay();

            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(SDK_27_TEST_ACTIVITY).setNewTask(true)
                    .setDisplayId(newDisplay.mId).execute();
            waitAndAssertTopResumedActivity(SDK_27_TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be resumed and focused");

            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(SDK_27_LAUNCHING_ACTIVITY).setNewTask(true)
                    .setDisplayId(DEFAULT_DISPLAY).execute();
            waitAndAssertTopResumedActivity(SDK_27_LAUNCHING_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity launched on default display must be resumed and focused");

            assertEquals("There must be only one resumed activity in the package.", 1,
                    mAmWmState.getAmState().getResumedActivitiesCountInPackage(
                            SDK_27_LAUNCHING_ACTIVITY.getPackageName()));

            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(SDK_27_SEPARATE_PROCESS_ACTIVITY).setNewTask(true)
                    .setDisplayId(DEFAULT_DISPLAY).execute();
            waitAndAssertTopResumedActivity(SDK_27_SEPARATE_PROCESS_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity launched on default display must be resumed and focused");
            assertTrue("Activity that was on secondary display must be resumed",
                    mAmWmState.getAmState().hasActivityState(SDK_27_TEST_ACTIVITY, STATE_RESUMED));
            assertEquals("There must be only two resumed activities in the package.", 2,
                    mAmWmState.getAmState().getResumedActivitiesCountInPackage(
                            SDK_27_TEST_ACTIVITY.getPackageName()));
        }
    }

}
