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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.server.wm.SplitActivityLifecycleTest.ActivityB.EXTRA_SHOW_WHEN_LOCKED;
import static android.server.wm.WindowManagerState.STATE_STARTED;
import static android.server.wm.WindowManagerState.STATE_STOPPED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.server.wm.WindowManagerState.Task;
import android.server.wm.WindowManagerState.TaskFragment;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests that verify the behavior of split Activity.
 * <p>
 * At the beginning of test, two Activities are launched side-by-side in two adjacent TaskFragments.
 * Then another Activity will be launched with different scenarios. The purpose of this test is to
 * verify the CUJ of split Activity.
 * </p>
 *
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:SplitActivityLifecycleTest
 */
@Presubmit
public class SplitActivityLifecycleTest extends TaskFragmentOrganizerTestBase {
    private Activity mOwnerActivity;
    private IBinder mOwnerToken;
    private final Rect mPrimaryBounds = new Rect();
    private final Rect mSideBounds = new Rect();
    private TaskFragmentRecord mTaskFragA;
    private TaskFragmentRecord mTaskFragB;
    private final ComponentName mActivityA = new ComponentName(mContext, ActivityA.class);
    private final ComponentName mActivityB = new ComponentName(mContext, ActivityB.class);
    private final ComponentName mActivityC = new ComponentName(mContext, ActivityC.class);
    private final Intent mIntent = new Intent().setComponent(mActivityC);

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mOwnerActivity = startActivity(ActivityA.class);
        mOwnerToken = getActivityToken(mOwnerActivity);
    }

    /** Launch two Activities in two adjacent TaskFragments side-by-side. */
    private void initializeSplitActivities(boolean splitInEmbeddedTask) {
        initializeSplitActivities(splitInEmbeddedTask, false /* showWhenLocked */);
    }

    /**
     * Launch two Activities in two adjacent TaskFragments side-by-side and support to set the
     * showWhenLocked attribute to Activity B.
     */
    private void initializeSplitActivities(boolean splitInEmbeddedTask, boolean showWhenLocked) {
        final Rect activityBounds = mOwnerActivity.getWindowManager().getCurrentWindowMetrics()
                .getBounds();
        activityBounds.splitVertically(mPrimaryBounds, mSideBounds);

        final TaskFragmentCreationParams paramsA = generatePrimaryTaskFragParams();
        final TaskFragmentCreationParams paramsB = generateSideTaskFragParams();
        IBinder taskFragTokenA = paramsA.getFragmentToken();
        IBinder taskFragTokenB = paramsB.getFragmentToken();

        final WindowContainerTransaction wct = new WindowContainerTransaction()
                .createTaskFragment(paramsA)
                .reparentActivityToTaskFragment(taskFragTokenA, mOwnerToken)
                .createTaskFragment(paramsB)
                .setAdjacentTaskFragments(taskFragTokenA, taskFragTokenB, null /* params */);

        final Intent intent = new Intent().setComponent(mActivityB);
        if (splitInEmbeddedTask) {
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        if (showWhenLocked) {
            intent.putExtra(EXTRA_SHOW_WHEN_LOCKED, true);
        }
        wct.startActivityInTaskFragment(taskFragTokenB, mOwnerToken, intent,
                null /* activityOptions */);

        mTaskFragmentOrganizer.setAppearedCount(2);
        mTaskFragmentOrganizer.applyTransaction(wct);
        mTaskFragmentOrganizer.waitForTaskFragmentCreated();

        final TaskFragmentInfo infoA = mTaskFragmentOrganizer.getTaskFragmentInfo(
                taskFragTokenA);
        final TaskFragmentInfo infoB = mTaskFragmentOrganizer.getTaskFragmentInfo(
                taskFragTokenB);

        assertNotEmptyTaskFragment(infoA, taskFragTokenA, mOwnerToken);
        assertNotEmptyTaskFragment(infoB, taskFragTokenB);

        mTaskFragA = new TaskFragmentRecord(infoA);
        mTaskFragB = new TaskFragmentRecord(infoB);

        waitAndAssertResumedActivity(mActivityA, "Activity A must still be resumed.");
        waitAndAssertResumedActivity(mActivityB, "Activity B must still be resumed.");

        if (splitInEmbeddedTask) {
            TaskFragment taskFragmentB = mWmState.getTaskFragmentByActivity(mActivityB);
            Task embeddedTask = mWmState.getTaskByActivity(mActivityB);
            assertWindowHierarchy(taskFragmentB, embeddedTask, mWmState.getActivity(mActivityB));
        }

        mTaskFragmentOrganizer.resetLatch();
    }

    /**
     * Verifies the behavior to launch Activity in the same TaskFragment as the owner Activity.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, this test verifies
     * the behavior to launch Activity C in the same TaskFragment as Activity A:
     * <pre class="prettyprint">
     * |A|B| -> |C|B|
     * </pre></p>
     */
    @Test
    public void testActivityLaunchInSameSplitTaskFragment() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */);

        final IBinder taskFragTokenA = mTaskFragA.getTaskFragToken();
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                .startActivityInTaskFragment(taskFragTokenA, mOwnerToken, mIntent,
                        null /* activityOptions */);

        mTaskFragmentOrganizer.applyTransaction(wct);

        final TaskFragmentInfo infoA = mTaskFragmentOrganizer.waitForAndGetTaskFragmentInfo(
                taskFragTokenA, info -> info.getActivities().size() == 2,
                "getActivities from TaskFragment A must contain 2 activities");

        assertNotEmptyTaskFragment(infoA, taskFragTokenA, mOwnerToken);

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertActivityState(mActivityA, STATE_STOPPED,
                "Activity A is occluded by Activity C, so it must be stopped.");
        waitAndAssertResumedActivity(mActivityB, "Activity B must be resumed.");

        final TaskFragment taskFragmentA = mWmState.getTaskFragmentByActivity(mActivityA);
        assertWithMessage("TaskFragmentA must contain Activity A and C")
                .that(taskFragmentA.mActivities).containsExactly(mWmState.getActivity(mActivityA),
                mWmState.getActivity(mActivityC));
    }

    /**
     * Verifies the behavior to launch Activity in the adjacent TaskFragment.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, this test verifies
     * the behavior to launch Activity C in the same TaskFragment as Activity B:
     * <pre class="prettyprint">
     * |A|B| -> |A|C|
     * </pre></p>
     */
    @Test
    public void testActivityLaunchInAdjacentSplitTaskFragment() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */);

        final IBinder taskFragTokenB = mTaskFragB.getTaskFragToken();
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                .startActivityInTaskFragment(taskFragTokenB, mOwnerToken, mIntent,
                        null /* activityOptions */);

        mTaskFragmentOrganizer.applyTransaction(wct);

        final TaskFragmentInfo infoB = mTaskFragmentOrganizer.waitForAndGetTaskFragmentInfo(
                taskFragTokenB, info -> info.getActivities().size() == 2,
                "getActivities from TaskFragment A must contain 2 activities");

        assertNotEmptyTaskFragment(infoB, taskFragTokenB);

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertResumedActivity(mActivityA, "Activity A must be resumed.");
        waitAndAssertActivityState(mActivityB, STATE_STOPPED,
                "Activity B is occluded by Activity C, so it must be stopped.");

        final TaskFragment taskFragmentB = mWmState.getTaskFragmentByActivity(mActivityB);
        assertWithMessage("TaskFragmentB must contain Activity B and C")
                .that(taskFragmentB.mActivities).containsExactly(mWmState.getActivity(mActivityB),
                mWmState.getActivity(mActivityC));
    }

    /**
     * Verifies the behavior that the Activity instance in bottom TaskFragment calls
     * {@link Context#startActivity(Intent)} to launch another Activity.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, Activity A calls
     * {@link Context#startActivity(Intent)} to launch Activity C. The expected behavior is that
     * Activity C will be launch on top of Activity B as below:
     * <pre class="prettyprint">
     * |A|B| -> |A|C|
     * </pre>
     * The reason is that TaskFragment B has higher z-order than TaskFragment A because we create
     * TaskFragment B later than TaskFragment A.
     * </p>
     */
    @Test
    public void testActivityLaunchFromBottomTaskFragment() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */);

        mOwnerActivity.startActivity(mIntent);

        final IBinder taskFragTokenB = mTaskFragB.getTaskFragToken();
        final TaskFragmentInfo infoB = mTaskFragmentOrganizer.waitForAndGetTaskFragmentInfo(
                taskFragTokenB, info -> info.getActivities().size() == 2,
                "getActivities from TaskFragment A must contain 2 activities");

        assertNotEmptyTaskFragment(infoB, taskFragTokenB);

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertResumedActivity(mActivityA, "Activity A must be resumed.");
        waitAndAssertActivityState(mActivityB, STATE_STOPPED,
                "Activity B is occluded by Activity C, so it must be stopped.");

        final TaskFragment taskFragmentB = mWmState.getTaskFragmentByActivity(mActivityB);
        assertWithMessage("TaskFragmentB must contain Activity B and C")
                .that(taskFragmentB.mActivities).containsExactly(mWmState.getActivity(mActivityB),
                mWmState.getActivity(mActivityC));
    }

    /**
     * Verifies the behavior of the activities in a TaskFragment that is sandwiched in adjacent
     * TaskFragments.
     */
    @Test
    public void testSandwichTaskFragmentInAdjacent() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */);

        final IBinder taskFragTokenA = mTaskFragA.getTaskFragToken();
        final TaskFragmentCreationParams paramsC = generateSideTaskFragParams();
        final IBinder taskFragTokenC = paramsC.getFragmentToken();
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                // Create the side TaskFragment for C and launch
                .createTaskFragment(paramsC)
                .startActivityInTaskFragment(taskFragTokenC, mOwnerToken, mIntent,
                        null /* activityOptions */)
                .setAdjacentTaskFragments(taskFragTokenA, taskFragTokenC, null /* options */);

        mTaskFragmentOrganizer.applyTransaction(wct);
        // Wait for the TaskFragment of Activity C to be created.
        mTaskFragmentOrganizer.waitForTaskFragmentCreated();

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertActivityState(mActivityB, STATE_STOPPED,
                "Activity B is occluded by Activity C, so it must be stopped.");
        waitAndAssertResumedActivity(mActivityA, "Activity B must be resumed.");
    }

    /**
     * Verifies the behavior to launch adjacent Activity to the adjacent TaskFragment.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, this test verifies
     * the behavior to launch the Activity C to the adjacent TaskFragment of the secondary
     * TaskFragment, which Activity B is attached to. Then the secondary TaskFragment is shifted to
     * occlude the primary TaskFragment, which Activity A is attached to, and the adjacent
     * TaskFragment, which Activity C is attached to, is occupied the region where the secondary
     * TaskFragment is located. This test is to verify the "shopping mode" scenario.
     * <pre class="prettyprint">
     * |A|B| -> |B|C|
     * </pre></p>
     */
    @Test
    public void testAdjacentActivityLaunchFromSecondarySplitTaskFragment() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */);

        final IBinder taskFragTokenB = mTaskFragB.getTaskFragToken();
        final TaskFragmentCreationParams paramsC = generateSideTaskFragParams();
        final IBinder taskFragTokenC = paramsC.getFragmentToken();
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                // Move TaskFragment B to the primaryBounds
                .setBounds(mTaskFragB.getToken(), mPrimaryBounds)
                // Create the side TaskFragment for C and launch
                .createTaskFragment(paramsC)
                .startActivityInTaskFragment(taskFragTokenC, mOwnerToken, mIntent,
                        null /* activityOptions */)
                .setAdjacentTaskFragments(taskFragTokenB, taskFragTokenC, null /* options */);

        mTaskFragmentOrganizer.applyTransaction(wct);
        // Wait for the TaskFragment of Activity C to be created.
        mTaskFragmentOrganizer.waitForTaskFragmentCreated();
        // Wait for the TaskFragment of Activity B to be changed.
        mTaskFragmentOrganizer.waitForTaskFragmentInfoChanged();

        final TaskFragmentInfo infoB = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragTokenB);
        final TaskFragmentInfo infoC = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragTokenC);

        assertNotEmptyTaskFragment(infoB, taskFragTokenB);
        assertNotEmptyTaskFragment(infoC, taskFragTokenC);

        mTaskFragB = new TaskFragmentRecord(infoB);
        final TaskFragmentRecord taskFragC = new TaskFragmentRecord(infoC);

        assertThat(mTaskFragB.getBounds()).isEqualTo(mPrimaryBounds);
        assertThat(taskFragC.getBounds()).isEqualTo(mSideBounds);

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertActivityState(mActivityA, STATE_STOPPED,
                "Activity A is occluded by Activity C, so it must be stopped.");
        waitAndAssertResumedActivity(mActivityB, "Activity B must be resumed.");
    }

    /**
     * Verifies the behavior to launch Activity in expanded TaskFragment.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, this test verifies
     * the behavior to launch Activity C in the TaskFragment which fills the Task bounds of owner
     * Activity:
     * <pre class="prettyprint">
     * |A|B| -> |C|
     * </pre></p>
     */
    @Test
    public void testActivityLaunchInExpandedTaskFragment() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */);

        testActivityLaunchInExpandedTaskFragmentInternal();
    }

    /**
     * Verifies the behavior to launch Activity in expanded TaskFragment and occludes the embedded
     * Task.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, which Activity B is in
     * embedded Task, this test verifies the behavior to launch Activity C in the TaskFragment which
     * fills the Task bounds of owner Activity:
     * <pre class="prettyprint">
     *     - Fullscreen -
     *     TaskFragmentC
     *       - ActivityC <---- new started Activity
     * - Left -      - Right -
     * TaskFragmentA TaskFragmentB
     *   - ActivityA   - Embedded Task
     *                   - ActivityB
     * </pre></p>
     */
    @Test
    @Ignore("b/197364677")
    public void testActivityLaunchInExpandedTaskFragment_AboveEmbeddedTask() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(true /* verifyEmbeddedTask */);

        testActivityLaunchInExpandedTaskFragmentInternal();
    }

    private void testActivityLaunchInExpandedTaskFragmentInternal() {

        final TaskFragmentCreationParams fullScreenParamsC = mTaskFragmentOrganizer
                .generateTaskFragParams(mOwnerToken);
        final IBinder taskFragTokenC = fullScreenParamsC.getFragmentToken();
        final WindowContainerTransaction wct = new WindowContainerTransaction()
                .createTaskFragment(fullScreenParamsC)
                .startActivityInTaskFragment(taskFragTokenC, mOwnerToken, mIntent,
                        null /* activityOptions */);

        mTaskFragmentOrganizer.applyTransaction(wct);

        mTaskFragmentOrganizer.waitForTaskFragmentCreated();

        assertNotEmptyTaskFragment(mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragTokenC),
                taskFragTokenC);

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertActivityState(mActivityA, STATE_STOPPED,
                "Activity A is occluded by Activity C, so it must be stopped.");
        waitAndAssertActivityState(mActivityB, STATE_STOPPED,
                "Activity B is occluded by Activity C, so it must be stopped.");
    }

    /**
     * Verifies the behavior to launch Activity above the embedded Task in TaskFragment.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, which Activity B is in
     * embedded Task, this test verifies the behavior to launch Activity C on top of the embedded
     * Task in the same TaskFragment as Activity B:
     * <pre class="prettyprint">
     * - Left -      - Right -
     * TaskFragmentA TaskFragmentB
     *   - ActivityA   - ActivityC <---- new started Activity
     *                 - Embedded Task
     *                   - ActivityB
     * </pre></p>
     */
    @Test
    @Ignore("b/197364677")
    public void testActivityLaunchAboveEmbeddedTaskInTaskFragment() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(true /* verifyEmbeddedTask */);

        final IBinder taskFragTokenB = mTaskFragB.getTaskFragToken();

        WindowContainerTransaction wct = new WindowContainerTransaction()
                .startActivityInTaskFragment(taskFragTokenB, mOwnerToken, mIntent,
                        null /* activityOptions */);

        mTaskFragmentOrganizer.applyTransaction(wct);

        mTaskFragmentOrganizer.waitForTaskFragmentInfoChanged();

        final TaskFragmentInfo infoB = mTaskFragmentOrganizer.getTaskFragmentInfo(taskFragTokenB);

        assertNotEmptyTaskFragment(infoB, taskFragTokenB);

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertResumedActivity(mActivityA, "Activity A must be resumed.");
        waitAndAssertActivityState(mActivityB, WindowManagerState.STATE_STOPPED,
                "Activity B is occluded by Activity C, so it must be stopped.");

        final TaskFragment taskFragmentB = mWmState.getTaskFragmentByActivity(mActivityB);
        assertWithMessage("TaskFragmentB must contain Activity C")
                .that(taskFragmentB.mActivities).containsExactly(mWmState.getActivity(mActivityC));
    }

    /**
     * Verifies the behavior to launch Activity to the embedded Task in TaskFragment.
     * <p>
     * For example, given that Activity A and B are showed side-by-side, which Activity B is in
     * embedded Task, this test verifies the behavior to launch Activity C to the embedded Task
     * and on top of Activity B:
     * <pre class="prettyprint">
     * - Left -      - Right -
     * TaskFragmentA TaskFragmentB
     *   - ActivityA   - Embedded Task
     *                   - ActivityC <---- new started Activity
     *                   - ActivityB
     * </pre></p>
     */
    @Test
    @Ignore("b/197364677")
    public void testActivityLaunchToEmbeddedTaskInTaskFragment() {
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(true /* verifyEmbeddedTask */);

        final IBinder taskFragTokenB = mTaskFragB.getTaskFragToken();
        // Make Activity C launch to the embedded Task.
        final Intent intent = new Intent(mIntent).addFlags(FLAG_ACTIVITY_NEW_TASK);

        WindowContainerTransaction wct = new WindowContainerTransaction()
                .startActivityInTaskFragment(taskFragTokenB, mOwnerToken, intent,
                        null /* activityOptions */);

        mTaskFragmentOrganizer.applyTransaction(wct);

        waitAndAssertResumedActivity(mActivityC, "Activity C must be resumed.");
        waitAndAssertResumedActivity(mActivityA, "Activity A must be resumed.");
        waitAndAssertActivityState(mActivityB, STATE_STOPPED,
                "Activity B is occluded by Activity C, so it must be stopped.");

        final Task embeddedTask = mWmState.getTaskByActivity(mActivityB);
        assertWithMessage("Embedded Task must contain Activity B and Activity C")
                .that(embeddedTask.mActivities).containsExactly(mWmState.getActivity(mActivityB),
                mWmState.getActivity(mActivityC));
    }

    /**
     * Verifies the show-when-locked behavior while launch embedded activities. Don't show the
     * embedded activities even if one of Activity has showWhenLocked flag.
     */
    @Test
    public void testLaunchEmbeddedActivityWithShowWhenLocked() {
        assumeTrue(supportsLockScreen());

        final LockScreenSession lockScreenSession = createManagedLockScreenSession();
        // Initialize test environment by launching Activity A and B (with showWhenLocked)
        // side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */, true /* showWhenLocked */);

        lockScreenSession.sleepDevice();
        lockScreenSession.wakeUpDevice();

        waitAndAssertActivityState(mActivityA, STATE_STOPPED,"Activity A must be stopped");
        waitAndAssertActivityState(mActivityB, STATE_STOPPED,"Activity B must be stopped");
    }

    /**
     * Verifies the show-when-locked behavior while launch embedded activities. Don't show the
     * embedded activities if the activities don't have showWhenLocked flag.
     */
    @Test
    public void testLaunchEmbeddedActivitiesWithoutShowWhenLocked() {
        assumeTrue(supportsLockScreen());

        final LockScreenSession lockScreenSession = createManagedLockScreenSession();
        // Initialize test environment by launching Activity A and B side-by-side.
        initializeSplitActivities(false /* verifyEmbeddedTask */, false /* showWhenLocked */);

        lockScreenSession.sleepDevice();
        lockScreenSession.wakeUpDevice();

        waitAndAssertActivityState(mActivityA, STATE_STOPPED,"Activity A must be stopped");
        waitAndAssertActivityState(mActivityB, STATE_STOPPED,"Activity B must be stopped");
    }

    /**
     * Verifies the show-when-locked behavior while launch embedded activities. The embedded
     * activities should be shown on top of the lock screen since they have the showWhenLocked flag.
     * Don't show the embedded activities even if one of Activity has showWhenLocked flag.
     */
    @Test
    public void testLaunchEmbeddedActivitiesWithShowWhenLocked() {
        assumeTrue(supportsLockScreen());

        final LockScreenSession lockScreenSession = createManagedLockScreenSession();
        // Initialize test environment by launching Activity A and B side-by-side.
        mOwnerActivity.setShowWhenLocked(true);
        initializeSplitActivities(false /* verifyEmbeddedTask */, true /* showWhenLocked */);

        lockScreenSession.sleepDevice();
        lockScreenSession.wakeUpDevice();

        waitAndAssertResumedActivity(mActivityA, "Activity A must be resumed.");
        waitAndAssertResumedActivity(mActivityB, "Activity B must be resumed.");

        // Launch Activity C without show-when-lock and verifies that both activities are stopped.
        mOwnerActivity.startActivity(mIntent);
        waitAndAssertActivityState(mActivityA, STATE_STOPPED, "Activity A must be stopped");
        waitAndAssertActivityState(mActivityC, STATE_STOPPED, "Activity C must be stopped");
    }

    /**
     * Verifies an Activity below adjacent translucent TaskFragments is visible.
     */
    @Test
    public void testTranslucentAdjacentTaskFragment() {
        // Create ActivityB on top of ActivityA
        Activity activityB = startActivity(ActivityB.class);
        waitAndAssertResumedActivity(mActivityB, "Activity B must be resumed.");
        waitAndAssertActivityState(mActivityA, STATE_STOPPED,
                "Activity A is occluded by Activity B, so it must be stopped.");

        // Create two adjacent TaskFragments, making ActivityB and TranslucentActivity
        // displayed side-by-side (ActivityB|TranslucentActivity).
        mOwnerActivity.getWindowManager().getCurrentWindowMetrics().getBounds()
                .splitVertically(mPrimaryBounds, mSideBounds);
        final TaskFragmentCreationParams primaryParams = generatePrimaryTaskFragParams();
        final TaskFragmentCreationParams secondaryParams = generateSideTaskFragParams();
        IBinder primaryToken = primaryParams.getFragmentToken();
        IBinder secondaryToken = secondaryParams.getFragmentToken();

        final ComponentName translucentActivity = new ComponentName(mContext,
                TranslucentActivity.class);
        final Intent intent = new Intent().setComponent(translucentActivity);
        WindowContainerTransaction wct = new WindowContainerTransaction()
                .createTaskFragment(primaryParams)
                .reparentActivityToTaskFragment(primaryToken, getActivityToken(activityB))
                .createTaskFragment(secondaryParams)
                .setAdjacentTaskFragments(primaryToken, secondaryToken, null /* params */)
                .startActivityInTaskFragment(secondaryToken, mOwnerToken, intent,
                        null /* activityOptions */);
        mTaskFragmentOrganizer.applyTransaction(wct);

        waitAndAssertResumedActivity(translucentActivity, "TranslucentActivity must be resumed.");
        waitAndAssertResumedActivity(mActivityB, "Activity B must be resumed.");
        waitAndAssertActivityState(mActivityA, STATE_STARTED,
                "Activity A is not fully occluded and must be visible and started");
    }

    private TaskFragmentCreationParams generatePrimaryTaskFragParams() {
        return mTaskFragmentOrganizer.generateTaskFragParams(mOwnerToken, mPrimaryBounds,
                WINDOWING_MODE_MULTI_WINDOW);
    }

    private TaskFragmentCreationParams generateSideTaskFragParams() {
        return mTaskFragmentOrganizer.generateTaskFragParams(mOwnerToken, mSideBounds,
                WINDOWING_MODE_MULTI_WINDOW);
    }

    private static class TaskFragmentRecord {
        private final IBinder mTaskFragToken;
        private final Rect mBounds = new Rect();
        private final WindowContainerToken mContainerToken;

        private TaskFragmentRecord(TaskFragmentInfo info) {
            mTaskFragToken = info.getFragmentToken();
            mBounds.set(info.getConfiguration().windowConfiguration.getBounds());
            mContainerToken = info.getToken();
        }

        private IBinder getTaskFragToken() {
            return mTaskFragToken;
        }

        private Rect getBounds() {
            return mBounds;
        }

        private WindowContainerToken getToken() {
            return mContainerToken;
        }
    }

    public static class ActivityA extends SplitTestActivity {}
    public static class ActivityB extends SplitTestActivity {}
    public static class ActivityC extends SplitTestActivity {}
    public static class TranslucentActivity extends SplitTestActivity {}
    public static class SplitTestActivity extends FocusableActivity {
        public static final String EXTRA_SHOW_WHEN_LOCKED = "showWhenLocked";
        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            if (getIntent().getBooleanExtra(EXTRA_SHOW_WHEN_LOCKED, false)) {
                setShowWhenLocked(true);
            }
        }
    }
}
