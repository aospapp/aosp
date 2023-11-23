/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.server.wm.TaskFragmentOrganizerTestBase.assertEmptyTaskFragment;
import static android.server.wm.TaskFragmentOrganizerTestBase.assertNotEmptyTaskFragment;
import static android.server.wm.TaskFragmentOrganizerTestBase.getActivityToken;
import static android.server.wm.WindowManagerState.STATE_RESUMED;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.server.wm.app30.Components.SDK_30_TEST_ACTIVITY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.server.wm.TaskFragmentOrganizerTestBase.BasicTaskFragmentOrganizer;
import android.server.wm.WindowContextTests.TestActivity;
import android.window.TaskAppearedInfo;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests that verify the behavior of {@link TaskFragmentOrganizer} policy.
 *
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:TaskFragmentOrganizerPolicyTest
 */
@RunWith(AndroidJUnit4.class)
@Presubmit
public class TaskFragmentOrganizerPolicyTest extends ActivityManagerTestBase {
    private TaskOrganizer mTaskOrganizer;
    private BasicTaskFragmentOrganizer mTaskFragmentOrganizer;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mTaskFragmentOrganizer = new BasicTaskFragmentOrganizer();
        mTaskFragmentOrganizer.registerOrganizer();
    }

    @After
    public void tearDown() {
        if (mTaskFragmentOrganizer != null) {
            mTaskFragmentOrganizer.unregisterOrganizer();
        }
    }

    /**
     * Verifies whether performing non-TaskFragment
     * {@link android.window.WindowContainerTransaction.HierarchyOp operations} on
     * {@link TaskFragmentOrganizer} without permission throws {@link SecurityException}.
     */
    @Test(expected = SecurityException.class)
    public void testPerformNonTaskFragmentHierarchyOperation_ThrowException() {
        final List<TaskAppearedInfo> taskInfos = new ArrayList<>();
        try {
            // Register TaskOrganizer to obtain Task information.
            NestedShellPermission.run(() -> {
                mTaskOrganizer = new TaskOrganizer();
                taskInfos.addAll(mTaskOrganizer.registerOrganizer());
            });

            // It is expected to throw Security exception when TaskFragmentOrganizer performs a
            // non-TaskFragment hierarchy operation.
            final WindowContainerToken taskToken = taskInfos.get(0).getTaskInfo().getToken();
            final WindowContainerTransaction wct = new WindowContainerTransaction()
                    .reorder(taskToken, true /* opTop */);
            mTaskFragmentOrganizer.applyTransaction(wct);
        } finally {
            if (mTaskOrganizer != null) {
                NestedShellPermission.run(() -> mTaskOrganizer.unregisterOrganizer());
            }
        }
    }

    /**
     * Verifies whether changing property on non-TaskFragment window container without permission
     * throws {@link SecurityException}.
     */
    @Test(expected = SecurityException.class)
    public void testSetPropertyOnNonTaskFragment_ThrowException() {
        final List<TaskAppearedInfo> taskInfos = new ArrayList<>();
        try {
            // Register TaskOrganizer to obtain Task information.
            NestedShellPermission.run(() -> {
                mTaskOrganizer = new TaskOrganizer();
                taskInfos.addAll(mTaskOrganizer.registerOrganizer());
            });

            // It is expected to throw SecurityException when TaskFragmentOrganizer attempts to
            // change the property on non-TaskFragment container.
            final WindowContainerToken taskToken = taskInfos.get(0).getTaskInfo().getToken();
            final WindowContainerTransaction wct = new WindowContainerTransaction()
                    .setBounds(taskToken, new Rect());
            mTaskFragmentOrganizer.applyTransaction(wct);
        } finally {
            if (mTaskOrganizer != null) {
                NestedShellPermission.run(() -> mTaskOrganizer.unregisterOrganizer());
            }
        }
    }

    /**
     * Verifies whether performing TaskFragment
     * {@link android.window.WindowContainerTransaction.HierarchyOp operations} on the TaskFragment
     * which is not organized by given {@link TaskFragmentOrganizer} throws
     * {@link SecurityException}.
     */
    @Test(expected = SecurityException.class)
    public void testPerformOperationsOnNonOrganizedTaskFragment_ThrowException() {
        final Activity activity = startNewActivity();

        // Create a TaskFragment with a TaskFragmentOrganizer.
        final TaskFragmentCreationParams params = mTaskFragmentOrganizer.generateTaskFragParams(
                getActivityToken(activity));
        final IBinder taskFragToken = params.getFragmentToken();
        WindowContainerTransaction wct = new WindowContainerTransaction()
                .createTaskFragment(params);
        mTaskFragmentOrganizer.applyTransaction(wct);
        // Wait for TaskFragment's creation to obtain its WindowContainerToken.
        mTaskFragmentOrganizer.waitForTaskFragmentCreated();

        // Create another TaskFragmentOrganizer
        final TaskFragmentOrganizer anotherOrganizer = new TaskFragmentOrganizer(Runnable::run);
        anotherOrganizer.registerOrganizer();
        // Try to perform an operation on the TaskFragment when is organized by the previous
        // TaskFragmentOrganizer.
        wct = new WindowContainerTransaction()
                .deleteTaskFragment(mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken)
                        .getToken());

        // It is expected to throw SecurityException when performing operations on the TaskFragment
        // which is not organized by the same TaskFragmentOrganizer.
        anotherOrganizer.applyTransaction(wct);
    }

    /**
     * Verifies the behavior to start Activity from another process in a new created Task under
     * TaskFragment with {@link android.permission#ACTIVITY_EMBEDDING}.
     * <p>
     * If the application to start Activity holds the permission, the activity is allowed to start
     * on the Task. Otherwise, the TaskFragment should remain empty.
     * </p>
     */
    @Test
    @Ignore("b/197364677")
    public void testStartActivityFromAnotherProcessInEmbeddedTask() {
        final Activity activity = startNewActivity();
        final IBinder ownerToken = getActivityToken(activity);
        final TaskFragmentCreationParams params = mTaskFragmentOrganizer.generateTaskFragParams(
                ownerToken);
        final IBinder taskFragToken = params.getFragmentToken();
        final Intent intent = new Intent()
                .setComponent(LAUNCHING_ACTIVITY)
                .addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK);
        final IBinder errorCallbackToken = new Binder();

        WindowContainerTransaction wct = new WindowContainerTransaction()
                .setErrorCallbackToken(errorCallbackToken)
                .createTaskFragment(params)
                .startActivityInTaskFragment(taskFragToken, ownerToken, intent,
                        null /* activityOptions */);

        mTaskFragmentOrganizer.applyTransaction(wct);

        mTaskFragmentOrganizer.waitForTaskFragmentCreated();

        TaskFragmentInfo info = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken);

        // TaskFragment must remain empty because we don't hold EMBEDDING_ACTIVITY permission to
        // launch Activity in the embedded Task.
        assertEmptyTaskFragment(info, taskFragToken);

        mTaskFragmentOrganizer.waitForTaskFragmentError();

        assertThat(mTaskFragmentOrganizer.getThrowable()).isInstanceOf(SecurityException.class);
        assertThat(mTaskFragmentOrganizer.getErrorCallbackToken()).isEqualTo(errorCallbackToken);

        final WindowContainerTransaction wctWithPermission = new WindowContainerTransaction()
                .startActivityInTaskFragment(taskFragToken, ownerToken, intent,
                        null /* activityOptions */);

        NestedShellPermission.run(() -> mTaskFragmentOrganizer.applyTransaction(wctWithPermission),
                "android.permission.ACTIVITY_EMBEDDING");

        mTaskFragmentOrganizer.waitForTaskFragmentInfoChanged();

        info = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken);

        // The new started Activity must be launched in the new created Task under the TaskFragment
        // with token taskFragToken.
        assertNotEmptyTaskFragment(info, taskFragToken);
    }

    /**
     * Verifies the behavior of starting an Activity of another app in TaskFragment is not
     * allowed without permissions.
     */
    @Test
    public void testStartAnotherAppActivityInTaskFragment() {
        final Activity activity = startNewActivity();
        final IBinder ownerToken = getActivityToken(activity);
        final TaskFragmentCreationParams params =
                mTaskFragmentOrganizer.generateTaskFragParams(ownerToken);
        final IBinder taskFragToken = params.getFragmentToken();
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                .createTaskFragment(params)
                .startActivityInTaskFragment(taskFragToken, ownerToken,
                        new Intent().setComponent(SDK_30_TEST_ACTIVITY),
                        null /* activityOptions */);
        mTaskFragmentOrganizer.applyTransaction(wct);
        mTaskFragmentOrganizer.waitForTaskFragmentCreated();

        // Launching an activity of another app in TaskFragment should report error.
        mTaskFragmentOrganizer.waitForTaskFragmentError();
        assertThat(mTaskFragmentOrganizer.getThrowable()).isInstanceOf(SecurityException.class);

        // Making sure no activity launched
        TaskFragmentInfo info = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken);
        assertEmptyTaskFragment(info, taskFragToken);
    }

    /**
     * Verifies the behavior of starting an Activity of another app while activities of the host
     * app are already embedded in TaskFragment.
     */
    @Test
    public void testStartAnotherAppActivityWithEmbeddedTaskFragments() {
        final Activity activity = startNewActivity();
        final IBinder ownerToken = getActivityToken(activity);
        final TaskFragmentCreationParams params =
                mTaskFragmentOrganizer.generateTaskFragParams(ownerToken);
        final IBinder taskFragToken = params.getFragmentToken();
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                .createTaskFragment(params)
                .startActivityInTaskFragment(taskFragToken, ownerToken,
                        new Intent(getInstrumentation().getTargetContext(),
                                WindowMetricsActivityTests.MetricsActivity.class),
                        null /* activityOptions */);
        mTaskFragmentOrganizer.applyTransaction(wct);
        mTaskFragmentOrganizer.waitForTaskFragmentCreated();
        mTaskFragmentOrganizer.waitForAndGetTaskFragmentInfo(
                taskFragToken, info -> info.getActivities().size() == 1,
                "getActivities from TaskFragment must contain 1 activities");

        activity.startActivity(new Intent().setComponent(SDK_30_TEST_ACTIVITY));

        waitAndAssertActivityState(SDK_30_TEST_ACTIVITY, STATE_RESUMED,
                "Activity should be resumed.");
        TaskFragmentInfo info = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken);
        assertEquals(1, info.getActivities().size());
    }

    /**
     * Verifies whether creating TaskFragment with non-resizeable {@link Activity} leads to
     * {@link IllegalArgumentException} returned by
     * {@link TaskFragmentOrganizer#onTaskFragmentError(IBinder, Throwable)}.
     */
    @Test
    public void testCreateTaskFragmentWithNonResizeableActivity_ThrowException() {
        // Pass non-resizeable Activity's token to TaskFragmentCreationParams and tries to
        // create a TaskFragment with the params.
        final Activity activity =
                startNewActivity(CompatChangeTests.NonResizeablePortraitActivity.class);
        final IBinder ownerToken = getActivityToken(activity);
        final TaskFragmentCreationParams params =
                mTaskFragmentOrganizer.generateTaskFragParams(ownerToken);
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                .createTaskFragment(params);

        mTaskFragmentOrganizer.applyTransaction(wct);

        mTaskFragmentOrganizer.waitForTaskFragmentError();

        assertThat(mTaskFragmentOrganizer.getThrowable())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that the TaskFragment hierarchy ops should still work while in lock task mode.
     */
    @Test
    public void testApplyHierarchyOpsInLockTaskMode() {
        // Start an activity
        final Activity activity = startNewActivity();

        try {
            // Lock the task
            runWithShellPermission(() -> {
                mAtm.startSystemLockTaskMode(activity.getTaskId());
            });

            // Create TaskFragment and reparent the activity
            final IBinder ownerToken = getActivityToken(activity);
            final TaskFragmentCreationParams params =
                    mTaskFragmentOrganizer.generateTaskFragParams(ownerToken);
            final IBinder taskFragToken = params.getFragmentToken();
            WindowContainerTransaction wct = new WindowContainerTransaction()
                    .createTaskFragment(params)
                    .reparentActivityToTaskFragment(taskFragToken, ownerToken);
            mTaskFragmentOrganizer.applyTransaction(wct);

            // Verifies it works
            mTaskFragmentOrganizer.waitForTaskFragmentCreated();
            TaskFragmentInfo info = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken);
            assertEquals(1, info.getActivities().size());

            // Delete the TaskFragment
            wct = new WindowContainerTransaction().deleteTaskFragment(
                    mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken).getToken());
            mTaskFragmentOrganizer.applyTransaction(wct);

            // Verifies the TaskFragment NOT removed because the removal would also empty the task.
            mTaskFragmentOrganizer.waitForTaskFragmentError();
            assertThat(mTaskFragmentOrganizer.getThrowable()).isInstanceOf(
                    IllegalStateException.class);
            info = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragToken);
            assertEquals(1, info.getActivities().size());
        } finally {
            runWithShellPermission(() -> {
                mAtm.stopSystemLockTaskMode();
            });
        }
    }

    private static Activity startNewActivity() {
        return startNewActivity(TestActivity.class);
    }

    private static Activity startNewActivity(Class<?> className) {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Intent intent = new Intent(instrumentation.getTargetContext(), className)
                .addFlags(FLAG_ACTIVITY_NEW_TASK);
        return instrumentation.startActivitySync(intent);
    }
}
