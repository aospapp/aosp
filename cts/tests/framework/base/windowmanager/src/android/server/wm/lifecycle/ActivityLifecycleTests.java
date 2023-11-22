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

import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.server.wm.ActivityManagerState.STATE_PAUSED;
import static android.server.wm.ActivityManagerState.STATE_STOPPED;
import static android.server.wm.UiDeviceUtils.pressBackButton;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_ACTIVITY_RESULT;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_CREATE;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_DESTROY;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_NEW_INTENT;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_PAUSE;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_POST_CREATE;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_RESTART;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_RESUME;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_START;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_STOP;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_TOP_POSITION_GAINED;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_TOP_POSITION_LOST;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.PRE_ON_CREATE;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;

import com.android.compatibility.common.util.AmUtils;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:ActivityLifecycleTests
 */
@FlakyTest(bugId = 77652261)
@MediumTest
@Presubmit
public class ActivityLifecycleTests extends ActivityLifecycleClientTestBase {

    @Test
    public void testSingleLaunch() throws Exception {
        final Activity activity = mFirstActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(activity, ON_RESUME));

        LifecycleVerifier.assertLaunchSequence(FirstActivity.class, getLifecycleLog());
    }

    @Test
    public void testLaunchOnTop() throws Exception {
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));

        getLifecycleLog().clear();
        final Activity secondActivity = mSecondActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(occludedActivityState(firstActivity, secondActivity),
                state(secondActivity, ON_RESUME));

        LifecycleVerifier.assertLaunchSequence(SecondActivity.class, FirstActivity.class,
                getLifecycleLog(), isTranslucent(secondActivity));
    }

    @Test
    public void testLaunchTranslucentOnTop() throws Exception {
        // Launch fullscreen activity
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));

        // Launch translucent activity on top
        getLifecycleLog().clear();
        final Activity translucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(firstActivity, ON_PAUSE),
                state(translucentActivity, ON_RESUME));

        LifecycleVerifier.assertLaunchSequence(TranslucentActivity.class, FirstActivity.class,
                getLifecycleLog(), true /* launchIsTranslucent */);
    }

    @Test
    public void testLaunchDoubleTranslucentOnTop() throws Exception {
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));

        // Launch translucent activity on top
        getLifecycleLog().clear();
        final Activity translucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(firstActivity, ON_PAUSE),
                state(translucentActivity, ON_RESUME));

        LifecycleVerifier.assertLaunchSequence(TranslucentActivity.class, FirstActivity.class,
                getLifecycleLog(), true /* launchIsTranslucent */);

        // Launch another translucent activity on top
        getLifecycleLog().clear();
        final Activity secondTranslucentActivity =
                mSecondTranslucentActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(translucentActivity, ON_PAUSE),
                state(secondTranslucentActivity, ON_RESUME));
        LifecycleVerifier.assertSequence(TranslucentActivity.class, getLifecycleLog(),
                Arrays.asList(ON_PAUSE), "launch");
        LifecycleVerifier.assertEmptySequence(FirstActivity.class, getLifecycleLog(), "launch");

        // Finish top translucent activity
        getLifecycleLog().clear();
        secondTranslucentActivity.finish();

        waitAndAssertActivityStates(state(translucentActivity, ON_RESUME));
        waitAndAssertActivityStates(state(secondTranslucentActivity, ON_DESTROY));
        LifecycleVerifier.assertResumeToDestroySequence(SecondTranslucentActivity.class,
                getLifecycleLog());
        LifecycleVerifier.assertSequence(TranslucentActivity.class, getLifecycleLog(),
                Arrays.asList(ON_RESUME), "launch");
        LifecycleVerifier.assertEmptySequence(FirstActivity.class, getLifecycleLog(), "launch");
    }

    @Test
    public void testTranslucentMovedIntoStack() throws Exception {
        // Launch a translucent activity and a regular activity in separate stacks
        final Activity translucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(
                new Intent().setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK));
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME),
                state(translucentActivity, ON_STOP));

        final ComponentName firstActivityName = getComponentName(FirstActivity.class);
        mAmWmState.computeState(firstActivityName);
        int firstActivityStack = mAmWmState.getAmState().getStackIdByActivity(firstActivityName);

        // Move translucent activity into the stack with the first activity
        getLifecycleLog().clear();
        moveActivityToStack(getComponentName(TranslucentActivity.class), firstActivityStack);

        // Wait for translucent activity to resume and first activity to pause
        waitAndAssertActivityStates(state(translucentActivity, ON_RESUME),
                state(firstActivity, ON_PAUSE));
        LifecycleVerifier.assertSequence(FirstActivity.class, getLifecycleLog(),
                Arrays.asList(ON_PAUSE), "launchOnTop");
        LifecycleVerifier.assertRestartAndResumeSequence(TranslucentActivity.class,
                getLifecycleLog());
    }

    @Test
    public void testDestroyTopTranslucent() throws Exception {
        // Launch a regular activity and a a translucent activity in the same stack
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());
        final Activity translucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(firstActivity, ON_PAUSE),
                state(translucentActivity, ON_RESUME));

        // Finish translucent activity
        getLifecycleLog().clear();
        mTranslucentActivityTestRule.finishActivity();

        waitAndAssertActivityStates(state(firstActivity, ON_RESUME),
                state(translucentActivity, ON_DESTROY));

        // Verify destruction lifecycle
        LifecycleVerifier.assertResumeToDestroySequence(TranslucentActivity.class,
                getLifecycleLog());
        LifecycleVerifier.assertSequence(FirstActivity.class, getLifecycleLog(),
                Arrays.asList(ON_RESUME), "resumeAfterTopDestroyed");
    }

    @Test
    public void testDestroyOnTopOfTranslucent() throws Exception {
        // Launch fullscreen activity
        final Activity firstActivity =
                mFirstActivityTestRule.launchActivity(new Intent());

        // Launch translucent activity
        final Activity translucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());

        // Launch another fullscreen activity
        final Activity secondActivity =
                mSecondActivityTestRule.launchActivity(new Intent());

        // Wait for top activity to resume
        waitAndAssertActivityStates(state(secondActivity, ON_RESUME),
                occludedActivityState(translucentActivity, secondActivity),
                occludedActivityState(firstActivity, secondActivity));

        getLifecycleLog().clear();

        final boolean secondActivityIsTranslucent = ActivityInfo.isTranslucentOrFloating(
                secondActivity.getWindow().getWindowStyle());

        // Finish top activity
        mSecondActivityTestRule.finishActivity();

        waitAndAssertActivityStates(state(secondActivity, ON_DESTROY));
        LifecycleVerifier.assertResumeToDestroySequence(SecondActivity.class, getLifecycleLog());
        if (secondActivityIsTranslucent) {
            // In this case we don't expect the state of the firstActivity to change since it is
            // already in the visible paused state. So, we just verify that translucentActivity
            // transitions to resumed state.
            waitAndAssertActivityStates(state(translucentActivity, ON_RESUME));
        } else {
            // Wait for translucent activity to resume
            waitAndAssertActivityStates(state(translucentActivity, ON_RESUME),
                    state(firstActivity, ON_START));

            // Verify that the first activity was restarted
            LifecycleVerifier.assertRestartSequence(FirstActivity.class, getLifecycleLog());
        }
    }

    @Test
    public void testDestroyDoubleTranslucentOnTop() throws Exception {
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());
        final Activity translucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());
        final Activity secondTranslucentActivity =
                mSecondTranslucentActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(firstActivity, ON_PAUSE),
                state(translucentActivity, ON_PAUSE), state(secondTranslucentActivity, ON_RESUME));

        // Finish top translucent activity
        getLifecycleLog().clear();
        secondTranslucentActivity.finish();

        waitAndAssertActivityStates(state(translucentActivity, ON_RESUME));
        waitAndAssertActivityStates(state(secondTranslucentActivity, ON_DESTROY));
        LifecycleVerifier.assertResumeToDestroySequence(SecondTranslucentActivity.class,
                getLifecycleLog());
        LifecycleVerifier.assertSequence(TranslucentActivity.class, getLifecycleLog(),
                Arrays.asList(ON_RESUME), "destroy");
        LifecycleVerifier.assertEmptySequence(FirstActivity.class, getLifecycleLog(), "destroy");

        // Finish first translucent activity
        getLifecycleLog().clear();
        translucentActivity.finish();

        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));
        waitAndAssertActivityStates(state(translucentActivity, ON_DESTROY));
        LifecycleVerifier.assertResumeToDestroySequence(TranslucentActivity.class,
                getLifecycleLog());
        LifecycleVerifier.assertSequence(FirstActivity.class, getLifecycleLog(),
                Arrays.asList(ON_RESUME), "secondDestroy");
    }

    @Test
    public void testLaunchAndDestroy() throws Exception {
        final Activity activity = mFirstActivityTestRule.launchActivity(new Intent());

        activity.finish();
        waitAndAssertActivityStates(state(activity, ON_DESTROY));

        LifecycleVerifier.assertLaunchAndDestroySequence(FirstActivity.class, getLifecycleLog());
    }

    @Test
    public void testRelaunchResumed() throws Exception {
        final Activity activity = mFirstActivityTestRule.launchActivity(new Intent());
        waitAndAssertActivityStates(state(activity, ON_RESUME));

        getLifecycleLog().clear();
        getInstrumentation().runOnMainSync(activity::recreate);
        waitAndAssertActivityStates(state(activity, ON_RESUME));

        LifecycleVerifier.assertRelaunchSequence(FirstActivity.class, getLifecycleLog(), ON_RESUME);
    }

    @Test
    public void testRelaunchPaused() throws Exception {
        final Activity pausedActivity = mFirstActivityTestRule.launchActivity(new Intent());
        final Activity topTranslucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());

        waitAndAssertActivityStates(state(pausedActivity, ON_PAUSE),
                state(topTranslucentActivity, ON_RESUME));

        getLifecycleLog().clear();
        getInstrumentation().runOnMainSync(pausedActivity::recreate);
        waitAndAssertActivityStates(state(pausedActivity, ON_PAUSE));

        LifecycleVerifier.assertRelaunchSequence(FirstActivity.class, getLifecycleLog(), ON_PAUSE);
    }

    @Test
    public void testRelaunchStopped() throws Exception {
        final Activity stoppedActivity = mFirstActivityTestRule.launchActivity(new Intent());
        final Activity topActivity = mSecondActivityTestRule.launchActivity(new Intent());

        waitAndAssertActivityStates(
                occludedActivityState(stoppedActivity, topActivity), state(topActivity, ON_RESUME));

        getLifecycleLog().clear();
        getInstrumentation().runOnMainSync(stoppedActivity::recreate);
        waitAndAssertActivityStates(occludedActivityState(stoppedActivity, topActivity));

        LifecycleVerifier.assertRelaunchSequence(FirstActivity.class, getLifecycleLog(),
                occludedActivityState(isTranslucent(topActivity)));
    }

    @Test
    public void testRelaunchConfigurationChangedWhileBecomingVisible() throws Exception {
        if (!supportsRotation()) {
            // Skip rotation test if device doesn't support it.
            return;
        }

        final Activity becomingVisibleActivity =
                mFirstActivityTestRule.launchActivity(new Intent());
        final Activity translucentActivity =
                mTranslucentActivityTestRule.launchActivity(new Intent());
        final Activity topOpaqueActivity = mSecondActivityTestRule.launchActivity(new Intent());

        waitAndAssertActivityStates(
                occludedActivityState(becomingVisibleActivity, topOpaqueActivity),
                occludedActivityState(translucentActivity, topOpaqueActivity),
                state(topOpaqueActivity, ON_RESUME));

        try (final RotationSession rotationSession = new RotationSession()) {
            if (!supportsLockedUserRotation(
                    rotationSession, translucentActivity.getDisplay().getDisplayId())) {
                return;
            }

            getLifecycleLog().clear();

            final int current = rotationSession.get();
            // Set new rotation to cause a configuration change.
            switch (current) {
                case ROTATION_0:
                case ROTATION_180:
                    rotationSession.set(ROTATION_90);
                    break;
                case ROTATION_90:
                case ROTATION_270:
                    rotationSession.set(ROTATION_0);
                    break;
                default:
                    fail("Unknown rotation:" + current);
            }

            // Assert that the top activity was relaunched.
            waitAndAssertActivityStates(state(topOpaqueActivity, ON_RESUME));
            LifecycleVerifier.assertRelaunchSequence(
                    SecondActivity.class, getLifecycleLog(), ON_RESUME);

            // Finish the top activity
            getLifecycleLog().clear();
            mSecondActivityTestRule.finishActivity();

            // Assert that the translucent activity and the activity visible behind it were
            // relaunched.
            waitAndAssertActivityStates(state(becomingVisibleActivity, ON_PAUSE),
                    state(translucentActivity, ON_RESUME));

            LifecycleVerifier.assertSequence(FirstActivity.class, getLifecycleLog(),
                    Arrays.asList(ON_DESTROY, PRE_ON_CREATE, ON_CREATE, ON_START, ON_RESUME,
                            ON_PAUSE), "becomingVisiblePaused");
            final List<LifecycleLog.ActivityCallback> expectedSequence =
                    Arrays.asList(ON_DESTROY, PRE_ON_CREATE, ON_CREATE, ON_START, ON_RESUME);
            LifecycleVerifier.assertSequence(TranslucentActivity.class, getLifecycleLog(),
                    expectedSequence, "becomingVisibleResumed");
        }
    }

    @Test
    public void testOnActivityResult() throws Exception {
        final Intent intent = new Intent();
        intent.putExtra(EXTRA_FINISH_IN_ON_RESUME, true);
        mLaunchForResultActivityTestRule.launchActivity(intent);

        final List<LifecycleLog.ActivityCallback> expectedSequence =
                Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                        ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST,
                        ON_PAUSE, ON_ACTIVITY_RESULT, ON_RESUME, ON_TOP_POSITION_GAINED);
        waitForActivityTransitions(LaunchForResultActivity.class, expectedSequence);

        // TODO(b/79218023): First activity might also be stopped before getting result.
        final List<LifecycleLog.ActivityCallback> sequenceWithStop =
                Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                        ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST,
                        ON_PAUSE, ON_STOP, ON_ACTIVITY_RESULT, ON_RESTART, ON_START, ON_RESUME,
                        ON_TOP_POSITION_GAINED);
        final List<LifecycleLog.ActivityCallback> thirdSequence =
                Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                        ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST,
                        ON_PAUSE, ON_STOP, ON_ACTIVITY_RESULT, ON_RESTART, ON_START, ON_RESUME,
                        ON_TOP_POSITION_GAINED);
        final List<LifecycleLog.ActivityCallback> fourthSequence =
                Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                        ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST,
                        ON_PAUSE, ON_STOP, ON_RESTART, ON_START, ON_ACTIVITY_RESULT, ON_RESUME,
                        ON_TOP_POSITION_GAINED);
        LifecycleVerifier.assertSequenceMatchesOneOf(LaunchForResultActivity.class,
                getLifecycleLog(),
                Arrays.asList(expectedSequence, sequenceWithStop, thirdSequence, fourthSequence),
                "activityResult");
    }

    @Test
    public void testOnActivityResultAfterStop() throws Exception {
        final Intent intent = new Intent();
        intent.putExtra(EXTRA_FINISH_AFTER_RESUME, true);
        mLaunchForResultActivityTestRule.launchActivity(intent);
        final boolean isTranslucent = isTranslucent(mLaunchForResultActivityTestRule.getActivity());

        final List<List<LifecycleLog.ActivityCallback>> expectedSequences;
        if (isTranslucent) {
            expectedSequences = Arrays.asList(
                    Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                            ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST, ON_PAUSE,
                            ON_ACTIVITY_RESULT, ON_RESUME, ON_TOP_POSITION_GAINED)
            );
        } else {
            expectedSequences = Arrays.asList(
                    Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                            ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST,
                            ON_PAUSE, ON_STOP, ON_RESTART, ON_START, ON_ACTIVITY_RESULT, ON_RESUME,
                            ON_TOP_POSITION_GAINED),
                    Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                            ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST,
                            ON_PAUSE, ON_STOP, ON_ACTIVITY_RESULT, ON_RESTART, ON_START, ON_RESUME,
                            ON_TOP_POSITION_GAINED)
            );
        }
        waitForActivityTransitions(LaunchForResultActivity.class, expectedSequences.get(0));

        LifecycleVerifier.assertSequenceMatchesOneOf(LaunchForResultActivity.class,
                getLifecycleLog(), expectedSequences, "activityResult");
    }

    @Test
    public void testOnPostCreateAfterCreate() throws Exception {
        final Activity callbackTrackingActivity =
                mCallbackTrackingActivityTestRule.launchActivity(new Intent());

        waitAndAssertActivityStates(state(callbackTrackingActivity, ON_TOP_POSITION_GAINED));

        LifecycleVerifier.assertSequence(CallbackTrackingActivity.class, getLifecycleLog(),
                Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                        ON_TOP_POSITION_GAINED),"create");
    }

    @Test
    public void testOnPostCreateAfterRecreateInOnResume() throws Exception {
        // Launch activity
        final Activity trackingActivity =
                mCallbackTrackingActivityTestRule.launchActivity(new Intent());

        // Wait for activity to resume
        waitAndAssertActivityStates(state(trackingActivity, ON_TOP_POSITION_GAINED));

        // Call "recreate" and assert sequence
        getLifecycleLog().clear();
        getInstrumentation().runOnMainSync(trackingActivity::recreate);
        waitAndAssertActivityStates(state(trackingActivity, ON_TOP_POSITION_GAINED));

        LifecycleVerifier.assertSequence(CallbackTrackingActivity.class,
                getLifecycleLog(),
                Arrays.asList(ON_TOP_POSITION_LOST, ON_PAUSE, ON_STOP, ON_DESTROY, PRE_ON_CREATE,
                        ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME, ON_TOP_POSITION_GAINED),
                "recreate");
    }

    @Test
    public void testOnPostCreateAfterRecreateInOnPause() throws Exception {
        // Launch activity
        final Activity trackingActivity =
                mCallbackTrackingActivityTestRule.launchActivity(new Intent());

        // Wait for activity to resume
        waitAndAssertActivityStates(state(trackingActivity, ON_TOP_POSITION_GAINED));

        // Launch translucent activity, which will make the first one paused.
        mTranslucentActivityTestRule.launchActivity(new Intent());

        // Wait for first activity to become paused
        waitAndAssertActivityStates(state(trackingActivity, ON_PAUSE));

        // Call "recreate" and assert sequence
        getLifecycleLog().clear();
        getInstrumentation().runOnMainSync(trackingActivity::recreate);
        waitAndAssertActivityStates(state(trackingActivity, ON_PAUSE));

        LifecycleVerifier.assertSequence(CallbackTrackingActivity.class,
                getLifecycleLog(),
                Arrays.asList(ON_STOP, ON_DESTROY, PRE_ON_CREATE, ON_CREATE, ON_START,
                        ON_POST_CREATE, ON_RESUME, ON_PAUSE),
                "recreate");
    }

    @Test
    public void testOnPostCreateAfterRecreateInOnStop() throws Exception {
        // Launch first activity
        final Activity trackingActivity =
                mCallbackTrackingActivityTestRule.launchActivity(new Intent());

        // Wait for activity to resume
        waitAndAssertActivityStates(state(trackingActivity, ON_TOP_POSITION_GAINED));

        // Launch second activity to cover and stop first
        final Activity secondActivity =
                mSecondActivityTestRule.launchActivity(new Intent());

        // Wait for second activity to become resumed
        waitAndAssertActivityStates(state(secondActivity, ON_RESUME));

        // Wait for first activity to become stopped
        waitAndAssertActivityStates(occludedActivityState(trackingActivity, secondActivity));

        // Call "recreate" and assert sequence
        getLifecycleLog().clear();
        getInstrumentation().runOnMainSync(trackingActivity::recreate);
        waitAndAssertActivityStates(occludedActivityState(trackingActivity, secondActivity));

        final List<LifecycleLog.ActivityCallback> callbacks;
        if (isTranslucent(secondActivity)) {
            callbacks = Arrays.asList(ON_STOP, ON_DESTROY, PRE_ON_CREATE, ON_CREATE, ON_START,
                    ON_POST_CREATE, ON_RESUME, ON_PAUSE);
        } else {
            callbacks = Arrays.asList(ON_DESTROY, PRE_ON_CREATE, ON_CREATE, ON_START,
                    ON_POST_CREATE, ON_RESUME, ON_PAUSE, ON_STOP);
        }

        LifecycleVerifier.assertSequence(
                CallbackTrackingActivity.class, getLifecycleLog(), callbacks, "recreate");
    }

    /**
     * The following test ensures an activity is brought back if its process is ended in the
     * background.
     */
    @Test
    public void testRestoreFromKill() throws Exception {
        final LaunchActivityBuilder builder = getLaunchActivityBuilder();
        final ComponentName targetActivity = builder.getTargetActivity();

        // Launch activity whose process will be killed
        builder.execute();

        // Start activity in another process to put original activity in background.
        mFirstActivityTestRule.launchActivity(new Intent());
        final boolean isTranslucent = isTranslucent(mFirstActivityTestRule.getActivity());
        mAmWmState.waitForActivityState(
                targetActivity, isTranslucent ? STATE_PAUSED : STATE_STOPPED);

        // Only try to kill targetActivity if the top activity isn't translucent. If the top
        // activity is translucent then targetActivity will be visible, so the process will be
        // started again really quickly.
        if (!isTranslucent) {
            // Kill first activity
            AmUtils.runKill(targetActivity.getPackageName(), true /* wait */);
        }

        // Return back to first activity
        pressBackButton();

        // Verify activity is resumed
        mAmWmState.waitForValidState(targetActivity);
        mAmWmState.assertResumedActivity("Originally launched activity should be resumed",
                targetActivity);
    }

    /**
     * Tests that recreate request from an activity is executed immediately.
     */
    @Test
    public void testLocalRecreate() throws Exception {
        // Launch the activity that will recreate itself
        Activity recreatingActivity = mSingleTopActivityTestRule.launchActivity(new Intent());

        // Launch second activity to cover and stop first
        Activity secondActivity = mSecondActivityTestRule.launchActivity(
                new Intent().setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK));

        // Wait for first activity to become stopped
        final boolean secondActivityIsTranslucent = ActivityInfo.isTranslucentOrFloating(
                secondActivity.getWindow().getWindowStyle());
        waitAndAssertActivityStates(
                occludedActivityState(recreatingActivity, secondActivityIsTranslucent),
                state(secondActivity, ON_RESUME));

        // Launch the activity again to recreate
        getLifecycleLog().clear();
        final Intent intent = new Intent(mContext, SingleTopActivity.class);
        intent.putExtra(EXTRA_RECREATE, true);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        mTargetContext.startActivity(intent);

        // Wait for activity to relaunch and resume
        final List<LifecycleLog.ActivityCallback> expectedRelaunchSequence;
        if (secondActivityIsTranslucent) {
            expectedRelaunchSequence = Arrays.asList(ON_NEW_INTENT, ON_RESUME,
                    ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST,
                    ON_PAUSE, ON_STOP, ON_DESTROY, PRE_ON_CREATE, ON_CREATE, ON_START,
                    ON_POST_CREATE, ON_RESUME, ON_TOP_POSITION_GAINED);
        } else {
            expectedRelaunchSequence = Arrays.asList(ON_RESTART, ON_START, ON_NEW_INTENT, ON_RESUME,
                    ON_TOP_POSITION_GAINED, ON_TOP_POSITION_LOST, ON_PAUSE, ON_STOP, ON_DESTROY,
                    PRE_ON_CREATE, ON_CREATE, ON_START, ON_POST_CREATE, ON_RESUME,
                    ON_TOP_POSITION_GAINED);
        }

        waitForActivityTransitions(SingleTopActivity.class, expectedRelaunchSequence);
        LifecycleVerifier.assertSequence(SingleTopActivity.class, getLifecycleLog(),
                expectedRelaunchSequence, "recreate");
    }

    @Test
    public void testOnNewIntent() throws Exception {
        // Launch a singleTop activity
        final Activity singleTopActivity =
                mSingleTopActivityTestRule.launchActivity(new Intent());

        // Wait for the activity to resume
        waitAndAssertActivityStates(state(singleTopActivity, ON_TOP_POSITION_GAINED));
        LifecycleVerifier.assertLaunchSequence(SingleTopActivity.class, getLifecycleLog());

        // Try to launch again
        getLifecycleLog().clear();
        final Intent intent = new Intent(mContext, SingleTopActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        mTargetContext.startActivity(intent);

        // Wait for the activity to resume again
        waitAndAssertActivityStates(state(singleTopActivity, ON_TOP_POSITION_GAINED));

        // Verify that the first activity was paused, new intent was delivered and resumed again
        LifecycleVerifier.assertSequence(SingleTopActivity.class, getLifecycleLog(),
                Arrays.asList(ON_TOP_POSITION_LOST, ON_PAUSE, ON_NEW_INTENT, ON_RESUME,
                        ON_TOP_POSITION_GAINED), "newIntent");
    }

    @Test
    public void testOnNewIntentFromHidden() throws Exception {
        // Launch a singleTop activity
        final Activity singleTopActivity =
                mSingleTopActivityTestRule.launchActivity(new Intent());

        // Wait for the activity to resume
        waitAndAssertActivityStates(state(singleTopActivity, ON_TOP_POSITION_GAINED));
        LifecycleVerifier.assertLaunchSequence(SingleTopActivity.class, getLifecycleLog());

        // Launch something on top
        final Intent newTaskIntent = new Intent();
        newTaskIntent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK);
        final Activity secondActivity = mSecondActivityTestRule.launchActivity(newTaskIntent);

        // Wait for the activity to resume
        waitAndAssertActivityStates(state(secondActivity, ON_RESUME));
        waitAndAssertActivityStates(occludedActivityState(singleTopActivity, secondActivity));

        // Try to launch again
        getLifecycleLog().clear();
        final Intent intent = new Intent(mContext, SingleTopActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        mTargetContext.startActivity(intent);

        // Wait for the activity to resume again
        waitAndAssertActivityStates(state(singleTopActivity, ON_TOP_POSITION_GAINED));

        // Verify that the first activity was restarted, new intent was delivered and resumed again
        final List<LifecycleLog.ActivityCallback> expectedSequence;
        if (isTranslucent(singleTopActivity)) {
            expectedSequence = Arrays.asList(ON_NEW_INTENT, ON_RESUME, ON_TOP_POSITION_GAINED);
        } else {
            expectedSequence = Arrays.asList(ON_RESTART, ON_START, ON_NEW_INTENT, ON_RESUME,
                    ON_TOP_POSITION_GAINED);
        }
        LifecycleVerifier.assertSequence(SingleTopActivity.class, getLifecycleLog(),
                expectedSequence, "newIntent");
    }

    @Test
    public void testOnNewIntentFromPaused() throws Exception {
        // Launch a singleTop activity
        final Activity singleTopActivity =
                mSingleTopActivityTestRule.launchActivity(new Intent());

        // Wait for the activity to resume
        waitAndAssertActivityStates(state(singleTopActivity, ON_TOP_POSITION_GAINED));
        LifecycleVerifier.assertLaunchSequence(SingleTopActivity.class, getLifecycleLog());

        // Launch translucent activity, which will make the first one paused.
        mTranslucentActivityTestRule.launchActivity(new Intent());

        // Wait for the activity to pause
        waitAndAssertActivityStates(state(singleTopActivity, ON_PAUSE));

        // Try to launch again
        getLifecycleLog().clear();
        final Intent intent = new Intent(mContext, SingleTopActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mTargetContext.startActivity(intent);

        // Wait for the activity to resume again
        // Verify that the new intent was delivered and resumed again
        final List<LifecycleLog.ActivityCallback> expectedSequence =
                Arrays.asList(ON_NEW_INTENT, ON_RESUME, ON_TOP_POSITION_GAINED);
        waitForActivityTransitions(SingleTopActivity.class, expectedSequence);
        LifecycleVerifier.assertSequence(SingleTopActivity.class, getLifecycleLog(),
                expectedSequence, "newIntent");
    }
}
