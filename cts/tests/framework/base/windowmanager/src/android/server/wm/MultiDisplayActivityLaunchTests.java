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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.server.wm.ActivityLauncher.KEY_LAUNCH_ACTIVITY;
import static android.server.wm.ActivityLauncher.KEY_NEW_TASK;
import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.ActivityManagerState.STATE_STOPPED;
import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.app.Components.ALT_LAUNCHING_ACTIVITY;
import static android.server.wm.app.Components.BROADCAST_RECEIVER_ACTIVITY;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.server.wm.app.Components.NON_RESIZEABLE_ACTIVITY;
import static android.server.wm.app.Components.RESIZEABLE_ACTIVITY;
import static android.server.wm.app.Components.SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY;
import static android.server.wm.app.Components.SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY2;
import static android.server.wm.app.Components.SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY3;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.app.Components.VIRTUAL_DISPLAY_ACTIVITY;
import static android.server.wm.second.Components.SECOND_ACTIVITY;
import static android.server.wm.second.Components.SECOND_LAUNCH_BROADCAST_ACTION;
import static android.server.wm.second.Components.SECOND_LAUNCH_BROADCAST_RECEIVER;
import static android.server.wm.third.Components.THIRD_ACTIVITY;
import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.server.wm.ActivityManagerState.ActivityDisplay;
import android.server.wm.ActivityManagerState.ActivityStack;
import android.server.wm.CommandSession.ActivitySession;
import android.server.wm.CommandSession.SizeInfo;
import android.util.SparseArray;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.Before;
import org.junit.Test;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:MultiDisplayActivityLaunchTests
 *
 *  Tests activity launching behavior on multi-display environment.
 */
