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

import static android.server.wm.StateLogger.log;
import static android.server.wm.app.Components.PipActivity.EXTRA_ENTER_PIP;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_ACTIVITY_RESULT;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_CREATE;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_DESTROY;
import static android.server.wm.lifecycle.LifecycleLog.ActivityCallback.ON_MULTI_WINDOW_MODE_CHANGED;
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

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.server.wm.MultiDisplayTestBase;
import android.server.wm.lifecycle.LifecycleLog.ActivityCallback;
import android.util.Pair;

import androidx.test.rule.ActivityTestRule;

import org.junit.Before;

import java.util.List;

/** Base class for device-side tests that verify correct activity lifecycle transitions. */
public class ActivityLifecycleClientTestBase extends MultiDisplayTestBase {

    static final String EXTRA_RECREATE = "recreate";
    static final String EXTRA_FINISH_IN_ON_RESUME = "finish_in_on_resume";
    static final String EXTRA_FINISH_AFTER_RESUME = "finish_after_resume";

    static final ComponentName CALLBACK_TRACKING_ACTIVITY =
            getComponentName(CallbackTrackingActivity.class);

    static final ComponentName CONFIG_CHANGE_HANDLING_ACTIVITY =
            getComponentName(ConfigChangeHandlingActivity.class);

    final ActivityTestRule mFirstActivityTestRule = new ActivityTestRule<>(FirstActivity.class,
            true /* initialTouchMode */, false /* launchActivity */);

    final ActivityTestRule mSecondActivityTestRule = new ActivityTestRule<>(SecondActivity.class,
            true /* initialTouchMode */, false /* launchActivity */);

    final ActivityTestRule mThirdActivityTestRule = new ActivityTestRule<>(ThirdActivity.class,
            true /* initialTouchMode */, false /* launchActivity */);

    final ActivityTestRule mTranslucentActivityTestRule = new ActivityTestRule<>(
            TranslucentActivity.class, true /* initialTouchMode */, false /* launchActivity */);

    final ActivityTestRule mSecondTranslucentActivityTestRule = new ActivityTestRule<>(
            SecondTranslucentActivity.class, true /* initialTouchMode */,
            false /* launchActivity */);

    final ActivityTestRule mLaunchForResultActivityTestRule = new ActivityTestRule<>(
             LaunchForResultActivity.class, true /* initialTouchMode */, false /* launchActivity */);

    final ActivityTestRule mCallbackTrackingActivityTestRule = new ActivityTestRule<>(
            CallbackTrackingActivity.class, true /* initialTouchMode */,
            false /* launchActivity */);

    final ActivityTestRule mTranslucentCallbackTrackingActivityTestRule = new ActivityTestRule<>(
            TranslucentCallbackTrackingActivity.class, true /* initialTouchMode */,
            false /* launchActivity */);

    final ActivityTestRule mShowWhenLockedCallbackTrackingActivityTestRule = new ActivityTestRule<>(
            ShowWhenLockedCallbackTrackingActivity.class, true /* initialTouchMode */,
            false /* launchActivity */);

    final ActivityTestRule mSingleTopActivityTestRule = new ActivityTestRule<>(
            SingleTopActivity.class, true /* initialTouchMode */, false /* launchActivity */);

    final ActivityTestRule mConfigChangeHandlingActivityTestRule = new ActivityTestRule<>(
            ConfigChangeHandlingActivity.class, true /* initialTouchMode */,
            false /* launchActivity */);

    final ActivityTestRule mPipActivityTestRule = new ActivityTestRule<>(
            PipActivity.class, true /* initialTouchMode */, false /* launchActivity */);

    final ActivityTestRule mAlwaysFocusableActivityTestRule = new ActivityTestRule<>(
            AlwaysFocusablePipActivity.class, true /* initialTouchMode */,
            false /* launchActivity */);

    final ActivityTestRule mSlowActivityTestRule = new ActivityTestRule<>(
            SlowActivity.class, true /* initialTouchMode */, false /* launchActivity */);

    private static LifecycleLog mLifecycleLog;

    protected Context mTargetContext;
    private LifecycleTracker mLifecycleTracker;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mTargetContext = getInstrumentation().getTargetContext();
        // Log transitions for all activities that belong to this app.
        mLifecycleLog = new LifecycleLog();
        mLifecycleLog.clear();

