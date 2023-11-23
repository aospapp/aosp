/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.server.wm.lifecycle;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.server.wm.ActivityManagerState.STATE_DESTROYED;
import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.app.Components.ALIAS_TEST_ACTIVITY;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_RESUME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.server.wm.ActivityLauncher;

import org.junit.Test;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:ActivityStarterTests
 */
@Presubmit
public class ActivityStarterTests extends ActivityLifecycleClientTestBase {

    private static final ComponentName STANDARD_ACTIVITY
            = getComponentName(StandardActivity.class);
    private static final ComponentName SECOND_STANDARD_ACTIVITY
            = getComponentName(SecondStandardActivity.class);
    private static final ComponentName SINGLE_TOP_ACTIVITY
            = getComponentName(SingleTopActivity.class);
    private static final ComponentName SINGLE_INSTANCE_ACTIVITY
            = getComponentName(SingleInstanceActivity.class);
    private static final ComponentName SINGLE_TASK_ACTIVITY
            = getComponentName(SingleTaskActivity.class);
    private static final ComponentName STANDARD_SINGLE_TOP_ACTIVITY
            = getComponentName(StandardWithSingleTopActivity.class);
    private static final ComponentName TEST_LAUNCHING_ACTIVITY
            = getComponentName(TestLaunchingActivity.class);
    private static final ComponentName LAUNCHING_AND_FINISH_ACTIVITY
            = getComponentName(LaunchingAndFinishActivity.class);


    /**
     * Ensures that the following launch flag combination works when starting an activity which is
     * already running:
     * - {@code FLAG_ACTIVITY_CLEAR_TOP}
     * - {@code FLAG_ACTIVITY_RESET_TASK_IF_NEEDED}
     * - {@code FLAG_ACTIVITY_NEW_TASK}
     */
    @Test
    public void testClearTopNewTaskResetTask() throws Exception {
        // Start activity normally
        final Activity initialActivity = mFirstActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(initialActivity, ON_RESUME));

        // Navigate home
        launchHomeActivity();