@Presubmit
public class MultiDisplayActivityLaunchTests extends MultiDisplayTestBase {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue(supportsMultiDisplay());
    }

    /**
     * Tests launching an activity on virtual display.
     */
    @Test
    public void testLaunchActivityOnSecondaryDisplay() throws Exception {
        validateActivityLaunchOnNewDisplay(ACTIVITY_TYPE_STANDARD);
    }

    /**
     * Tests launching a recent activity on virtual display.
     */
    @Test
    public void testLaunchRecentActivityOnSecondaryDisplay() throws Exception {
        validateActivityLaunchOnNewDisplay(ACTIVITY_TYPE_RECENTS);
    }

    /**
     * Tests launching an assistant activity on virtual display.
     */
    @Test
    public void testLaunchAssistantActivityOnSecondaryDisplay() throws Exception {
        validateActivityLaunchOnNewDisplay(ACTIVITY_TYPE_ASSISTANT);
    }

    private void validateActivityLaunchOnNewDisplay(int activityType) throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Launch activity on new secondary display.
            separateTestJournal();
            getLaunchActivityBuilder().setUseInstrumentation().setWithShellPermission(true)
                    .setTargetActivity(TEST_ACTIVITY).setNewTask(true)
                    .setMultipleTask(true).setActivityType(activityType)
                    .setDisplayId(newDisplay.mId).execute();
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be focused and on top");

            // Check that activity config corresponds to display config.
            final SizeInfo reportedSizes = getLastReportedSizesForActivity(TEST_ACTIVITY);
            assertEquals("Activity launched on secondary display must have proper configuration",
                    CUSTOM_DENSITY_DPI, reportedSizes.densityDpi);

            assertEquals("Top activity must have correct activity type", activityType,
                    mAmWmState.getAmState().getFrontStackActivityType(newDisplay.mId));
        }
    }

    /**
     * Tests launching an activity on primary display explicitly.
     */
    @Test
    public void testLaunchActivityOnPrimaryDisplay() throws Exception {
        // Launch activity on primary display explicitly.
        launchActivityOnDisplay(LAUNCHING_ACTIVITY, DEFAULT_DISPLAY);

        waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, DEFAULT_DISPLAY,
                "Activity launched on primary display must be focused and on top");

        // Launch another activity on primary display using the first one
        getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY).setNewTask(true)
                .setMultipleTask(true).setDisplayId(DEFAULT_DISPLAY).execute();
        mAmWmState.computeState(TEST_ACTIVITY);

        waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                "Activity launched on primary display must be focused");
    }

    /**
     * Tests launching an existing activity from an activity that resided on secondary display.
     */
    @Test
    public void testLaunchActivityFromSecondaryDisplay() throws Exception {
        getLaunchActivityBuilder().setUseInstrumentation()
                .setTargetActivity(TEST_ACTIVITY).setNewTask(true)
                .setDisplayId(DEFAULT_DISPLAY).execute();

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay =
                    virtualDisplaySession.setSimulateDisplay(true).createDisplay();
            final int newDisplayId = newDisplay.mId;

            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(BROADCAST_RECEIVER_ACTIVITY).setNewTask(true)
                    .setDisplayId(newDisplayId).execute();
            waitAndAssertTopResumedActivity(BROADCAST_RECEIVER_ACTIVITY, newDisplay.mId,
                    "Activity should be resumed on secondary display");

            mBroadcastActionTrigger.launchActivityNewTask(getActivityName(TEST_ACTIVITY));
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity should be the top resumed on default display");

            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(TEST_ACTIVITY).setNewTask(true)
                    .setDisplayId(newDisplayId).execute();
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity should be resumed on secondary display");
        }
    }

    /**
     * Tests that an activity can be launched on a secondary display while the primary
     * display is off.
     */
    @Test
    public void testLaunchExternalDisplayActivityWhilePrimaryOff() throws Exception {
        // Launch something on the primary display so we know there is a resumed activity there
        launchActivity(RESIZEABLE_ACTIVITY);
        waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, DEFAULT_DISPLAY,
                "Activity launched on primary display must be resumed");

        try (final PrimaryDisplayStateSession displayStateSession =
                     new PrimaryDisplayStateSession();
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            displayStateSession.turnScreenOff();

            // Make sure there is no resumed activity when the primary display is off
            waitAndAssertActivityState(RESIZEABLE_ACTIVITY, STATE_STOPPED,
                    "Activity launched on primary display must be stopped after turning off");
            assertEquals("Unexpected resumed activity",
                    0, mAmWmState.getAmState().getResumedActivitiesCount());

            final ActivityDisplay newDisplay = externalDisplaySession
                    .setCanShowWithInsecureKeyguard(true).createVirtualDisplay();

            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            // Check that the test activity is resumed on the external display
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on external display must be resumed");
            mAmWmState.assertFocusedAppOnDisplay("App on default display must still be focused",
                    RESIZEABLE_ACTIVITY, DEFAULT_DISPLAY);
        }
    }

    /**
     * Tests launching a non-resizeable activity on virtual display. It should land on the
     * virtual display.
     */
    @Test
    public void testLaunchNonResizeableActivityOnSecondaryDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(NON_RESIZEABLE_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(NON_RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "Activity requested to launch on secondary display must be focused");
        }
    }

    /**
     * Tests successfully moving a non-resizeable activity to a virtual display.
     */
    @Test
    public void testMoveNonResizeableActivityToSecondaryDisplay() throws Exception {
        try (final VirtualDisplayLauncher virtualLauncher = new VirtualDisplayLauncher()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualLauncher.createDisplay();
            // Launch a non-resizeable activity on a primary display.
            final ActivitySession nonResizeableSession = virtualLauncher.launchActivity(
                    builder -> builder.setTargetActivity(NON_RESIZEABLE_ACTIVITY).setNewTask(true));

            // Launch a resizeable activity on new secondary display to create a new stack there.
            virtualLauncher.launchActivityOnDisplay(RESIZEABLE_ACTIVITY, newDisplay);
            final int externalFrontStackId = mAmWmState.getAmState()
                    .getFrontStackId(newDisplay.mId);

            // Clear lifecycle callback history before moving the activity so the later verification
            // can get the callbacks which are related to the reparenting.
            nonResizeableSession.takeCallbackHistory();

            // Try to move the non-resizeable activity to the top of stack on secondary display.
            moveActivityToStack(NON_RESIZEABLE_ACTIVITY, externalFrontStackId);
            // Wait for a while to check that it will move.
            mAmWmState.waitForWithAmState(state ->
                    newDisplay.mId == state.getDisplayByActivity(NON_RESIZEABLE_ACTIVITY),
                    "Waiting to see if activity is moved");
            assertEquals("Non-resizeable activity should be moved",
                    newDisplay.mId,
                    mAmWmState.getAmState().getDisplayByActivity(NON_RESIZEABLE_ACTIVITY));

            waitAndAssertTopResumedActivity(NON_RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "The moved non-resizeable activity must be focused");
            assertActivityLifecycle(nonResizeableSession, true /* relaunched */);
        }
    }

    /**
     * Tests launching a non-resizeable activity on virtual display from activity there. It should
     * land on the secondary display based on the resizeability of the root activity of the task.
     */
    @Test
    public void testLaunchNonResizeableActivityFromSecondaryDisplaySameTask() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display.
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(BROADCAST_RECEIVER_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(BROADCAST_RECEIVER_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be focused");

            // Launch non-resizeable activity from secondary display.
            mBroadcastActionTrigger.launchActivityNewTask(getActivityName(NON_RESIZEABLE_ACTIVITY));
            waitAndAssertTopResumedActivity(NON_RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on the secondary display and resumed");
        }
    }

    /**
     * Tests launching a non-resizeable activity on virtual display in a new task from activity
     * there. It must land on the display as its caller.
     */
    @Test
    public void testLaunchNonResizeableActivityFromSecondaryDisplayNewTask() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be focused");

            // Launch non-resizeable activity from secondary display in a new task.
            getLaunchActivityBuilder().setTargetActivity(NON_RESIZEABLE_ACTIVITY)
                    .setNewTask(true).setMultipleTask(true).execute();

            mAmWmState.waitForActivityState(NON_RESIZEABLE_ACTIVITY, STATE_RESUMED);

            // Check that non-resizeable activity is on the same display.
            final int newFrontStackId = mAmWmState.getAmState().getFocusedStackId();
            final ActivityStack newFrontStack =
                    mAmWmState.getAmState().getStackById(newFrontStackId);
            assertTrue("Launched activity must be on the same display",
                    newDisplay.mId == newFrontStack.mDisplayId);
            assertEquals("Launched activity must be resumed",
                    getActivityName(NON_RESIZEABLE_ACTIVITY),
                    newFrontStack.mResumedActivity);
            mAmWmState.assertFocusedStack(
                    "Top stack must be the one with just launched activity",
                    newFrontStackId);
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(newDisplay.mId, LAUNCHING_ACTIVITY);
                        put(newFrontStack.mDisplayId, NON_RESIZEABLE_ACTIVITY);
                    }}
            );
        }
    }

    /**
     * Tests launching an activity on virtual display and then launching another activity via shell
     * command and without specifying the display id - the second activity must appear on the
     * primary display.
     */
    @Test
    public void testConsequentLaunchActivity() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be on top");

            // Launch second activity without specifying display.
            launchActivity(LAUNCHING_ACTIVITY);

            // Check that activity is launched in focused stack on primary display.
            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, DEFAULT_DISPLAY,
                    "Launched activity must be focused");
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(newDisplay.mId, TEST_ACTIVITY);
                        put(DEFAULT_DISPLAY, LAUNCHING_ACTIVITY);
                    }}
            );
        }
    }

    /**
     * Tests launching an activity on simulated display and then launching another activity from the
     * first one - it must appear on the secondary display, because it was launched from there.
     */
    @Test
    public void testConsequentLaunchActivityFromSecondaryDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display.
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be on top");

            // Launch second activity from app on secondary display without specifying display id.
            getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY).execute();

            // Check that activity is launched in focused stack on external display.
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");
        }
    }

    /**
     * Tests launching an activity on virtual display and then launching another activity from the
     * first one - it must appear on the secondary display, because it was launched from there.
     */
    @Test
    public void testConsequentLaunchActivityFromVirtualDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be on top");

            // Launch second activity from app on secondary display without specifying display id.
            getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY).execute();
            mAmWmState.computeState(TEST_ACTIVITY);

            // Check that activity is launched in focused stack on external display.
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");
        }
    }

    /**
     * Tests launching an activity on virtual display and then launching another activity from the
     * first one with specifying the target display - it must appear on the secondary display.
     */
    @Test
    public void testConsequentLaunchActivityFromVirtualDisplayToTargetDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be on top");

            // Launch second activity from app on secondary display specifying same display id.
            getLaunchActivityBuilder()
                    .setTargetActivity(SECOND_ACTIVITY)
                    .setDisplayId(newDisplay.mId)
                    .execute();

            // Check that activity is launched in focused stack on external display.
            waitAndAssertTopResumedActivity(SECOND_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");

            // Launch other activity with different uid and check if it has launched successfully.
            getLaunchActivityBuilder()
                    .setUseBroadcastReceiver(SECOND_LAUNCH_BROADCAST_RECEIVER,
                            SECOND_LAUNCH_BROADCAST_ACTION)
                    .setDisplayId(newDisplay.mId)
                    .setTargetActivity(THIRD_ACTIVITY)
                    .execute();

            // Check that activity is launched in focused stack on external display.
            waitAndAssertTopResumedActivity(THIRD_ACTIVITY, newDisplay.mId,
                    "Launched activity must be on top");
        }
    }

    /**
     * Tests launching an activity to secondary display from activity on primary display.
     */
    @Test
    public void testLaunchActivityFromAppToSecondaryDisplay() throws Exception {
        // Start launching activity.
        launchActivity(LAUNCHING_ACTIVITY);

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display.
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();

            // Launch activity on secondary display from the app on primary display.
            getLaunchActivityBuilder().setTargetActivity(TEST_ACTIVITY)
                    .setDisplayId(newDisplay.mId).execute();

            // Check that activity is launched on external display.
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be focused");
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, LAUNCHING_ACTIVITY);
                        put(newDisplay.mId, TEST_ACTIVITY);
                    }}
            );
        }
    }

    /** Tests that launching app from pending activity queue on external display is allowed. */
    @Test
    public void testLaunchPendingActivityOnSecondaryDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display.
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();
            final Bundle bundle = ActivityOptions.makeBasic().
                    setLaunchDisplayId(newDisplay.mId).toBundle();
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setComponent(SECOND_ACTIVITY)
                    .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(KEY_LAUNCH_ACTIVITY, true)
                    .putExtra(KEY_NEW_TASK, true);
            mContext.startActivity(intent, bundle);

            // ActivityManagerTestBase.setup would press home key event, which would cause
            // PhoneWindowManager.startDockOrHome to call AMS.stopAppSwitches.
            // Since this test case is not start activity from shell, it won't grant
            // STOP_APP_SWITCHES and this activity should be put into pending activity queue
            // and this activity should been launched after
            // ActivityTaskManagerService.APP_SWITCH_DELAY_TIME
            mAmWmState.waitForPendingActivityContain(SECOND_ACTIVITY);
            // If the activity is not pending, skip this test.
            mAmWmState.assumePendingActivityContain(SECOND_ACTIVITY);
            // In order to speed up test case without waiting for APP_SWITCH_DELAY_TIME, we launch
            // another activity with LaunchActivityBuilder, in this way the activity can be start
            // directly and also trigger pending activity to be launched.
            getLaunchActivityBuilder()
                    .setTargetActivity(THIRD_ACTIVITY)
                    .execute();
            mAmWmState.waitForValidState(SECOND_ACTIVITY);
            waitAndAssertTopResumedActivity(THIRD_ACTIVITY, DEFAULT_DISPLAY,
                    "Top activity must be the newly launched one");
            mAmWmState.assertVisibility(SECOND_ACTIVITY, true);
            assertEquals("Activity launched by app on secondary display must be on that display",
                    newDisplay.mId, mAmWmState.getAmState().getDisplayByActivity(SECOND_ACTIVITY));
        }
    }

    /**
     * Tests that when an activity is launched with displayId specified and there is an existing
     * matching task on some other display - that task will moved to the target display.
     */
    @Test
    public void testMoveToDisplayOnLaunch() throws Exception {
        // Launch activity with unique affinity, so it will the only one in its task.
        launchActivity(LAUNCHING_ACTIVITY);

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);
            // Launch something to that display so that a new stack is created. We need this to be
            // able to compare task numbers in stacks later.
            launchActivityOnDisplay(RESIZEABLE_ACTIVITY, newDisplay.mId);
            mAmWmState.assertVisibility(RESIZEABLE_ACTIVITY, true /* visible */);

            final int stackNum = mAmWmState.getAmState().getDisplay(DEFAULT_DISPLAY)
                    .mStacks.size();
            final int stackNumOnSecondary = mAmWmState.getAmState()
                    .getDisplay(newDisplay.mId).mStacks.size();

            // Launch activity on new secondary display.
            // Using custom command here, because normally we add flags
            // {@link Intent#FLAG_ACTIVITY_NEW_TASK} and {@link Intent#FLAG_ACTIVITY_MULTIPLE_TASK}
            // when launching on some specific display. We don't do it here as we want an existing
            // task to be used.
            final String launchCommand = "am start -n " + getActivityName(LAUNCHING_ACTIVITY)
                    + " --display " + newDisplay.mId;
            executeShellCommand(launchCommand);

            // Check that activity is brought to front.
            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, newDisplay.mId,
                    "Existing task must be brought to front");

            // Check that task has moved from primary display to secondary.
            // Since it is 1-to-1 relationship between task and stack for standard type &
            // fullscreen activity, we check the number of stacks here
            final int stackNumFinal = mAmWmState.getAmState().getDisplay(DEFAULT_DISPLAY)
                    .mStacks.size();
            assertEquals("Stack number in default stack must be decremented.", stackNum - 1,
                    stackNumFinal);
            final int stackNumFinalOnSecondary = mAmWmState.getAmState()
                    .getDisplay(newDisplay.mId).mStacks.size();
            assertEquals("Stack number on external display must be incremented.",
                    stackNumOnSecondary + 1, stackNumFinalOnSecondary);
        }
    }

    /**
     * Tests that when an activity is launched with displayId specified and there is an existing
     * matching task on some other display - that task will moved to the target display.
     */
    @Test
    public void testMoveToEmptyDisplayOnLaunch() throws Exception {
        // Launch activity with unique affinity, so it will the only one in its task. And choose
        // resizeable activity to prevent the test activity be relaunched when launch it to another
        // display, which may affect on this test case.
        launchActivity(RESIZEABLE_ACTIVITY);

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            final int stackNum = mAmWmState.getAmState().getDisplay(DEFAULT_DISPLAY).mStacks.size();

            // Launch activity on new secondary display.
            // Using custom command here, because normally we add flags
            // {@link Intent#FLAG_ACTIVITY_NEW_TASK} and {@link Intent#FLAG_ACTIVITY_MULTIPLE_TASK}
            // when launching on some specific display. We don't do it here as we want an existing
            // task to be used.
            final String launchCommand = "am start -n " + getActivityName(RESIZEABLE_ACTIVITY)
                    + " --display " + newDisplay.mId;
            executeShellCommand(launchCommand);

            // Check that activity is brought to front.
            waitAndAssertTopResumedActivity(RESIZEABLE_ACTIVITY, newDisplay.mId,
                    "Existing task must be brought to front");

            // Check that task has moved from primary display to secondary.
            final int stackNumFinal = mAmWmState.getAmState().getDisplay(DEFAULT_DISPLAY)
                    .mStacks.size();
            assertEquals("Stack number in default stack must be decremented.", stackNum - 1,
                    stackNumFinal);
        }
    }

    /**
     * Tests that task affinity does affect what display an activity is launched on but that
     * matching the task component root does.
     */
    @Test
    public void testTaskMatchAcrossDisplays() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);
            mAmWmState.computeState(LAUNCHING_ACTIVITY);

            // Check that activity is on the secondary display.
            final int frontStackId = mAmWmState.getAmState().getFrontStackId(newDisplay.mId);
            final ActivityStack firstFrontStack =
                    mAmWmState.getAmState().getStackById(frontStackId);
            assertEquals("Activity launched on secondary display must be resumed",
                    getActivityName(LAUNCHING_ACTIVITY), firstFrontStack.mResumedActivity);
            mAmWmState.assertFocusedStack("Top stack must be on secondary display",
                    frontStackId);

            executeShellCommand("am start -n " + getActivityName(ALT_LAUNCHING_ACTIVITY));
            mAmWmState.waitForValidState(ALT_LAUNCHING_ACTIVITY);

            // Check that second activity gets launched on the default display despite
            // the affinity match on the secondary display.
            final int defaultDisplayFrontStackId = mAmWmState.getAmState().getFrontStackId(
                    DEFAULT_DISPLAY);
            final ActivityStack defaultDisplayFrontStack =
                    mAmWmState.getAmState().getStackById(defaultDisplayFrontStackId);
            assertEquals("Activity launched on default display must be resumed",
                    getActivityName(ALT_LAUNCHING_ACTIVITY),
                    defaultDisplayFrontStack.mResumedActivity);
            mAmWmState.assertFocusedStack("Top stack must be on primary display",
                    defaultDisplayFrontStackId);

            executeShellCommand("am start -n " + getActivityName(LAUNCHING_ACTIVITY));
            mAmWmState.waitForFocusedStack(frontStackId);

            // Check that the third intent is redirected to the first task due to the root
            // component match on the secondary display.
            final ActivityStack secondFrontStack =
                    mAmWmState.getAmState().getStackById(frontStackId);
            assertEquals("Activity launched on secondary display must be resumed",
                    getActivityName(LAUNCHING_ACTIVITY), secondFrontStack.mResumedActivity);
            mAmWmState.assertFocusedStack("Top stack must be on primary display", frontStackId);
            assertEquals("Top stack must only contain 1 task",
                    1, secondFrontStack.getTasks().size());
            assertEquals("Top task must only contain 1 activity",
                    1, secondFrontStack.getTasks().get(0).mActivities.size());
        }
    }

    /**
     * Tests that an activity is launched on the preferred display where the caller resided when
     * both displays have matching tasks.
     */
    @Test
    public void testTaskMatchOrderAcrossDisplays() throws Exception {
        getLaunchActivityBuilder().setUseInstrumentation()
                .setTargetActivity(TEST_ACTIVITY).setNewTask(true)
                .setDisplayId(DEFAULT_DISPLAY).execute();
        final int stackId = mAmWmState.getAmState().getFrontStackId(DEFAULT_DISPLAY);

        getLaunchActivityBuilder().setUseInstrumentation()
                .setTargetActivity(BROADCAST_RECEIVER_ACTIVITY).setNewTask(true)
                .setDisplayId(DEFAULT_DISPLAY).execute();

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            getLaunchActivityBuilder().setUseInstrumentation().setWithShellPermission(true)
                    .setTargetActivity(TEST_ACTIVITY).setNewTask(true)
                    .setDisplayId(newDisplay.mId).execute();
            assertNotEquals("Top focus stack should not be on default display",
                    stackId, mAmWmState.getAmState().getFocusedStackId());

            mBroadcastActionTrigger.launchActivityNewTask(getActivityName(TEST_ACTIVITY));
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity must be launched on default display");
            mAmWmState.assertFocusedStack("Top focus stack must be on the default display",
                    stackId);
        }
    }

    /**
     * Tests that the task affinity search respects the launch display id.
     */
    @Test
    public void testLaunchDisplayAffinityMatch() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);

            // Check that activity is on the secondary display.
            final int frontStackId = mAmWmState.getAmState().getFrontStackId(newDisplay.mId);
            final ActivityStack firstFrontStack =
                    mAmWmState.getAmState().getStackById(frontStackId);
            assertEquals("Activity launched on secondary display must be resumed",
                    getActivityName(LAUNCHING_ACTIVITY), firstFrontStack.mResumedActivity);
            mAmWmState.assertFocusedStack("Focus must be on secondary display", frontStackId);

            // We don't want FLAG_ACTIVITY_MULTIPLE_TASK, so we can't use launchActivityOnDisplay
            executeShellCommand("am start -n " + getActivityName(ALT_LAUNCHING_ACTIVITY)
                    + " -f 0x10000000" // FLAG_ACTIVITY_NEW_TASK
                    + " --display " + newDisplay.mId);
            mAmWmState.computeState(ALT_LAUNCHING_ACTIVITY);

            // Check that second activity gets launched into the affinity matching
            // task on the secondary display
            final int secondFrontStackId =
                    mAmWmState.getAmState().getFrontStackId(newDisplay.mId);
            final ActivityStack secondFrontStack =
                    mAmWmState.getAmState().getStackById(secondFrontStackId);
            assertEquals("Activity launched on secondary display must be resumed",
                    getActivityName(ALT_LAUNCHING_ACTIVITY),
                    secondFrontStack.mResumedActivity);
            mAmWmState.assertFocusedStack("Top stack must be on secondary display",
                    secondFrontStackId);
            assertEquals("Top stack must only contain 1 task",
                    1, secondFrontStack.getTasks().size());
            assertEquals("Top stack task must contain 2 activities",
                    2, secondFrontStack.getTasks().get(0).mActivities.size());
        }
    }

    /**
     * Tests that a new task launched by an activity will end up on that activity's display
     * even if the focused stack is not on that activity's display.
     */
    @Test
    public void testNewTaskSameDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();

            launchActivityOnDisplay(BROADCAST_RECEIVER_ACTIVITY, newDisplay.mId);

            // Check that the first activity is launched onto the secondary display
            waitAndAssertTopResumedActivity(BROADCAST_RECEIVER_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be resumed");

            executeShellCommand("am start -n " + getActivityName(TEST_ACTIVITY));

            // Check that the second activity is launched on the default display
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity launched on default display must be resumed");
            mAmWmState.assertResumedActivities("Both displays should have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, TEST_ACTIVITY);
                        put(newDisplay.mId, BROADCAST_RECEIVER_ACTIVITY);
                    }}
            );

            mBroadcastActionTrigger.launchActivityNewTask(getActivityName(LAUNCHING_ACTIVITY));

            // Check that the third activity ends up in a new stack in the same display where the
            // first activity lands
            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, newDisplay.mId,
                    "Activity must be launched on secondary display");
            assertEquals("Secondary display must contain 2 stacks", 2,
                    mAmWmState.getAmState().getDisplay(newDisplay.mId).mStacks.size());
            mAmWmState.assertResumedActivities("Both displays should have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, TEST_ACTIVITY);
                        put(newDisplay.mId, LAUNCHING_ACTIVITY);
                    }}
            );
        }
    }

    /**
     * Tests than an immediate launch after new display creation is handled correctly.
     */
    @Test
    public void testImmediateLaunchOnNewDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display and immediately launch an activity on it.
            final ActivityDisplay newDisplay = virtualDisplaySession
                    .setLaunchActivity(TEST_ACTIVITY)
                    .createDisplay();

            // Check that activity is launched and placed correctly.
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Test activity must be on top");
            final int frontStackId = mAmWmState.getAmState().getFrontStackId(newDisplay.mId);
            final ActivityStack firstFrontStack =
                    mAmWmState.getAmState().getStackById(frontStackId);
            assertEquals("Activity launched on secondary display must be resumed",
                    getActivityName(TEST_ACTIVITY), firstFrontStack.mResumedActivity);
            mAmWmState.assertFocusedStack("Top stack must be on secondary display",
                    frontStackId);
        }
    }

    /** Tests launching of activities on a single task instance display. */
    @Test
    public void testSingleTaskInstanceDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            ActivityDisplay display =
                    virtualDisplaySession.setSimulateDisplay(true).createDisplay();
            final int displayId = display.mId;

            SystemUtil.runWithShellPermissionIdentity(
                    () -> mAtm.setDisplayToSingleTaskInstance(displayId));
            display = getDisplayState(displayId);
            assertTrue("Display must be set to singleTaskInstance", display.mSingleTaskInstance);

            // SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY will launch
            // SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY2 in the same task and
            // SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY3 in different task.
            launchActivityOnDisplay(SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY, displayId);

            waitAndAssertTopResumedActivity(SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY3, DEFAULT_DISPLAY,
                    "Activity should be resumed on default display");

            display = getDisplayState(displayId);
            // Verify that the 2 activities in the same task are on the display and the one in a
            // different task isn't on the display, but on the default display
            assertTrue("Display should contain SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY",
                    display.containsActivity(SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY));
            assertTrue("Display should contain SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY2",
                    display.containsActivity(SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY2));

            assertFalse("Display shouldn't contain SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY3",
                    display.containsActivity(SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY3));
            assertTrue("Display should contain SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY3",
                    getDisplayState(DEFAULT_DISPLAY).containsActivity(
                            SINGLE_TASK_INSTANCE_DISPLAY_ACTIVITY3));
        }
    }
}