        // Track transitions and allow waiting for pending activity states.
        mLifecycleTracker = new LifecycleTracker(mLifecycleLog);
    }

    /** Launch an activity given a class. */
    protected Activity launchActivity(Class<? extends Activity> activityClass) {
        final Intent intent = new Intent(mTargetContext, activityClass);
        return getInstrumentation().startActivitySync(intent);
    }

    /**
     * Blocking call that will wait for activities to reach expected states with timeout.
     */
    @SafeVarargs
    final void waitAndAssertActivityStates(
            Pair<Class<? extends Activity>, ActivityCallback>... activityCallbacks) {
        log("Start waitAndAssertActivityCallbacks");
        mLifecycleTracker.waitAndAssertActivityStates(activityCallbacks);
    }

    /**
     * Blocking call that will wait for activities to perform the expected sequence of transitions.
     * @see LifecycleTracker#waitForActivityTransitions(Class, List)
     */
    final void waitForActivityTransitions(Class<? extends Activity> activityClass,
            List<ActivityCallback> expectedTransitions) {
        log("Start waitForActivityTransitions");
        mLifecycleTracker.waitForActivityTransitions(activityClass, expectedTransitions);
    }

    /**
     * Blocking call that will wait for activities to perform the expected sequence of transitions.
     * After waiting it asserts that the sequence matches the expected.
     * @see LifecycleTracker#waitForActivityTransitions(Class, List)
     */
    final void waitAndAssertActivityTransitions(Class<? extends Activity> activityClass,
            List<ActivityCallback> expectedTransitions, String message) {
        log("Start waitAndAssertActivityTransition");
        mLifecycleTracker.waitForActivityTransitions(activityClass, expectedTransitions);

        LifecycleVerifier.assertSequence(activityClass, getLifecycleLog(), expectedTransitions,
                message);
    }

    LifecycleLog getLifecycleLog() {
        return mLifecycleLog;
    }

    static Pair<Class<? extends Activity>, ActivityCallback> state(Activity activity,
            ActivityCallback stage) {
        return state(activity.getClass(), stage);
    }

    static Pair<Class<? extends Activity>, ActivityCallback> state(
            Class<? extends Activity> activityClass, ActivityCallback stage) {
        return new Pair<>(activityClass, stage);
    }

    /**
     * Returns a pair of the activity and the state it should be in based on the configuration of
     * occludingActivity.
     */
    static Pair<Class<? extends Activity>, ActivityCallback> occludedActivityState(
            Activity activity, Activity occludingActivity) {
        return occludedActivityState(activity, isTranslucent(occludingActivity));
    }

    /**
     * Returns a pair of the activity and the state it should be in based on
     * occludingActivityIsTranslucent.
     */
    static Pair<Class<? extends Activity>, ActivityCallback> occludedActivityState(
            Activity activity, boolean occludingActivityIsTranslucent) {
        // Activities behind a translucent activity should be in the paused state since they are
        // still visible. Otherwise, they should be in the stopped state.
        return state(activity, occludedActivityState(occludingActivityIsTranslucent));
    }

    static ActivityCallback occludedActivityState(boolean occludingActivityIsTranslucent) {
        return occludingActivityIsTranslucent ? ON_PAUSE : ON_STOP;
    }

    /** Returns true if the input activity is translucent. */
    static boolean isTranslucent(Activity activity) {
        return ActivityInfo.isTranslucentOrFloating(activity.getWindow().getWindowStyle());
    }

    /** Base activity that only tracks fundamental activity lifecycle states. */
    public static class LifecycleTrackingActivity extends Activity {
        LifecycleLog.LifecycleLogClient mLifecycleLogClient;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mLifecycleLogClient = LifecycleLog.LifecycleLogClient.create(this);
            mLifecycleLogClient.onActivityCallback(PRE_ON_CREATE);
            mLifecycleLogClient.onActivityCallback(ON_CREATE);
        }

        @Override
        protected void onStart() {
            super.onStart();
            mLifecycleLogClient.onActivityCallback(ON_START);
        }

        @Override
        protected void onResume() {
            super.onResume();
            mLifecycleLogClient.onActivityCallback(ON_RESUME);
        }

        @Override
        protected void onPause() {
            super.onPause();
            mLifecycleLogClient.onActivityCallback(ON_PAUSE);
        }

        @Override
        protected void onStop() {
            super.onStop();
            mLifecycleLogClient.onActivityCallback(ON_STOP);
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            mLifecycleLogClient.onActivityCallback(ON_DESTROY);
            mLifecycleLogClient.close();
        }

        @Override
        protected void onRestart() {
            super.onRestart();
            mLifecycleLogClient.onActivityCallback(ON_RESTART);
        }
    }

    // Test activity
    public static class FirstActivity extends LifecycleTrackingActivity {
    }

    // Test activity
    public static class SecondActivity extends LifecycleTrackingActivity {
    }

    // Test activity
    public static class ThirdActivity extends LifecycleTrackingActivity {
    }

    // Translucent test activity
    public static class TranslucentActivity extends LifecycleTrackingActivity {
    }

    // Translucent test activity
    public static class SecondTranslucentActivity extends LifecycleTrackingActivity {
    }

    /**
     * Base activity that records callbacks in addition to main lifecycle transitions.
     */
    public static class CallbackTrackingActivity extends LifecycleTrackingActivity {

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            mLifecycleLogClient.onActivityCallback(ON_ACTIVITY_RESULT);
        }

        @Override
        protected void onPostCreate(Bundle savedInstanceState) {
            super.onPostCreate(savedInstanceState);
            mLifecycleLogClient.onActivityCallback(ON_POST_CREATE);
        }

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            mLifecycleLogClient.onActivityCallback(ON_NEW_INTENT);
        }

        @Override
        public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
            mLifecycleLogClient.onActivityCallback(
                    isTopResumedActivity ? ON_TOP_POSITION_GAINED : ON_TOP_POSITION_LOST);
        }

        @Override
        public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
            mLifecycleLogClient.onActivityCallback(ON_MULTI_WINDOW_MODE_CHANGED);
        }
    }

    // Translucent callback tracking test activity
    public static class TranslucentCallbackTrackingActivity extends CallbackTrackingActivity {
    }

    // Callback tracking activity that supports being shown on top of lock screen
    public static class ShowWhenLockedCallbackTrackingActivity extends CallbackTrackingActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setShowWhenLocked(true);
        }
    }

    /**
     * Test activity that launches {@link ResultActivity} for result.
     */
    public static class LaunchForResultActivity extends CallbackTrackingActivity {

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            startForResult();
        }

        private void startForResult() {
            final Intent intent = new Intent(this, ResultActivity.class);
            intent.putExtras(getIntent());
            startActivityForResult(intent, 1 /* requestCode */);
        }
    }

    /** Test activity that is started for result and finishes itself in ON_RESUME. */
    public static class ResultActivity extends CallbackTrackingActivity {
        @Override
        protected void onResume() {
            super.onResume();
            setResult(RESULT_OK);
            final Intent intent = getIntent();
            if (intent.getBooleanExtra(EXTRA_FINISH_IN_ON_RESUME, false)) {
                finish();
            } else if (intent.getBooleanExtra(EXTRA_FINISH_AFTER_RESUME, false)) {
                new Handler().postDelayed(() -> finish(), 2000);
            }
        }
    }

    /** Test activity that can call {@link Activity#recreate()} if requested in a new intent. */
    public static class SingleTopActivity extends CallbackTrackingActivity {

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            if (intent != null && intent.getBooleanExtra(EXTRA_RECREATE, false)) {
                recreate();
            }
        }
    }

    // Config change handling activity
    public static class ConfigChangeHandlingActivity extends CallbackTrackingActivity {
    }

    // Callback tracking activity that runs in a separate process
    public static class SecondProcessCallbackTrackingActivity extends CallbackTrackingActivity {
    }

    // Pip-capable activity
    // TODO(b/123013403): Disabled onMultiWindowMode changed callbacks to make the tests pass, so
    // that they can verify other lifecycle transitions. This should be fixed and switched to
    // extend CallbackTrackingActivity.
    public static class PipActivity extends LifecycleTrackingActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Enter picture in picture with the given aspect ratio if provided
            if (getIntent().hasExtra(EXTRA_ENTER_PIP)) {
                enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            }
        }
    }

    public static class AlwaysFocusablePipActivity extends CallbackTrackingActivity {
    }

    public static class SlowActivity extends CallbackTrackingActivity {

        static final String EXTRA_CONTROL_FLAGS = "extra_control_flags";
        static final int FLAG_SLOW_TOP_RESUME_RELEASE = 0x00000001;
        static final int FLAG_TIMEOUT_TOP_RESUME_RELEASE = 0x00000002;

        private int mFlags;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mFlags = getIntent().getIntExtra(EXTRA_CONTROL_FLAGS, 0);
        }

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            mFlags = getIntent().getIntExtra(EXTRA_CONTROL_FLAGS, 0);
        }

        @Override
        public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
            if (!isTopResumedActivity && (mFlags & FLAG_SLOW_TOP_RESUME_RELEASE) != 0) {
                sleep(200);
            } else if (!isTopResumedActivity && (mFlags & FLAG_TIMEOUT_TOP_RESUME_RELEASE) != 0) {
                sleep(2000);
            }
            // Intentionally moving the logging of the state change to after sleep to facilitate
            // race condition with other activity getting top state before this releases its.
            super.onTopResumedActivityChanged(isTopResumedActivity);
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    static ComponentName getComponentName(Class<? extends Activity> activity) {
        return new ComponentName(getInstrumentation().getContext(), activity);
    }

    void moveTaskToPrimarySplitScreenAndVerify(Activity activity) {
        getLifecycleLog().clear();

        moveTaskToPrimarySplitScreen(activity.getTaskId());

        final Class<? extends Activity> activityClass = activity.getClass();
        waitAndAssertActivityTransitions(activityClass,
                LifecycleVerifier.getSplitScreenTransitionSequence(activityClass),
                "enterSplitScreen");
    }
}