        // Start activity again with flags in question. Verify activity is resumed.
        final Intent intent = new Intent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        final Activity secondLaunchActivity = mFirstActivityTestRule.launchActivity(intent);
        mAmWmState.waitForActivityState(secondLaunchActivity.getComponentName(), STATE_RESUMED);
        assertEquals("The activity should be started and be resumed",
                getActivityName(secondLaunchActivity.getComponentName()),
                mAmWmState.getAmState().getTopActivityName(0));
    }

    /**
     * This test case tests "standard" activity behavior.
     * A first launched standard activity and a second launched standard activity
     * must be in same task.
     */
    @Test
    public void testLaunchStandardActivity() {
        // Launch a standard activity.
        launchActivity(STANDARD_ACTIVITY);

        final int taskId = mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId();
        final int instances = mAmWmState.getAmState().getActivityCountInTask(taskId, null);

        // Launch a second standard activity.
        launchActivity(SECOND_STANDARD_ACTIVITY);

        // Make sure instances in task are increased.
        assertEquals("instances of activity in task must be increased.", instances + 1,
                mAmWmState.getAmState().getActivityCountInTask(taskId, null));

        // Make sure the stack for the second standard activity is front.
        assertEquals("The stack for the second standard activity must be front.",
                getActivityName(SECOND_STANDARD_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));

        // Make sure the standard activity and the second standard activity are in same task.
        assertEquals("Activity must be in same task.", taskId,
                mAmWmState.getAmState().getTaskByActivity(SECOND_STANDARD_ACTIVITY).getTaskId());
    }

    /**
     * This test case tests "single top" activity behavior.
     * - A first launched standard activity and a second launched single top
     * activity are in same task.
     * - A new instance of single top activity is not created if task
     * already has a its activity at the top of its task.
     */
    @Test
    public void testLaunchSingleTopActivity() {
        // Launch a standard activity.
        launchActivity(STANDARD_ACTIVITY);

        final int taskId = mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId();

        // Launch a single top activity.
        launchActivity(SINGLE_TOP_ACTIVITY);

        final int instances = mAmWmState.getAmState().getActivityCountInTask(taskId, null);

        // Make sure the single top activity is in focus.
        mAmWmState.assertFocusedActivity(SINGLE_TOP_ACTIVITY + "must be focused Activity",
                SINGLE_TOP_ACTIVITY);

        // Make sure the stack for the single top activity is front.
        assertEquals("The stack for the single top activity must be front.",
                getActivityName(SINGLE_TOP_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));

        // Make sure the standard activity and the single top activity are in same task.
        assertEquals("Two activities must be in same task.", taskId,
                mAmWmState.getAmState().getTaskByActivity(SINGLE_TOP_ACTIVITY).getTaskId());

        // Launch a single top activity.
        launchActivity(SINGLE_TOP_ACTIVITY);

        // Make sure that instances of activity are not increased.
        assertEquals("instances of activity must not be increased.", instances,
                mAmWmState.getAmState().getActivityCountInTask(taskId, null));
    }

    /**
     * This test case tests "single instance" activity behavior.
     * - A first launched standard activity and a second launched single instance
     * activity are not in same task.
     * - A single instance activity is always the single and only member of its task.
     */
    @Test
    public void testLaunchSingleInstanceActivity() {
        // Launch a standard activity.
        launchActivity(STANDARD_ACTIVITY);

        final int firstTaskId = mAmWmState.getAmState()
                .getTaskByActivity(STANDARD_ACTIVITY).getTaskId();

        // Launch a single instance activity
        launchActivity(SINGLE_INSTANCE_ACTIVITY);

        final int secondTaskId = mAmWmState.getAmState()
                .getTaskByActivity(SINGLE_INSTANCE_ACTIVITY).getTaskId();

        // Make sure the single instance activity is in focus.
        mAmWmState.assertFocusedActivity(SINGLE_INSTANCE_ACTIVITY + "must be focused Activity",
                SINGLE_INSTANCE_ACTIVITY);
        // Make sure the single instance activity is front.
        assertEquals("The stack for the single instance activity must be front.",
                getActivityName(SINGLE_INSTANCE_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));

        // Make sure the standard activity and the test activity are not in same task.
        assertNotEquals("Activity must be in different task.", firstTaskId, secondTaskId);

        // Make sure the single instance activity is only member of its task.
        assertEquals("Single instance activity is only member of its task", 1,
                mAmWmState.getAmState().getActivityCountInTask(secondTaskId, null));
    }

    /**
     * This test case tests "single task" activity behavior.
     * - A first launched standard activity and a second launched single task activity
     * are in same task.
     * - Instance of single task activity is only one in its task.
     */
    @Test
    public void testLaunchSingleTaskActivity() {
        // Launch a standard activity.
        launchActivity(STANDARD_ACTIVITY);

        final int taskId = mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId();

        // Launch a single task activity
        launchActivity(SINGLE_TASK_ACTIVITY);

        // Make sure the single task activity is in focus.
        mAmWmState.assertFocusedActivity(SINGLE_TASK_ACTIVITY + "must be focused Activity",
                SINGLE_TASK_ACTIVITY);

        // Make sure the stack for the single task activity is front.
        assertEquals("The stack for the single task activity must be front.",
                getActivityName(SINGLE_TASK_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));

        // Make sure the test activity is in same task.
        assertEquals("Activity must be in same task.", taskId,
                mAmWmState.getAmState().getTaskByActivity(SINGLE_TASK_ACTIVITY).getTaskId());

        // Launch a second standard activity
        launchActivity(SECOND_STANDARD_ACTIVITY);

        // Launch a single task activity again.
        launchActivity(SINGLE_TASK_ACTIVITY);
        mAmWmState.waitForActivityState(SECOND_STANDARD_ACTIVITY, STATE_DESTROYED);

        // Make sure the number of instances for single task activity is only one.
        assertEquals("Instance of single task activity in its task must be only one", 1,
                mAmWmState.getAmState().getActivityCountInTask(taskId, SINGLE_TASK_ACTIVITY));
        // Make sure that instance of standard activity does not exists.
        assertEquals("Instance of second standard activity must not exist.", 0,
                mAmWmState.getAmState().getActivityCountInTask(taskId, SECOND_STANDARD_ACTIVITY));

    }

    /**
     * Tests that the existing task would be brought to top while launching alias activity or
     * real activity without creating new activity instances, tasks, or stacks.
     */
    @Test
    public void testLaunchAliasActivity() {
        // Launch alias activity.
        getLaunchActivityBuilder().setUseInstrumentation().setTargetActivity(ALIAS_TEST_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_NEW_TASK).execute();
        final int stacks = mAmWmState.getAmState().getStackCounts();
        final int taskId =
                mAmWmState.getAmState().getTaskByActivity(ALIAS_TEST_ACTIVITY).getTaskId();

        // Return to home and launch the alias activity again.
        launchHomeActivity();
        getLaunchActivityBuilder().setUseInstrumentation().setTargetActivity(ALIAS_TEST_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_NEW_TASK).execute();
        assertEquals("Instance of the activity in its task must be only one", 1,
                mAmWmState.getAmState().getActivityCountInTask(taskId, ALIAS_TEST_ACTIVITY));
        assertEquals("Stacks counts should not be increased.", stacks,
                mAmWmState.getAmState().getStackCounts());

        // Return to home and launch the real activity.
        launchHomeActivity();
        getLaunchActivityBuilder().setUseInstrumentation().setTargetActivity(TEST_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_NEW_TASK).execute();
        assertEquals("Instance of the activity in its task must be only one", 1,
                mAmWmState.getAmState().getActivityCountInTask(taskId, ALIAS_TEST_ACTIVITY));
        assertEquals("Stacks counts should not be increased.", stacks,
                mAmWmState.getAmState().getStackCounts());
    }

    /**
     * This test case tests behavior of activities launched with FLAG_ACTIVITY_NEW_TASK
     * and FLAG_ACTIVITY_CLEAR_TASK.
     * A first launched activity is finished, then a second activity is created if the
     * second activity is launched with FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_CLEAR_TASK.
     */
    @Test
    public void testLaunchActivityWithFlagNewTaskAndClearTask() {
        // Launch a standard activity with FLAG_ACTIVITY_NEW_TASK.
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_NEW_TASK)
                .execute();

        final int taskId = mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId();

        // Launch Activity with FLAG_ACTIVITY_NEW_TASK|FLAG_ACTIVITY_CLEAR_TASK.
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_ACTIVITY)
                .setLaunchingActivity(TEST_LAUNCHING_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)
                .execute();

        mAmWmState.waitForActivityState(STANDARD_ACTIVITY, STATE_DESTROYED);

        // Make sure the number of instances for standard activity is one
        // because previous standard activity to be finished due to FLAG_ACTIVITY_CLEAR_TASK.
        assertEquals("Instance of activity must be one", 1,
                mAmWmState.getAmState().getActivityCountInTask(taskId, STANDARD_ACTIVITY));

        // Make sure the stack for the standard activity is front.
        assertEquals("The stack for the standard activity must be front.",
                getActivityName(STANDARD_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));
    }

    /**
     * This test case tests behavior of activity launched with FLAG_ACTIVITY_CLEAR_TOP.
     * A top activity is finished when an activity is launched with FLAG_ACTIVITY_CLEAR_TOP.
     */
    @Test
    public void testLaunchActivityWithFlagClearTop() {
        // Launch a standard activity
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_ACTIVITY)
                .execute();

        final int taskId = mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId();

        // Launch a second standard activity
        getLaunchActivityBuilder()
                .setTargetActivity(SECOND_STANDARD_ACTIVITY)
                .setLaunchingActivity(TEST_LAUNCHING_ACTIVITY)
                .execute();

        // Launch a standard activity again with CLEAR_TOP_FLAG
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_ACTIVITY)
                .setLaunchingActivity(TEST_LAUNCHING_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_CLEAR_TOP)
                .execute();

        mAmWmState.waitForActivityState(STANDARD_ACTIVITY, STATE_RESUMED);

        // Make sure that the standard activity is in focus.
        mAmWmState.assertFocusedActivity(STANDARD_ACTIVITY + "must be focused Activity",
                STANDARD_ACTIVITY);

        // Make sure the stack for the standard activity is front.
        assertEquals("The stack for the standard activity must be front.",
                getActivityName(STANDARD_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));

        // Make sure the activity is not in same task.
        assertEquals("Activity must be in same task.", taskId,
                mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId());
        // Make sure the second standard activity is finished.
        final String waitFinishMsg = "Instance of second standard activity must not exist";
        mAmWmState.waitForWithAmState((amState) ->
            0 == amState.getActivityCountInTask(taskId, SECOND_STANDARD_ACTIVITY),
            waitFinishMsg);
        assertEquals(waitFinishMsg, 0,
            mAmWmState.getAmState().getActivityCountInTask(taskId, SECOND_STANDARD_ACTIVITY));
    }

    @Test
    public void testLaunchActivityWithFlagPreviousIsTop() {
        // Launch a standard activity
        getLaunchActivityBuilder()
                .setTargetActivity(SINGLE_TOP_ACTIVITY)
                .execute();

        final int taskId = mAmWmState.getAmState().getTaskByActivity(
                SINGLE_TOP_ACTIVITY).getTaskId();

        // Launch a standard activity again with PREVIOUS_IS_TOP
        getLaunchActivityBuilder()
                .setTargetActivity(SINGLE_TOP_ACTIVITY)
                .setLaunchingActivity(LAUNCHING_AND_FINISH_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                .execute();

        assertEquals("Instance of activity must be one", 1,
                mAmWmState.getAmState().getActivityCountInTask(taskId, SINGLE_TOP_ACTIVITY));
    }

    /**
     * This test case tests behavior of activity launched with FLAG_ACTIVITY_SINGLE_TOP.
     * A single top activity must not be launched if it is already running at the top
     * of the history stack.
     */
    @Test
    public void testLaunchActivityWithFlagSingleTop() {
        // Launch a standard activity
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_ACTIVITY)
                .execute();

        final int taskId = mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId();

        // Launch a standard activity with SINGLE_TOP flag.
        // This standard activity launches a standard activity with single top flag.
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_SINGLE_TOP_ACTIVITY)
                .setLaunchingActivity(TEST_LAUNCHING_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_SINGLE_TOP)
                .execute();

        mAmWmState.waitForActivityState(STANDARD_SINGLE_TOP_ACTIVITY, STATE_RESUMED);

        // Make sure that a new instance is not created if it is already running at the top
        // of the history stack.
        assertEquals("Multiple single top activities must not be created.", 1,
                mAmWmState.getAmState()
                        .getActivityCountInTask(taskId, STANDARD_SINGLE_TOP_ACTIVITY));


        // Make sure that activity is in focus.
        mAmWmState.assertFocusedActivity(STANDARD_SINGLE_TOP_ACTIVITY + "must be focused Activity",
                STANDARD_SINGLE_TOP_ACTIVITY);
        // Make sure the stack for the single top activity is front.
        assertEquals("The stack for the single top activity must be front.",
                getActivityName(STANDARD_SINGLE_TOP_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));

        // Make sure the standard activity and the single top activity are in same task.
        assertEquals("Activity must be in same task.", taskId,
                mAmWmState.getAmState().getTaskByActivity(STANDARD_SINGLE_TOP_ACTIVITY)
                        .getTaskId());
    }

    /**
     * This test case tests behavior of activity launched with FLAG_ACTIVITY_MULTIPLE_TASK
     * and FLAG_ACTIVITY_NEW_TASK.
     * Second launched activity which is launched with FLAG_ACTIVITY_MULTIPLE_TASK and
     * FLAG_ACTIVITY_NEW_TASK is created in new task/stack. So, a first launched activity
     * and a second launched activity are in different task/stack.
     */
    @Test
    public void testActivityWithFlagMultipleTaskAndNewTask() {
        // Launch a standard activity
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .execute();

        final int taskId = mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId();

        // Launch a standard activity with FLAG_ACTIVITY_MULTIPLE_TASK|FLAG_ACTIVITY_NEW_TASK
        getLaunchActivityBuilder()
                .setTargetActivity(STANDARD_ACTIVITY)
                .setLaunchingActivity(TEST_LAUNCHING_ACTIVITY)
                .setIntentFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .execute();

        // Make sure the stack for the standard activity is front.
        assertEquals("The stack for the standard activity must be front.",
                getActivityName(STANDARD_ACTIVITY),
                mAmWmState.getAmState().getTopActivityName(0));
        // Make sure the first standard activity and second standard activity are not in same task.
        assertNotEquals("Activity must not be in same task.", taskId,
                mAmWmState.getAmState().getTaskByActivity(STANDARD_ACTIVITY).getTaskId());
    }

    // Test activity
    public static class StandardActivity extends Activity {
    }

    // Test activity
    public static class SecondStandardActivity extends Activity {
    }

    // Test activity
    public static class StandardWithSingleTopActivity extends Activity {

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            final Intent intent = new Intent().setComponent(
                    new ComponentName(this /* context */, getClass()));
            intent.addFlags(FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }
    }

    // Test activity
    public static class SingleTopActivity extends Activity {
    }

    // Test activity
    public static class SingleTaskActivity extends Activity {
    }

    // Test activity
    public static class SingleInstanceActivity extends Activity {
    }

    // Launching activity
    public static class TestLaunchingActivity extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            final Intent intent = getIntent();
            if (intent != null) {
                ActivityLauncher.launchActivityFromExtras(this, intent.getExtras());
            }
        }

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            ActivityLauncher.launchActivityFromExtras(this, intent.getExtras());
        }
    }

    public static class LaunchingAndFinishActivity extends TestLaunchingActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            finish();
        }
    }
}
