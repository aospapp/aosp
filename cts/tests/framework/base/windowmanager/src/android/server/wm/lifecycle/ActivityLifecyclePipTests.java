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

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.server.wm.app.Components.PipActivity.EXTRA_ENTER_PIP;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_CREATE;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_DESTROY;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_PAUSE;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_RESTART;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_RESUME;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_START;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_STOP;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.PRE_ON_CREATE;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:ActivityLifecyclePipTests
 */
@FlakyTest(bugId = 77652261)
@MediumTest
@Presubmit
public class ActivityLifecyclePipTests extends ActivityLifecycleClientTestBase {

    @Before
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue(supportsPip());
    }

    @Test
    public void testGoToPip() throws Exception {
        // Launch first activity
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());

        // Launch Pip-capable activity
        final Activity pipActivity = mPipActivityTestRule.launchActivity(new Intent());

        waitAndAssertActivityStates(state(firstActivity, ON_STOP), state(pipActivity, ON_RESUME));

        // Move activity to Picture-In-Picture
        getLifecycleLog().clear();
        final ComponentName pipActivityName = getComponentName(PipActivity.class);
        mAmWmState.computeState(pipActivityName);
        final int stackId = mAmWmState.getAmState().getStackIdByActivity(pipActivityName);
        assertNotEquals(stackId, INVALID_STACK_ID);
        moveTopActivityToPinnedStack(stackId);

        // Wait and assert lifecycle
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME), state(pipActivity, ON_PAUSE));
        LifecycleVerifier.assertRestartAndResumeSequence(FirstActivity.class, getLifecycleLog());
        LifecycleVerifier.assertSequence(PipActivity.class, getLifecycleLog(),
                Arrays.asList(ON_PAUSE), "enterPip");
    }

    @Test
    public void testPipOnLaunch() throws Exception {
        // Launch first activity
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());

        // Clear the log before launching to Pip
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));
        getLifecycleLog().clear();

        // Launch Pip-capable activity and enter Pip immediately
        final Activity pipActivity = mPipActivityTestRule.launchActivity(
                new Intent().putExtra(EXTRA_ENTER_PIP, true));

        // Wait and assert lifecycle
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME), state(pipActivity, ON_PAUSE));

        final List<LifecycleLog.ActivityCallback> expectedSequence =
                Arrays.asList(ON_PAUSE, ON_RESUME);
        final List<LifecycleLog.ActivityCallback> extraCycleSequence =
                Arrays.asList(ON_PAUSE, ON_STOP, ON_RESTART, ON_START, ON_RESUME);
        LifecycleVerifier.assertSequenceMatchesOneOf(FirstActivity.class,
                getLifecycleLog(), Arrays.asList(expectedSequence, extraCycleSequence),
                "activityEnteringPipOnTop");
        LifecycleVerifier.assertSequence(PipActivity.class, getLifecycleLog(),
                Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_RESUME, ON_PAUSE),
                "launchAndEnterPip");
    }

    @Test
    public void testDestroyPip() throws Exception {
        // Launch first activity
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());

        // Clear the log before launching to Pip
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));
        getLifecycleLog().clear();

        // Launch Pip-capable activity and enter Pip immediately
        final Activity pipActivity = mPipActivityTestRule.launchActivity(
                new Intent().putExtra(EXTRA_ENTER_PIP, true));

        // Wait and assert lifecycle
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME), state(pipActivity, ON_PAUSE));

        // Exit PiP
        getLifecycleLog().clear();
        pipActivity.finish();

        waitAndAssertActivityStates(state(pipActivity, ON_DESTROY));
        LifecycleVerifier.assertEmptySequence(FirstActivity.class, getLifecycleLog(), "finishPip");
        LifecycleVerifier.assertSequence(PipActivity.class, getLifecycleLog(),
                Arrays.asList(ON_STOP, ON_DESTROY), "finishPip");
    }

    @Test
    public void testLaunchBelowPip() throws Exception {
        // Launch Pip-capable activity and enter Pip immediately
        final Activity pipActivity = mPipActivityTestRule.launchActivity(
                new Intent().putExtra(EXTRA_ENTER_PIP, true));

        waitAndAssertActivityStates(state(pipActivity, ON_PAUSE));

        // Launch a regular activity below
        getLifecycleLog().clear();
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent()
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK));

        // Wait and verify the sequence
        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));
        LifecycleVerifier.assertLaunchSequence(FirstActivity.class, getLifecycleLog());
        LifecycleVerifier.assertEmptySequence(PipActivity.class, getLifecycleLog(),
                "launchBelowPip");
    }

    @Test
    public void testIntoPipSameTask() throws Exception {
        // Launch Pip-capable activity and enter Pip immediately
        final Activity pipActivity = mPipActivityTestRule.launchActivity(
                new Intent().putExtra(EXTRA_ENTER_PIP, true));

        waitAndAssertActivityStates(state(pipActivity, ON_PAUSE));

        // Launch a regular activity into same task
        getLifecycleLog().clear();
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());

        // Wait and verify the sequence
        waitAndAssertActivityStates(state(pipActivity, ON_STOP), state(firstActivity, ON_PAUSE));

        // TODO(b/123013403): sometimes extra one or even more relaunches happen
        //final List<LifecycleLog.ActivityCallback> extraDestroySequence =
        //        Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP,
        //                ON_DESTROY, PRE_ON_CREATE, ON_CREATE, ON_START, ON_RESUME, ON_PAUSE);
        //waitForActivityTransitions(FirstActivity.class, extraDestroySequence);
        //final List<LifecycleLog.ActivityCallback> expectedSequence =
        //        Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_RESUME, ON_PAUSE);
        //LifecycleVerifier.assertSequenceMatchesOneOf(FirstActivity.class, getLifecycleLog(),
        //        Arrays.asList(extraDestroySequence, expectedSequence),
        //        "launchIntoPip");

        LifecycleVerifier.assertSequence(PipActivity.class, getLifecycleLog(),
                Arrays.asList(ON_STOP), "launchIntoPip");
    }

    @Test
    public void testDestroyBelowPip() throws Exception {
        // Launch a regular activity
        final Activity firstActivity = mFirstActivityTestRule.launchActivity(new Intent());

        // Launch Pip-capable activity and enter Pip immediately
        final Activity pipActivity = mPipActivityTestRule.launchActivity(
                new Intent().putExtra(EXTRA_ENTER_PIP, true));

        waitAndAssertActivityStates(state(pipActivity, ON_PAUSE), state(firstActivity, ON_RESUME));

        // Destroy the activity below
        getLifecycleLog().clear();
        firstActivity.finish();
        waitAndAssertActivityStates(state(firstActivity, ON_DESTROY));
        LifecycleVerifier.assertResumeToDestroySequence(FirstActivity.class, getLifecycleLog());
        LifecycleVerifier.assertEmptySequence(PipActivity.class, getLifecycleLog(),
                "destroyBelowPip");
    }

    @Test
    public void testSplitScreenBelowPip() throws Exception {
        // Launch Pip-capable activity and enter Pip immediately
        final Activity pipActivity = mPipActivityTestRule.launchActivity(
                new Intent().putExtra(EXTRA_ENTER_PIP, true));

        waitAndAssertActivityStates(state(pipActivity, ON_PAUSE));

        // Launch first activity
        getLifecycleLog().clear();
        final Activity firstActivity =
                mFirstActivityTestRule.launchActivity(new Intent()
                        .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK));

        waitAndAssertActivityStates(state(firstActivity, ON_RESUME));
        LifecycleVerifier.assertLaunchSequence(FirstActivity.class, getLifecycleLog());

        // Enter split screen
        moveTaskToPrimarySplitScreenAndVerify(firstActivity);
        // TODO(b/123013403): will fail with callback tracking enabled - delivers extra
        // MULTI_WINDOW_MODE_CHANGED
        LifecycleVerifier.assertEmptySequence(PipActivity.class, getLifecycleLog(),
                "launchBelow");

        // Launch second activity to side
        getLifecycleLog().clear();
        final Activity secondActivity = mSecondActivityTestRule.launchActivity(
                new Intent().setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK));

        // Wait for activities to resume and verify lifecycle
        waitAndAssertActivityStates(state(secondActivity, ON_RESUME));
        LifecycleVerifier.assertLaunchSequence(SecondActivity.class, getLifecycleLog());
        LifecycleVerifier.assertSequence(FirstActivity.class, getLifecycleLog(),
                Arrays.asList(ON_RESUME), "launchToSide");
        LifecycleVerifier.assertEmptySequence(PipActivity.class, getLifecycleLog(),
                "launchBelow");
    }

    @Test
    public void testPipAboveSplitScreen() throws Exception {
        // Launch first activity
        final Activity firstActivity =
                mFirstActivityTestRule.launchActivity(new Intent());

        // Enter split screen
        moveTaskToPrimarySplitScreenAndVerify(firstActivity);

        // Launch second activity to side
        final Activity secondActivity = mSecondActivityTestRule.launchActivity(
                new Intent().setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK));

        // Wait for activities to resume
        waitAndAssertActivityStates(state(secondActivity, ON_RESUME),
                state(firstActivity, ON_RESUME));

        // Launch Pip-capable activity and enter Pip immediately
        getLifecycleLog().clear();
        final Activity pipActivity = mPipActivityTestRule.launchActivity(
                new Intent().putExtra(EXTRA_ENTER_PIP, true));

        // Wait for it to launch and pause. Other activities should not be affected.
        waitAndAssertActivityStates(state(pipActivity, ON_PAUSE), state(secondActivity, ON_RESUME));
        LifecycleVerifier.assertSequence(PipActivity.class, getLifecycleLog(),
                Arrays.asList(PRE_ON_CREATE, ON_CREATE, ON_START, ON_RESUME, ON_PAUSE),
                "launchAndEnterPip");
        LifecycleVerifier.assertEmptySequence(FirstActivity.class, getLifecycleLog(),
                "launchPipOnTop");
        final List<LifecycleLog.ActivityCallback> expectedSequence =
                Arrays.asList(ON_PAUSE, ON_RESUME);
        final List<LifecycleLog.ActivityCallback> extraCycleSequence =
                Arrays.asList(ON_PAUSE, ON_STOP, ON_RESTART, ON_START, ON_RESUME);
        // TODO(b/123013403): sometimes extra destroy is observed
        LifecycleVerifier.assertSequenceMatchesOneOf(SecondActivity.class,
                getLifecycleLog(), Arrays.asList(expectedSequence, extraCycleSequence),
                "activityEnteringPipOnTop");
    }
}
