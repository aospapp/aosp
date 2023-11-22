/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.Instrumentation.ActivityMonitor;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.CATEGORY_HOME;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS;
import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.content.pm.PackageManager.FEATURE_EMBEDDED;
import static android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.content.pm.PackageManager.FEATURE_SCREEN_LANDSCAPE;
import static android.content.pm.PackageManager.FEATURE_SCREEN_PORTRAIT;
import static android.content.pm.PackageManager.FEATURE_SECURE_LOCK_SCREEN;
import static android.content.pm.PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static android.server.wm.ActivityLauncher.KEY_ACTIVITY_TYPE;
import static android.server.wm.ActivityLauncher.KEY_DISPLAY_ID;
import static android.server.wm.ActivityLauncher.KEY_INTENT_EXTRAS;
import static android.server.wm.ActivityLauncher.KEY_INTENT_FLAGS;
import static android.server.wm.ActivityLauncher.KEY_LAUNCH_ACTIVITY;
import static android.server.wm.ActivityLauncher.KEY_LAUNCH_TO_SIDE;
import static android.server.wm.ActivityLauncher.KEY_MULTIPLE_INSTANCES;
import static android.server.wm.ActivityLauncher.KEY_MULTIPLE_TASK;
import static android.server.wm.ActivityLauncher.KEY_NEW_TASK;
import static android.server.wm.ActivityLauncher.KEY_RANDOM_DATA;
import static android.server.wm.ActivityLauncher.KEY_REORDER_TO_FRONT;
import static android.server.wm.ActivityLauncher.KEY_SUPPRESS_EXCEPTIONS;
import static android.server.wm.ActivityLauncher.KEY_TARGET_COMPONENT;
import static android.server.wm.ActivityLauncher.KEY_USE_APPLICATION_CONTEXT;
import static android.server.wm.ActivityLauncher.KEY_USE_INSTRUMENTATION;
import static android.server.wm.ActivityLauncher.launchActivityFromExtras;
import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.ComponentNameUtils.getLogTag;
import static android.server.wm.StateLogger.log;
import static android.server.wm.StateLogger.logAlways;
import static android.server.wm.StateLogger.logE;
import static android.server.wm.UiDeviceUtils.pressAppSwitchButton;
import static android.server.wm.UiDeviceUtils.pressBackButton;
import static android.server.wm.UiDeviceUtils.pressEnterButton;
import static android.server.wm.UiDeviceUtils.pressHomeButton;
import static android.server.wm.UiDeviceUtils.pressSleepButton;
import static android.server.wm.UiDeviceUtils.pressUnlockButton;
import static android.server.wm.UiDeviceUtils.pressWakeupButton;
import static android.server.wm.UiDeviceUtils.waitForDeviceIdle;
import static android.server.wm.app.Components.BROADCAST_RECEIVER_ACTIVITY;
import static android.server.wm.app.Components.BroadcastReceiverActivity.ACTION_TRIGGER_BROADCAST;
import static android.server.wm.app.Components.BroadcastReceiverActivity.EXTRA_BROADCAST_ORIENTATION;
import static android.server.wm.app.Components.BroadcastReceiverActivity.EXTRA_CUTOUT_EXISTS;
import static android.server.wm.app.Components.BroadcastReceiverActivity.EXTRA_DISMISS_KEYGUARD;
import static android.server.wm.app.Components.BroadcastReceiverActivity.EXTRA_DISMISS_KEYGUARD_METHOD;
import static android.server.wm.app.Components.BroadcastReceiverActivity.EXTRA_FINISH_BROADCAST;
import static android.server.wm.app.Components.BroadcastReceiverActivity.EXTRA_MOVE_BROADCAST_TO_BACK;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.server.wm.app.Components.PipActivity.ACTION_EXPAND_PIP;
import static android.server.wm.app.Components.PipActivity.ACTION_SET_REQUESTED_ORIENTATION;
import static android.server.wm.app.Components.PipActivity.EXTRA_PIP_ORIENTATION;
import static android.server.wm.app.Components.PipActivity.EXTRA_SET_ASPECT_RATIO_WITH_DELAY_DENOMINATOR;
import static android.server.wm.app.Components.PipActivity.EXTRA_SET_ASPECT_RATIO_WITH_DELAY_NUMERATOR;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.second.Components.SECOND_ACTIVITY;
import static android.server.wm.third.Components.THIRD_ACTIVITY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Surface.ROTATION_0;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static java.lang.Integer.toHexString;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.server.wm.CommandSession.ActivityCallback;
import android.server.wm.CommandSession.ActivitySession;
import android.server.wm.CommandSession.ConfigInfo;
import android.server.wm.CommandSession.LaunchInjector;
import android.server.wm.CommandSession.LaunchProxy;
import android.server.wm.CommandSession.SizeInfo;
import android.server.wm.TestJournalProvider.TestJournalContainer;
import android.server.wm.settings.SettingsSession;
import android.util.EventLog;
import android.util.EventLog.Event;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.rule.ActivityTestRule;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class ActivityManagerTestBase {
    private static final boolean PRETEND_DEVICE_SUPPORTS_PIP = false;
    private static final boolean PRETEND_DEVICE_SUPPORTS_FREEFORM = false;
    private static final String LOG_SEPARATOR = "LOG_SEPARATOR";
    // Use one of the test tags as a separator
    private static final int EVENT_LOG_SEPARATOR_TAG = 42;

    protected static final int[] ALL_ACTIVITY_TYPE_BUT_HOME = {
            ACTIVITY_TYPE_STANDARD, ACTIVITY_TYPE_ASSISTANT, ACTIVITY_TYPE_RECENTS,
            ACTIVITY_TYPE_UNDEFINED
    };

    private static final String TEST_PACKAGE = TEST_ACTIVITY.getPackageName();
    private static final String SECOND_TEST_PACKAGE = SECOND_ACTIVITY.getPackageName();
    private static final String THIRD_TEST_PACKAGE = THIRD_ACTIVITY.getPackageName();
    private static final List<String> TEST_PACKAGES;

    static {
        final List<String> testPackages = new ArrayList<>(3);
        testPackages.add(TEST_PACKAGE);
        testPackages.add(SECOND_TEST_PACKAGE);
        testPackages.add(THIRD_TEST_PACKAGE);
        testPackages.add("android.server.wm.cts");
        TEST_PACKAGES = Collections.unmodifiableList(testPackages);
    }

    protected static final String AM_START_HOME_ACTIVITY_COMMAND =
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME";

    private static final String LOCK_CREDENTIAL = "1234";

    private static final int UI_MODE_TYPE_MASK = 0x0f;
    private static final int UI_MODE_TYPE_VR_HEADSET = 0x07;

    private static Boolean sHasHomeScreen = null;
    private static Boolean sSupportsSystemDecorsOnSecondaryDisplays = null;
    private static Boolean sSupportsInsecureLockScreen = null;

    protected static final int INVALID_DEVICE_ROTATION = -1;

    protected Context mContext;
    protected ActivityManager mAm;
    protected ActivityTaskManager mAtm;

    /**
     * Callable to clear launch params for all test packages.
     */
    private final Callable<Void> mClearLaunchParamsCallable = () -> {
        mAtm.clearLaunchParamsForPackages(TEST_PACKAGES);
        return null;
    };

    @Rule
    public final ActivityTestRule<SideActivity> mSideActivityRule =
            new ActivityTestRule<>(SideActivity.class, true /* initialTouchMode */,
                    false /* launchActivity */);

    /**
     * @return the am command to start the given activity with the following extra key/value pairs.
     * {@param keyValuePairs} must be a list of arguments defining each key/value extra.
     */
    // TODO: Make this more generic, for instance accepting flags or extras of other types.
    protected static String getAmStartCmd(final ComponentName activityName,
            final String... keyValuePairs) {
        return getAmStartCmdInternal(getActivityName(activityName), keyValuePairs);
    }

    private static String getAmStartCmdInternal(final String activityName,
            final String... keyValuePairs) {
        return appendKeyValuePairs(
                new StringBuilder("am start -n ").append(activityName),
                keyValuePairs);
    }

    private static String appendKeyValuePairs(
            final StringBuilder cmd, final String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new RuntimeException("keyValuePairs must be pairs of key/value arguments");
        }
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            final String key = keyValuePairs[i];
            final String value = keyValuePairs[i + 1];
            cmd.append(" --es ")
                    .append(key)
                    .append(" ")
                    .append(value);
        }
        return cmd.toString();
    }

    protected static String getAmStartCmd(final ComponentName activityName, final int displayId,
            final String... keyValuePair) {
        return getAmStartCmdInternal(getActivityName(activityName), displayId, keyValuePair);
    }

    private static String getAmStartCmdInternal(final String activityName, final int displayId,
            final String... keyValuePairs) {
        return appendKeyValuePairs(
                new StringBuilder("am start -n ")
                        .append(activityName)
                        .append(" -f 0x")
                        .append(toHexString(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK))
                        .append(" --display ")
                        .append(displayId),
                keyValuePairs);
    }

    protected static String getAmStartCmdInNewTask(final ComponentName activityName) {
        return "am start -n " + getActivityName(activityName) + " -f 0x18000000";
    }

    protected static String getAmStartCmdOverHome(final ComponentName activityName) {
        return "am start --activity-task-on-home -n " + getActivityName(activityName);
    }

    protected ActivityAndWindowManagersState mAmWmState = new ActivityAndWindowManagersState();

    public ActivityAndWindowManagersState getAmWmState() {
        return mAmWmState;
    }

    protected BroadcastActionTrigger mBroadcastActionTrigger = new BroadcastActionTrigger();

    /**
     * Helper class to process test actions by broadcast.
     */
    protected class BroadcastActionTrigger {

        private Intent createIntentWithAction(String broadcastAction) {
            return new Intent(broadcastAction)
                    .setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }

        void doAction(String broadcastAction) {
            mContext.sendBroadcast(createIntentWithAction(broadcastAction));
        }

        void finishBroadcastReceiverActivity() {
            mContext.sendBroadcast(createIntentWithAction(ACTION_TRIGGER_BROADCAST)
                    .putExtra(EXTRA_FINISH_BROADCAST, true));
        }

        void launchActivityNewTask(String launchComponent) {
            mContext.sendBroadcast(createIntentWithAction(ACTION_TRIGGER_BROADCAST)
                    .putExtra(KEY_LAUNCH_ACTIVITY, true)
                    .putExtra(KEY_NEW_TASK, true)
                    .putExtra(KEY_TARGET_COMPONENT, launchComponent));
        }

        void moveTopTaskToBack() {
            mContext.sendBroadcast(createIntentWithAction(ACTION_TRIGGER_BROADCAST)
                    .putExtra(EXTRA_MOVE_BROADCAST_TO_BACK, true));
        }

        void requestOrientation(int orientation) {
            mContext.sendBroadcast(createIntentWithAction(ACTION_TRIGGER_BROADCAST)
                    .putExtra(EXTRA_BROADCAST_ORIENTATION, orientation));
        }

        void dismissKeyguardByFlag() {
            mContext.sendBroadcast(createIntentWithAction(ACTION_TRIGGER_BROADCAST)
                    .putExtra(EXTRA_DISMISS_KEYGUARD, true));
        }

        void dismissKeyguardByMethod() {
            mContext.sendBroadcast(createIntentWithAction(ACTION_TRIGGER_BROADCAST)
                    .putExtra(EXTRA_DISMISS_KEYGUARD_METHOD, true));
        }

        void expandPipWithAspectRatio(String extraNum, String extraDenom) {
            mContext.sendBroadcast(createIntentWithAction(ACTION_EXPAND_PIP)
                    .putExtra(EXTRA_SET_ASPECT_RATIO_WITH_DELAY_NUMERATOR, extraNum)
                    .putExtra(EXTRA_SET_ASPECT_RATIO_WITH_DELAY_DENOMINATOR, extraDenom));
        }

        void requestOrientationForPip(int orientation) {
            mContext.sendBroadcast(createIntentWithAction(ACTION_SET_REQUESTED_ORIENTATION)
                    .putExtra(EXTRA_PIP_ORIENTATION, String.valueOf(orientation)));
        }
    }

    /**
     * Helper class to launch / close test activity by instrumentation way.
     */
    protected class TestActivitySession<T extends Activity> implements AutoCloseable {
        private T mTestActivity;
        boolean mFinishAfterClose;
        private static final int ACTIVITY_LAUNCH_TIMEOUT = 10000;
        private static final int WAIT_SLICE = 50;

        void launchTestActivityOnDisplaySync(Class<T> activityClass, int displayId) {
            launchTestActivityOnDisplaySync(new Intent(mContext, activityClass), displayId);
        }

        void launchTestActivityOnDisplaySync(Intent intent, int displayId) {
            SystemUtil.runWithShellPermissionIdentity(() -> {
                final Bundle bundle = ActivityOptions.makeBasic()
                        .setLaunchDisplayId(displayId).toBundle();
                final ActivityMonitor monitor = getInstrumentation()
                        .addMonitor((String) null, null, false);
                mContext.startActivity(intent.addFlags(FLAG_ACTIVITY_NEW_TASK), bundle);
                // Wait for activity launch with timeout.
                mTestActivity = (T) monitor.waitForActivityWithTimeout(ACTIVITY_LAUNCH_TIMEOUT);
                assertNotNull(mTestActivity);
                // Check activity is launched and resumed.
                final ComponentName testActivityName = mTestActivity.getComponentName();
                waitAndAssertTopResumedActivity(testActivityName, displayId,
                        "Activity must be resumed");
            });
        }

        void finishCurrentActivityNoWait() {
            if (mTestActivity != null) {
                mTestActivity.finishAndRemoveTask();
                mTestActivity = null;
            }
        }

        void runOnMainSyncAndWait(Runnable runnable) {
            getInstrumentation().runOnMainSync(runnable);
            getInstrumentation().waitForIdleSync();
        }

        void runOnMainAndAssertWithTimeout(@NonNull BooleanSupplier condition, long timeoutMs,
                String message) {
            final AtomicBoolean result = new AtomicBoolean();
            final long expiredTime = System.currentTimeMillis() + timeoutMs;
            while (!result.get()) {
                if (System.currentTimeMillis() >= expiredTime) {
                    fail(message);
                }
                runOnMainSyncAndWait(() -> {
                    if (condition.getAsBoolean()) {
                        result.set(true);
                    }
                });
                SystemClock.sleep(WAIT_SLICE);
            }
        }

        T getActivity() {
            return mTestActivity;
        }

        @Override
        public void close() throws Exception {
            if (mTestActivity != null && mFinishAfterClose) {
                mTestActivity.finishAndRemoveTask();
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = getInstrumentation().getContext();
        mAm = mContext.getSystemService(ActivityManager.class);
        mAtm = mContext.getSystemService(ActivityTaskManager.class);

        pressWakeupButton();
        pressUnlockButton();
        pressHomeButton();
        removeStacksWithActivityTypes(ALL_ACTIVITY_TYPE_BUT_HOME);

        // Clear launch params for all test packages to make sure each test is run in a clean state.
        SystemUtil.callWithShellPermissionIdentity(mClearLaunchParamsCallable);
    }

    @After
    public void tearDown() throws Exception {
        // Synchronous execution of removeStacksWithActivityTypes() ensures that all activities but
        // home are cleaned up from the stack at the end of each test. Am force stop shell commands
        // might be asynchronous and could interrupt the stack cleanup process if executed first.
        removeStacksWithActivityTypes(ALL_ACTIVITY_TYPE_BUT_HOME);
        stopTestPackage(TEST_PACKAGE);
        stopTestPackage(SECOND_TEST_PACKAGE);
        stopTestPackage(THIRD_TEST_PACKAGE);
        pressHomeButton();

    }

    protected void moveTopActivityToPinnedStack(int stackId) {
        SystemUtil.runWithShellPermissionIdentity(
                () -> mAtm.moveTopActivityToPinnedStack(stackId, new Rect(0, 0, 500, 500))
        );
    }

    protected void startActivityOnDisplay(int displayId, ComponentName component) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);

        mContext.startActivity(new Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setComponent(component), options.toBundle());
    }

    protected boolean noHomeScreen() {
        try {
            return mContext.getResources().getBoolean(
                    Resources.getSystem().getIdentifier("config_noHomeScreen", "bool",
                            "android"));
        } catch (Resources.NotFoundException e) {
            // Assume there's a home screen.
            return false;
        }
    }

    private boolean getSupportsSystemDecorsOnSecondaryDisplays() {
        try {
            return mContext.getResources().getBoolean(
                    Resources.getSystem().getIdentifier(
                            "config_supportsSystemDecorsOnSecondaryDisplays", "bool", "android"));
        } catch (Resources.NotFoundException e) {
            // Assume this device support system decorations.
            return true;
        }
    }

    protected ComponentName getDefaultSecondaryHomeComponent() {
        int resId = Resources.getSystem().getIdentifier(
                "config_secondaryHomeComponent", "string", "android");
        return ComponentName.unflattenFromString(mContext.getResources().getString(resId));
    }

    protected void tapOnDisplay(int x, int y, int displayId) {
        final long downTime = SystemClock.uptimeMillis();
        injectMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, displayId);

        final long upTime = SystemClock.uptimeMillis();
        injectMotion(downTime, upTime, MotionEvent.ACTION_UP, x, y, displayId);
    }

    protected void tapOnStackCenter(ActivityManagerState.ActivityStack stack) {
        final Rect sideStackBounds = stack.getBounds();
        final int tapX = sideStackBounds.left + sideStackBounds.width() / 2;
        final int tapY = sideStackBounds.top + sideStackBounds.height() / 2;
        tapOnDisplay(tapX, tapY, stack.mDisplayId);
    }

    private static void injectMotion(long downTime, long eventTime, int action,
            int x, int y, int displayId) {
        final MotionEvent event = MotionEvent.obtain(downTime, eventTime, action,
                x, y, 0 /* metaState */);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        event.setDisplayId(displayId);
        getInstrumentation().getUiAutomation().injectInputEvent(event, true /* sync */);
    }

    protected void removeStacksWithActivityTypes(int... activityTypes) {
        SystemUtil.runWithShellPermissionIdentity(
                () -> mAtm.removeStacksWithActivityTypes(activityTypes));
        waitForIdle();
    }

    protected void removeStacksInWindowingModes(int... windowingModes) {
        SystemUtil.runWithShellPermissionIdentity(
                () -> mAtm.removeStacksInWindowingModes(windowingModes)
        );
        waitForIdle();
    }

    public static String executeShellCommand(String command) {
        log("Shell command: " + command);
        try {
            return SystemUtil.runShellCommand(getInstrumentation(), command);
        } catch (IOException e) {
            //bubble it up
            logE("Error running shell command: " + command);
            throw new RuntimeException(e);
        }
    }

    protected Bitmap takeScreenshot() {
        return getInstrumentation().getUiAutomation().takeScreenshot();
    }

    protected void launchActivity(final ComponentName activityName, final String... keyValuePairs) {
        launchActivityNoWait(activityName, keyValuePairs);
        mAmWmState.waitForValidState(activityName);
    }

    protected void launchActivityNoWait(final ComponentName activityName,
            final String... keyValuePairs) {
        executeShellCommand(getAmStartCmd(activityName, keyValuePairs));
    }

    protected void launchActivityInNewTask(final ComponentName activityName) {
        executeShellCommand(getAmStartCmdInNewTask(activityName));
        mAmWmState.waitForValidState(activityName);
    }

    private static void waitForIdle() {
        getInstrumentation().waitForIdleSync();
    }

    /** Returns the set of stack ids. */
    private HashSet<Integer> getStackIds() {
        mAmWmState.computeState(true);
        final List<ActivityManagerState.ActivityStack> stacks = mAmWmState.getAmState().getStacks();
        final HashSet<Integer> stackIds = new HashSet<>();
        for (ActivityManagerState.ActivityStack s : stacks) {
            stackIds.add(s.mStackId);
        }
        return stackIds;
    }

    /** Returns the stack that contains the provided task. */
    protected ActivityManagerState.ActivityStack getStackForTaskId(int taskId) {
        mAmWmState.computeState(true);
        final List<ActivityManagerState.ActivityStack> stacks = mAmWmState.getAmState().getStacks();
        for (ActivityManagerState.ActivityStack stack : stacks) {
            for (ActivityManagerState.ActivityTask task : stack.mTasks) {
                if (task.mTaskId == taskId) {
                    return stack;
                }
            }
        }
        return null;
    }

    protected void launchHomeActivity() {
        executeShellCommand(AM_START_HOME_ACTIVITY_COMMAND);
        mAmWmState.waitForHomeActivityVisible();
    }

    protected void launchActivity(ComponentName activityName, int windowingMode,
            final String... keyValuePairs) {
        executeShellCommand(getAmStartCmd(activityName, keyValuePairs)
                + " --windowingMode " + windowingMode);
        mAmWmState.waitForValidState(new WaitForValidActivityState.Builder(activityName)
                .setWindowingMode(windowingMode)
                .build());
    }

    protected void launchActivityOnDisplay(ComponentName activityName, int windowingMode,
            int displayId, final String... keyValuePairs) {
        executeShellCommand(getAmStartCmd(activityName, displayId, keyValuePairs)
                + " --windowingMode " + windowingMode);
        mAmWmState.waitForValidState(new WaitForValidActivityState.Builder(activityName)
                .setWindowingMode(windowingMode)
                .build());
    }

    protected void launchActivityOnDisplay(ComponentName activityName, int displayId,
            String... keyValuePairs) {
        launchActivityOnDisplayNoWait(activityName, displayId, keyValuePairs);
        mAmWmState.waitForValidState(activityName);
    }

    protected void launchActivityOnDisplayNoWait(ComponentName activityName, int displayId,
            String... keyValuePairs) {
        executeShellCommand(getAmStartCmd(activityName, displayId, keyValuePairs));
    }

    /**
     * Launches {@param activityName} into split-screen primary windowing mode and also makes
     * the recents activity visible to the side of it.
     * NOTE: Recents view may be combined with home screen on some devices, so using this to wait
     * for Recents only makes sense when {@link ActivityManagerState#isHomeRecentsComponent()} is
     * {@code false}.
     */
    protected void launchActivityInSplitScreenWithRecents(ComponentName activityName) {
        launchActivityInSplitScreenWithRecents(activityName, SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT);
    }

    protected void launchActivityInSplitScreenWithRecents(ComponentName activityName,
            int createMode) {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            launchActivity(activityName);
            final int taskId = mAmWmState.getAmState().getTaskByActivity(activityName).mTaskId;
            mAtm.setTaskWindowingModeSplitScreenPrimary(taskId, createMode,
                    true /* onTop */, false /* animate */,
                    null /* initialBounds */, true /* showRecents */);

            mAmWmState.waitForValidState(
                    new WaitForValidActivityState.Builder(activityName)
                            .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
                            .setActivityType(ACTIVITY_TYPE_STANDARD)
                            .build());
            mAmWmState.waitForRecentsActivityVisible();
        });
    }

    public void moveTaskToPrimarySplitScreen(int taskId) {
        moveTaskToPrimarySplitScreen(taskId, false /* showRecents */);
    }

    /**
     * Moves the device into split-screen with the specified task into the primary stack.
     * @param taskId             The id of the task to move into the primary stack.
     * @param showSideActivity   Whether to show the Recents activity (or a placeholder activity in
     *                           place of the Recents activity if home is the recents component)
     */
    public void moveTaskToPrimarySplitScreen(int taskId, boolean showSideActivity) {
        final boolean isHomeRecentsComponent = mAmWmState.getAmState().isHomeRecentsComponent();
        SystemUtil.runWithShellPermissionIdentity(() -> {
            mAtm.setTaskWindowingModeSplitScreenPrimary(taskId,
                    SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, true /* onTop */,
                    false /* animate */,
                    null /* initialBounds */, showSideActivity && !isHomeRecentsComponent);
            mAmWmState.waitForRecentsActivityVisible();

            if (isHomeRecentsComponent && showSideActivity) {
                // Launch Placeholder Side Activity
                final Activity sideActivity = mSideActivityRule.launchActivity(
                        new Intent());
                mAmWmState.waitForActivityState(sideActivity.getComponentName(), STATE_RESUMED);
            }
        });
    }

    /**
     * Launches {@param primaryActivity} into split-screen primary windowing mode
     * and {@param secondaryActivity} to the side in split-screen secondary windowing mode.
     */
    protected void launchActivitiesInSplitScreen(LaunchActivityBuilder primaryActivity,
            LaunchActivityBuilder secondaryActivity) {
        // Launch split-screen primary.
        primaryActivity
                .setUseInstrumentation()
                .setWaitForLaunched(true)
                .execute();

        final int taskId = mAmWmState.getAmState().getTaskByActivity(
                primaryActivity.mTargetActivity).mTaskId;
        moveTaskToPrimarySplitScreen(taskId);

        // Launch split-screen secondary
        // Recents become focused, so we can just launch new task in focused stack
        secondaryActivity
                .setUseInstrumentation()
                .setWaitForLaunched(true)
                .setNewTask(true)
                .setMultipleTask(true)
                .execute();
    }

    protected void setActivityTaskWindowingMode(ComponentName activityName, int windowingMode) {
        mAmWmState.computeState(activityName);
        final int taskId = mAmWmState.getAmState().getTaskByActivity(activityName).mTaskId;
        SystemUtil.runWithShellPermissionIdentity(
                () -> mAtm.setTaskWindowingMode(taskId, windowingMode, true /* toTop */));
        mAmWmState.waitForValidState(new WaitForValidActivityState.Builder(activityName)
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .setWindowingMode(windowingMode)
                .build());
    }

    protected void moveActivityToStack(ComponentName activityName, int stackId) {
        mAmWmState.computeState(activityName);
        final int taskId = mAmWmState.getAmState().getTaskByActivity(activityName).mTaskId;
        SystemUtil.runWithShellPermissionIdentity(
                () -> mAtm.moveTaskToStack(taskId, stackId, true));

        mAmWmState.waitForValidState(new WaitForValidActivityState.Builder(activityName)
                .setStackId(stackId)
                .build());
    }

    protected void resizeActivityTask(
            ComponentName activityName, int left, int top, int right, int bottom) {
        mAmWmState.computeState(activityName);
        final int taskId = mAmWmState.getAmState().getTaskByActivity(activityName).mTaskId;
        SystemUtil.runWithShellPermissionIdentity(
                () -> mAtm.resizeTask(taskId, new Rect(left, top, right, bottom)));
    }

    protected void resizeDockedStack(
            int stackWidth, int stackHeight, int taskWidth, int taskHeight) {
        SystemUtil.runWithShellPermissionIdentity(() ->
                mAtm.resizeDockedStack(new Rect(0, 0, stackWidth, stackHeight),
                        new Rect(0, 0, taskWidth, taskHeight)));
    }

    protected void resizeStack(int stackId, int stackLeft, int stackTop, int stackWidth,
            int stackHeight) {
        SystemUtil.runWithShellPermissionIdentity(() -> mAtm.resizeStack(stackId,
                new Rect(stackLeft, stackTop, stackWidth, stackHeight)));
    }

    protected void pressAppSwitchButtonAndWaitForRecents() {
        pressAppSwitchButton();
        mAmWmState.waitForRecentsActivityVisible();
        mAmWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);
    }

    // Utility method for debugging, not used directly here, but useful, so kept around.
    protected void printStacksAndTasks() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            final String output = mAtm.listAllStacks();
            for (String line : output.split("\\n")) {
                log(line);
            }
        });
    }

    protected boolean supportsVrMode() {
        return hasDeviceFeature(FEATURE_VR_MODE_HIGH_PERFORMANCE);
    }

    protected boolean supportsPip() {
        return hasDeviceFeature(FEATURE_PICTURE_IN_PICTURE)
                || PRETEND_DEVICE_SUPPORTS_PIP;
    }

    protected boolean supportsFreeform() {
        return hasDeviceFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                || PRETEND_DEVICE_SUPPORTS_FREEFORM;
    }

    /** Whether or not the device supports pin/pattern/password lock. */
    protected boolean supportsSecureLock() {
        return hasDeviceFeature(FEATURE_SECURE_LOCK_SCREEN);
    }

    /** Whether or not the device supports "swipe" lock. */
    protected boolean supportsInsecureLock() {
        return !hasDeviceFeature(FEATURE_LEANBACK)
                && !hasDeviceFeature(FEATURE_WATCH)
                && !hasDeviceFeature(FEATURE_EMBEDDED)
                && !hasDeviceFeature(FEATURE_AUTOMOTIVE)
                && getSupportsInsecureLockScreen();
    }

    protected boolean isWatch() {
        return hasDeviceFeature(FEATURE_WATCH);
    }

    protected boolean isTablet() {
        // Larger than approx 7" tablets
        return mContext.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    protected void waitAndAssertActivityState(ComponentName activityName,
            String state, String message) {
        mAmWmState.waitForActivityState(activityName, state);

        assertTrue(message, mAmWmState.getAmState().hasActivityState(activityName, state));
    }

    public void waitAndAssertTopResumedActivity(ComponentName activityName, int displayId,
            String message) {
        mAmWmState.waitForValidState(activityName);
        mAmWmState.waitForActivityState(activityName, STATE_RESUMED);
        final String activityClassName = getActivityName(activityName);
        mAmWmState.waitForWithAmState(state ->
                        activityClassName.equals(state.getFocusedActivity()),
                "Waiting for activity to be on top");

        mAmWmState.assertSanity();
        mAmWmState.assertFocusedActivity(message, activityName);
        assertTrue("Activity must be resumed",
                mAmWmState.getAmState().hasActivityState(activityName, STATE_RESUMED));
        final int frontStackId = mAmWmState.getAmState().getFrontStackId(displayId);
        ActivityManagerState.ActivityStack frontStackOnDisplay =
                mAmWmState.getAmState().getStackById(frontStackId);
        assertEquals("Resumed activity of front stack of the target display must match. " + message,
                activityClassName, frontStackOnDisplay.mResumedActivity);
        mAmWmState.assertFocusedStack("Top activity's stack must also be on top", frontStackId);
        mAmWmState.assertVisibility(activityName, true /* visible */);
    }
    
    // TODO: Switch to using a feature flag, when available.
    protected static boolean isUiModeLockedToVrHeadset() {
        final String output = runCommandAndPrintOutput("dumpsys uimode");

        Integer curUiMode = null;
        Boolean uiModeLocked = null;
        for (String line : output.split("\\n")) {
            line = line.trim();
            Matcher matcher = sCurrentUiModePattern.matcher(line);
            if (matcher.find()) {
                curUiMode = Integer.parseInt(matcher.group(1), 16);
            }
            matcher = sUiModeLockedPattern.matcher(line);
            if (matcher.find()) {
                uiModeLocked = matcher.group(1).equals("true");
            }
        }

        boolean uiModeLockedToVrHeadset = (curUiMode != null) && (uiModeLocked != null)
                && ((curUiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_VR_HEADSET) && uiModeLocked;

        if (uiModeLockedToVrHeadset) {
            log("UI mode is locked to VR headset");
        }

        return uiModeLockedToVrHeadset;
    }

    protected boolean supportsSplitScreenMultiWindow() {
        return ActivityTaskManager.supportsSplitScreenMultiWindow(mContext);
    }

    protected boolean hasHomeScreen() {
        if (sHasHomeScreen == null) {
            sHasHomeScreen = !noHomeScreen();
        }
        return sHasHomeScreen;
    }

    protected boolean supportsSystemDecorsOnSecondaryDisplays() {
        if (sSupportsSystemDecorsOnSecondaryDisplays == null) {
            sSupportsSystemDecorsOnSecondaryDisplays = getSupportsSystemDecorsOnSecondaryDisplays();
        }
        return sSupportsSystemDecorsOnSecondaryDisplays;
    }

    protected boolean getSupportsInsecureLockScreen() {
        if (sSupportsInsecureLockScreen == null) {
            try {
                sSupportsInsecureLockScreen = mContext.getResources().getBoolean(
                        Resources.getSystem().getIdentifier(
                                "config_supportsInsecureLockScreen", "bool", "android"));
            } catch (Resources.NotFoundException e) {
                sSupportsInsecureLockScreen = true;
            }
        }
        return sSupportsInsecureLockScreen;
    }

    /**
     * Rotation support is indicated by explicitly having both landscape and portrait
     * features or not listing either at all.
     */
    protected boolean supportsRotation() {
        final boolean supportsLandscape = hasDeviceFeature(FEATURE_SCREEN_LANDSCAPE);
        final boolean supportsPortrait = hasDeviceFeature(FEATURE_SCREEN_PORTRAIT);
        return (supportsLandscape && supportsPortrait)
                || (!supportsLandscape && !supportsPortrait);
    }

    protected boolean hasDeviceFeature(final String requiredFeature) {
        return mContext.getPackageManager()
                .hasSystemFeature(requiredFeature);
    }

    protected static boolean isDisplayOn(int displayId) {
        final DisplayManager displayManager = getInstrumentation()
                .getContext().getSystemService(DisplayManager.class);
        final Display display = displayManager.getDisplay(displayId);
        return display != null && display.getState() == Display.STATE_ON;
    }

    protected static boolean perDisplayFocusEnabled() {
        return getInstrumentation().getTargetContext().getResources()
                .getBoolean(android.R.bool.config_perDisplayFocusEnabled);
    }

    /**
     * Test @Rule class that disables screen doze settings before each test method running and
     * restoring to initial values after test method finished.
     */
    protected static class DisableScreenDozeRule implements TestRule {

        /** Copied from android.provider.Settings.Secure since these keys are hiden. */
        private static final String[] DOZE_SETTINGS = {
                "doze_enabled",
                "doze_always_on",
                "doze_pulse_on_pick_up",
                "doze_pulse_on_long_press",
                "doze_pulse_on_double_tap"
        };

        private String get(String key) {
            return executeShellCommand("settings get secure " + key).trim();
        }

        private void put(String key, String value) {
            executeShellCommand("settings put secure " + key + " " + value);
        }

        @Override
        public Statement apply(final Statement base, final Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    final Map<String, String> initialValues = new HashMap<>();
                    Arrays.stream(DOZE_SETTINGS).forEach(k -> initialValues.put(k, get(k)));
                    try {
                        Arrays.stream(DOZE_SETTINGS).forEach(k -> put(k, "0"));
                        base.evaluate();
                    } finally {
                        Arrays.stream(DOZE_SETTINGS).forEach(k -> put(k, initialValues.get(k)));
                    }
                }
            };
        }
    }

    /**
     * HomeActivitySession is used to replace the default home component, so that you can use
     * your preferred home for testing within the session. The original default home will be
     * restored automatically afterward.
     */
    protected class HomeActivitySession implements AutoCloseable {
        private PackageManager mPackageManager;
        private ComponentName mOrigHome;
        private ComponentName mSessionHome;

        public HomeActivitySession(ComponentName sessionHome) {
            mSessionHome = sessionHome;
            mPackageManager = mContext.getPackageManager();

            final Intent intent = new Intent(ACTION_MAIN);
            intent.addCategory(CATEGORY_HOME);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            final ResolveInfo resolveInfo =
                    mPackageManager.resolveActivity(intent, MATCH_DEFAULT_ONLY);
            if (resolveInfo != null) {
                mOrigHome = new ComponentName(resolveInfo.activityInfo.packageName,
                        resolveInfo.activityInfo.name);
            }

            SystemUtil.runWithShellPermissionIdentity(
                    () -> mPackageManager.setComponentEnabledSetting(mSessionHome,
                            COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP));
            setDefaultHome(mSessionHome);
        }

        @Override
        public void close() {
            SystemUtil.runWithShellPermissionIdentity(
                    () -> mPackageManager.setComponentEnabledSetting(mSessionHome,
                            COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP));
            if (mOrigHome != null) {
                setDefaultHome(mOrigHome);
            }
        }

        private void setDefaultHome(ComponentName componentName) {
            executeShellCommand("cmd package set-home-activity --user "
                    + android.os.Process.myUserHandle().getIdentifier() + " "
                    + componentName.flattenToString());
        }
    }

    protected class LockScreenSession implements AutoCloseable {
        private static final boolean DEBUG = false;

        private final boolean mIsLockDisabled;
        private boolean mLockCredentialSet;
        private boolean mRemoveActivitiesOnClose;
        private AmbientDisplayConfiguration mAmbientDisplayConfiguration;

        public static final int FLAG_REMOVE_ACTIVITIES_ON_CLOSE = 1;

        public LockScreenSession() {
            this(0 /* flags */);
        }

        public LockScreenSession(int flags) {
            mIsLockDisabled = isLockDisabled();
            mLockCredentialSet = false;
            // Enable lock screen (swipe) by default.
            setLockDisabled(false);
            if ((flags & FLAG_REMOVE_ACTIVITIES_ON_CLOSE) != 0) {
                mRemoveActivitiesOnClose = true;
            }
            mAmbientDisplayConfiguration = new AmbientDisplayConfiguration(mContext);
        }

        public LockScreenSession setLockCredential() {
            mLockCredentialSet = true;
            runCommandAndPrintOutput("locksettings set-pin " + LOCK_CREDENTIAL);
            return this;
        }

        public LockScreenSession enterAndConfirmLockCredential() {
            // Ensure focus will switch to default display. Meanwhile we cannot tap on center area,
            // which may tap on input credential area.
            tapOnDisplay(10, 10, DEFAULT_DISPLAY);

            waitForDeviceIdle(3000);
            SystemUtil.runWithShellPermissionIdentity(() ->
                    getInstrumentation().sendStringSync(LOCK_CREDENTIAL));
            pressEnterButton();
            return this;
        }

        private void removeLockCredential() {
            runCommandAndPrintOutput("locksettings clear --old " + LOCK_CREDENTIAL);
            mLockCredentialSet = false;
        }

        LockScreenSession disableLockScreen() {
            setLockDisabled(true);
            return this;
        }

        LockScreenSession sleepDevice() {
            pressSleepButton();
            // Not all device variants lock when we go to sleep, so we need to explicitly lock the
            // device. Note that pressSleepButton() above is redundant because the action also
            // puts the device to sleep, but kept around for clarity.
            getInstrumentation().getUiAutomation().performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            if (mAmbientDisplayConfiguration.alwaysOnEnabled(
                    android.os.Process.myUserHandle().getIdentifier())) {
                mAmWmState.waitForAodShowing();
            } else {
                for (int retry = 1; isDisplayOn(DEFAULT_DISPLAY) && retry <= 5; retry++) {
                    logAlways("***Waiting for display to turn off... retry=" + retry);
                    SystemClock.sleep(TimeUnit.SECONDS.toMillis(1));
                }
            }
            return this;
        }

        LockScreenSession wakeUpDevice() {
            pressWakeupButton();
            return this;
        }

        LockScreenSession unlockDevice() {
            pressUnlockButton();
            return this;
        }

        public LockScreenSession gotoKeyguard(ComponentName... showWhenLockedActivities) {
            if (DEBUG && isLockDisabled()) {
                logE("LockScreenSession.gotoKeyguard() is called without lock enabled.");
            }
            sleepDevice();
            wakeUpDevice();
            if (showWhenLockedActivities.length == 0) {
                mAmWmState.waitForKeyguardShowingAndNotOccluded();
            } else {
                mAmWmState.waitForValidState(showWhenLockedActivities);
            }
            return this;
        }

        @Override
        public void close() {
            if (mRemoveActivitiesOnClose) {
                removeStacksWithActivityTypes(ALL_ACTIVITY_TYPE_BUT_HOME);
            }

            setLockDisabled(mIsLockDisabled);
            if (mLockCredentialSet) {
                removeLockCredential();
            }

            // Dismiss active keyguard after credential is cleared, so keyguard doesn't ask for
            // the stale credential.
            // TODO (b/112015010) If keyguard is occluded, credential cannot be removed as expected.
            // LockScreenSession#close is always calls before stop all test activities,
            // which could cause keyguard stay at occluded after wakeup.
            // If Keyguard is occluded, press back key can close ShowWhenLocked activity.
            pressBackButton();

            // If device is unlocked, there might have ShowWhenLocked activity runs on,
            // use home key to clear all activity at foreground.
            pressHomeButton();
            sleepDevice();
            wakeUpDevice();
            unlockDevice();
        }

        /**
         * Returns whether the lock screen is disabled.
         *
         * @return true if the lock screen is disabled, false otherwise.
         */
        private boolean isLockDisabled() {
            final String isLockDisabled = runCommandAndPrintOutput(
                    "locksettings get-disabled").trim();
            return !"null".equals(isLockDisabled) && Boolean.parseBoolean(isLockDisabled);
        }

        /**
         * Disable the lock screen.
         *
         * @param lockDisabled true if should disable, false otherwise.
         */
        protected void setLockDisabled(boolean lockDisabled) {
            runCommandAndPrintOutput("locksettings set-disabled " + lockDisabled);
        }
    }

    /** Helper class to save, set & wait, and restore rotation related preferences. */
    protected class RotationSession extends SettingsSession<Integer> {
        private final SettingsSession<Integer> mUserRotation;
        private final HandlerThread mThread;
        private final Handler mRunnableHandler;
        private final SettingsObserver mRotationObserver;
        private int mPreviousDegree;

        public RotationSession() {
            // Save accelerometer_rotation preference.
            super(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                    Settings.System::getInt, Settings.System::putInt);
            mUserRotation = new SettingsSession<>(
                    Settings.System.getUriFor(Settings.System.USER_ROTATION),
                    Settings.System::getInt, Settings.System::putInt);

            mThread = new HandlerThread("Observer_Thread");
            mThread.start();
            mRunnableHandler = new Handler(mThread.getLooper());
            mRotationObserver = new SettingsObserver(mRunnableHandler);

            mPreviousDegree = mUserRotation.get();
            // Disable accelerometer_rotation.
            super.set(0);
        }

        @Override
        public void set(@NonNull Integer value) {
            // When the rotation is locked and the SystemUI receives the rotation becoming 0deg, it
            // will call freezeRotation to WMS, which will cause USER_ROTATION be set to zero again.
            // In order to prevent our test target from being overwritten by SystemUI during
            // rotation test, wait for the USER_ROTATION changed then continue testing.
            final boolean waitSystemUI = value == ROTATION_0 && mPreviousDegree != ROTATION_0;
            if (waitSystemUI) {
                mRotationObserver.observe();
            }
            mUserRotation.set(value);
            mPreviousDegree = value;

            if (waitSystemUI) {
                waitForRotationNotified();
            }
            // Wait for settling rotation.
            mAmWmState.waitForRotation(value);

            if (waitSystemUI) {
                mRotationObserver.stopObserver();
            }
        }

        @Override
        public void close() throws Exception {
            mThread.quitSafely();
            mUserRotation.close();
            // Restore accelerometer_rotation preference.
            super.close();
        }

        private void waitForRotationNotified() {
            for (int retry = 1; retry <= 5; retry++) {
                // There will receive USER_ROTATION changed twice because when the device rotates to
                // 0deg, RotationContextButton will also set ROTATION_0 again.
                if (mRotationObserver.count == 2) {
                    return;
                }
                logAlways("waitForRotationNotified retry=" + retry);
                SystemClock.sleep(500);
            }
            logE("waitForRotationNotified skip");
        }

        private class SettingsObserver extends ContentObserver {
            int count;

            SettingsObserver(Handler handler) { super(handler); }

            void observe() {
                count = 0;
                final ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.System.getUriFor(
                        Settings.System.USER_ROTATION), false, this);
            }

            void stopObserver() {
                count = 0;
                final ContentResolver resolver = mContext.getContentResolver();
                resolver.unregisterContentObserver(this);
            }

            @Override
            public void onChange(boolean selfChange) {
                count++;
            }
        }
    }

    /**
     * Returns whether the test device respects settings of locked user rotation mode.
     *
     * The method sets the locked user rotation settings to the rotation that rotates the display by
     * 180 degrees and checks if the actual display rotation changes after that.
     *
     * This is a necessary assumption check before leveraging user rotation mode to force display
     * rotation, because there is no requirement that an Android device that supports both
     * orientations needs to support user rotation mode.
     *
     * @param session   the rotation session used to set user rotation
     * @param displayId the display ID to check rotation against
     * @return {@code true} if test device respects settings of locked user rotation mode;
     * {@code false} if not.
     */
    protected boolean supportsLockedUserRotation(RotationSession session, int displayId)
            throws Exception {
        final int origRotation = getDeviceRotation(displayId);
        // Use the same orientation as target rotation to avoid affect of app-requested orientation.
        final int targetRotation = (origRotation + 2) % 4;
        session.set(targetRotation);
        final boolean result = (getDeviceRotation(displayId) == targetRotation);
        session.set(origRotation);
        return result;
    }

    protected int getDeviceRotation(int displayId) {
        final String displays = runCommandAndPrintOutput("dumpsys display displays").trim();
        Pattern pattern = Pattern.compile(
                "(mDisplayId=" + displayId + ")([\\s\\S]*?)(mOverrideDisplayInfo)(.*)"
                        + "(rotation)(\\s+)(\\d+)");
        Matcher matcher = pattern.matcher(displays);
        if (matcher.find()) {
            final String match = matcher.group(7);
            return Integer.parseInt(match);
        }

        return INVALID_DEVICE_ROTATION;
    }

    /** Empties the test journal so the following events won't be mixed-up with previous records. */
    protected void separateTestJournal() {
        TestJournalContainer.start();
    }

    protected static String runCommandAndPrintOutput(String command) {
        final String output = executeShellCommand(command);
        log(output);
        return output;
    }

    protected static class LogSeparator {
        private final String mUniqueString;

        private LogSeparator() {
            mUniqueString = UUID.randomUUID().toString();
        }

        @Override
        public String toString() {
            return mUniqueString;
        }
    }

    /**
     * Inserts a log separator so we can always find the starting point from where to evaluate
     * following logs.
     *
     * @return Unique log separator.
     */
    protected LogSeparator separateLogs() {
        final LogSeparator logSeparator = new LogSeparator();
        executeShellCommand("log -t " + LOG_SEPARATOR + " " + logSeparator);
        EventLog.writeEvent(EVENT_LOG_SEPARATOR_TAG, logSeparator.mUniqueString);
        return logSeparator;
    }

    protected static String[] getDeviceLogsForComponents(
            LogSeparator logSeparator, String... logTags) {
        String filters = LOG_SEPARATOR + ":I ";
        for (String component : logTags) {
            filters += component + ":I ";
        }
        final String[] result = executeShellCommand("logcat -v brief -d " + filters + " *:S")
                .split("\\n");
        if (logSeparator == null) {
            return result;
        }

        // Make sure that we only check logs after the separator.
        int i = 0;
        boolean lookingForSeparator = true;
        while (i < result.length && lookingForSeparator) {
            if (result[i].contains(logSeparator.toString())) {
                lookingForSeparator = false;
            }
            i++;
        }
        final String[] filteredResult = new String[result.length - i];
        for (int curPos = 0; i < result.length; curPos++, i++) {
            filteredResult[curPos] = result[i];
        }
        return filteredResult;
    }

    protected static List<Event> getEventLogsForComponents(LogSeparator logSeparator, int... tags) {
        List<Event> events = new ArrayList<>();

        int[] searchTags = Arrays.copyOf(tags, tags.length + 1);
        searchTags[searchTags.length - 1] = EVENT_LOG_SEPARATOR_TAG;

        try {
            EventLog.readEvents(searchTags, events);
        } catch (IOException e) {
            fail("Could not read from event log." + e);
        }

        for (Iterator<Event> itr = events.iterator(); itr.hasNext(); ) {
            Event event = itr.next();
            itr.remove();
            if (event.getTag() == EVENT_LOG_SEPARATOR_TAG &&
                    logSeparator.mUniqueString.equals(event.getData())) {
                break;
            }
        }
        return events;
    }

    protected boolean supportsMultiDisplay() {
        return mContext.getPackageManager().hasSystemFeature(
                FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS);
    }

    /**
     * Base helper class for retrying validator success.
     */
    private abstract static class RetryValidator {

        private static final int RETRY_LIMIT = 5;
        private static final long RETRY_INTERVAL = TimeUnit.SECONDS.toMillis(1);

        /**
         * @return Error string if validation is failed, null if everything is fine.
         **/
        @Nullable
        protected abstract String validate();

        /**
         * Executes {@link #validate()}. Retries {@link #RETRY_LIMIT} times with
         * {@link #RETRY_INTERVAL} interval.
         *
         * @param waitingMessage logging message while waiting validation.
         */
        void assertValidator(String waitingMessage) {
            String resultString = null;
            for (int retry = 1; retry <= RETRY_LIMIT; retry++) {
                resultString = validate();
                if (resultString == null) {
                    return;
                }
                logAlways(waitingMessage + ": " + resultString);
                SystemClock.sleep(RETRY_INTERVAL);
            }
            fail(resultString);
        }
    }

    static class CountSpec<T> {
        static final int DONT_CARE = Integer.MIN_VALUE;
        static final int EQUALS = 1;
        static final int GREATER_THAN = 2;
        static final int LESS_THAN = 3;

        final T mEvent;
        final int mRule;
        final int mCount;
        final String mMessage;

        CountSpec(T event, int rule, int count, String message) {
            mEvent = event;
            mRule = count == DONT_CARE ? DONT_CARE : rule;
            mCount = count;
            if (message != null) {
                mMessage = message;
            } else {
                switch (rule) {
                    case EQUALS:
                        mMessage = event + " + must equal to " + count;
                        break;
                    case GREATER_THAN:
                        mMessage = event + " + must be greater than " + count;
                        break;
                    case LESS_THAN:
                        mMessage = event + " + must be less than " + count;
                        break;
                    default:
                        mMessage = "Don't care";
                }
            }
        }

        /** @return {@code true} if the given value is satisfied the condition. */
        boolean validate(int value) {
            switch (mRule) {
                case DONT_CARE:
                    return true;
                case EQUALS:
                    return value == mCount;
                case GREATER_THAN:
                    return value > mCount;
                case LESS_THAN:
                    return value < mCount;
                default:
            }
            throw new RuntimeException("Unknown CountSpec rule");
        }
    }

    static <T> CountSpec<T> countSpec(T event, int rule, int count, String message) {
        return new CountSpec<>(event, rule, count, message);
    }

    static <T> CountSpec<T> countSpec(T event, int rule, int count) {
        return new CountSpec<>(event, rule, count, null /* message */);
    }

    static void assertLifecycleCounts(ComponentName activityName, String message,
            int createCount, int startCount, int resumeCount, int pauseCount, int stopCount,
            int destroyCount, int configChangeCount) {
        new ActivityLifecycleCounts(activityName).assertCountWithRetry(
                message,
                countSpec(ActivityCallback.ON_CREATE, CountSpec.EQUALS, createCount),
                countSpec(ActivityCallback.ON_START, CountSpec.EQUALS, startCount),
                countSpec(ActivityCallback.ON_RESUME, CountSpec.EQUALS, resumeCount),
                countSpec(ActivityCallback.ON_PAUSE, CountSpec.EQUALS, pauseCount),
                countSpec(ActivityCallback.ON_STOP, CountSpec.EQUALS, stopCount),
                countSpec(ActivityCallback.ON_DESTROY, CountSpec.EQUALS, destroyCount),
                countSpec(ActivityCallback.ON_CONFIGURATION_CHANGED, CountSpec.EQUALS,
                        configChangeCount));
    }

    static void assertLifecycleCounts(ComponentName activityName,
            int createCount, int startCount, int resumeCount, int pauseCount, int stopCount,
            int destroyCount, int configChangeCount) {
        assertLifecycleCounts(activityName, "Assert lifecycle of " + getLogTag(activityName),
                createCount, startCount, resumeCount, pauseCount, stopCount,
                destroyCount, configChangeCount);
    }

    static void assertSingleLaunch(ComponentName activityName) {
        assertLifecycleCounts(activityName,
                "***Waiting for activity create, start, and resume",
                1 /* createCount */, 1 /* startCount */, 1 /* resumeCount */,
                0 /* pauseCount */, 0 /* stopCount */, 0 /* destroyCount */,
                CountSpec.DONT_CARE /* configChangeCount */);
    }

    static void assertSingleLaunchAndStop(ComponentName activityName) {
        assertLifecycleCounts(activityName,
                "***Waiting for activity create, start, resume, pause, and stop",
                1 /* createCount */, 1 /* startCount */, 1 /* resumeCount */,
                1 /* pauseCount */, 1 /* stopCount */, 0 /* destroyCount */,
                CountSpec.DONT_CARE /* configChangeCount */);
    }

    static void assertSingleStartAndStop(ComponentName activityName) {
        assertLifecycleCounts(activityName,
                "***Waiting for activity start, resume, pause, and stop",
                0 /* createCount */, 1 /* startCount */, 1 /* resumeCount */,
                1 /* pauseCount */, 1 /* stopCount */, 0 /* destroyCount */,
                CountSpec.DONT_CARE /* configChangeCount */);
    }

    static void assertSingleStart(ComponentName activityName) {
        assertLifecycleCounts(activityName,
                "***Waiting for activity start and resume",
                0 /* createCount */, 1 /* startCount */, 1 /* resumeCount */,
                0 /* pauseCount */, 0 /* stopCount */, 0 /* destroyCount */,
                CountSpec.DONT_CARE /* configChangeCount */);
    }

    /** Assert the activity is either relaunched or received configuration changed. */
    static void assertActivityLifecycle(ComponentName activityName, boolean relaunched) {
        new RetryValidator() {

            @Nullable
            @Override
            protected String validate() {
                final String failedReason = checkActivityIsRelaunchedOrConfigurationChanged(
                        getActivityName(activityName),
                        TestJournalContainer.get(activityName).callbacks, relaunched);
                if (failedReason != null) {
                    return failedReason;
                }
                return null;
            }
        }.assertValidator("***Waiting for valid lifecycle state");
    }

    /** Assert the activity is either relaunched or received configuration changed. */
    static List<ActivityCallback> assertActivityLifecycle(ActivitySession activitySession,
            boolean relaunched) {
        final String name = activitySession.getName();
        final List<ActivityCallback> callbackHistory = activitySession.takeCallbackHistory();
        String failedReason = checkActivityIsRelaunchedOrConfigurationChanged(
                name, callbackHistory, relaunched);
        if (failedReason != null) {
            fail(failedReason);
        }
        return callbackHistory;
    }

    private static String checkActivityIsRelaunchedOrConfigurationChanged(String name,
            List<ActivityCallback> callbackHistory, boolean relaunched) {
        final ActivityLifecycleCounts lifecycles = new ActivityLifecycleCounts(callbackHistory);
        if (relaunched) {
            return lifecycles.validateCount(
                    countSpec(ActivityCallback.ON_DESTROY, CountSpec.GREATER_THAN, 0,
                            name + " must have been destroyed."),
                    countSpec(ActivityCallback.ON_CREATE, CountSpec.GREATER_THAN, 0,
                            name + " must have been (re)created."));
        }
        return lifecycles.validateCount(
                countSpec(ActivityCallback.ON_DESTROY, CountSpec.LESS_THAN, 1,
                        name + " must *NOT* have been destroyed."),
                countSpec(ActivityCallback.ON_CREATE, CountSpec.LESS_THAN, 1,
                        name + " must *NOT* have been (re)created."),
                countSpec(ActivityCallback.ON_CONFIGURATION_CHANGED, CountSpec.GREATER_THAN, 0,
                                name + " must have received configuration changed."));
    }

    static void assertRelaunchOrConfigChanged(ComponentName activityName, int numRelaunch,
            int numConfigChange) {
        new ActivityLifecycleCounts(activityName).assertCountWithRetry(
                "***Waiting for relaunch or config changed",
                countSpec(ActivityCallback.ON_DESTROY, CountSpec.EQUALS, numRelaunch),
                countSpec(ActivityCallback.ON_CREATE, CountSpec.EQUALS, numRelaunch),
                countSpec(ActivityCallback.ON_CONFIGURATION_CHANGED, CountSpec.EQUALS,
                        numConfigChange));
    }

    static void assertActivityDestroyed(ComponentName activityName) {
        new ActivityLifecycleCounts(activityName).assertCountWithRetry(
                "***Waiting for activity destroyed",
                countSpec(ActivityCallback.ON_DESTROY, CountSpec.EQUALS, 1),
                countSpec(ActivityCallback.ON_CREATE, CountSpec.EQUALS, 0),
                countSpec(ActivityCallback.ON_CONFIGURATION_CHANGED, CountSpec.EQUALS, 0));
    }

    private static final Pattern sCurrentUiModePattern = Pattern.compile("mCurUiMode=0x(\\d+)");
    private static final Pattern sUiModeLockedPattern =
            Pattern.compile("mUiModeLocked=(true|false)");

    @Nullable
    SizeInfo getLastReportedSizesForActivity(ComponentName activityName) {
        for (int retry = 1; retry <= 5; retry++) {
            final ConfigInfo result = TestJournalContainer.get(activityName).lastConfigInfo;
            if (result != null && result.sizeInfo != null) {
                return result.sizeInfo;
            }
            logAlways("***Waiting for sizes to be reported... retry=" + retry);
            SystemClock.sleep(1000);
        }
        logE("***Waiting for activity size failed: activityName=" + getActivityName(activityName));
        return null;
    }

    /** Check if a device has display cutout. */
    boolean hasDisplayCutout() {
        // Launch an activity to report cutout state
        separateTestJournal();
        launchActivity(BROADCAST_RECEIVER_ACTIVITY);

        // Read the logs to check if cutout is present
        final Boolean displayCutoutPresent = getCutoutStateForActivity(BROADCAST_RECEIVER_ACTIVITY);
        assertNotNull("The activity should report cutout state", displayCutoutPresent);

        // Finish activity
        mBroadcastActionTrigger.finishBroadcastReceiverActivity();
        mAmWmState.waitForWithAmState(
                (state) -> !state.containsActivity(BROADCAST_RECEIVER_ACTIVITY),
                "Waiting for activity to be removed");

        return displayCutoutPresent;
    }

    /**
     * Wait for activity to report cutout state in logs and return it. Will return {@code null}
     * after timeout.
     */
    @Nullable
    private Boolean getCutoutStateForActivity(ComponentName activityName) {
        final String logTag = getLogTag(activityName);
        for (int retry = 1; retry <= 5; retry++) {
            final Bundle extras = TestJournalContainer.get(activityName).extras;
            if (extras.containsKey(EXTRA_CUTOUT_EXISTS)) {
                return extras.getBoolean(EXTRA_CUTOUT_EXISTS);
            }
            logAlways("***Waiting for cutout state to be reported... retry=" + retry);
            SystemClock.sleep(1000);
        }
        logE("***Waiting for activity cutout state failed: activityName=" + logTag);
        return null;
    }

    /** Waits for at least one onMultiWindowModeChanged event. */
    ActivityLifecycleCounts waitForOnMultiWindowModeChanged(ComponentName activityName) {
        int retry = 1;
        ActivityLifecycleCounts result;
        do {
            result = new ActivityLifecycleCounts(activityName);
            if (result.getCount(ActivityCallback.ON_MULTI_WINDOW_MODE_CHANGED) >= 1) {
                return result;
            }
            logAlways("***waitForOnMultiWindowModeChanged... retry=" + retry);
            SystemClock.sleep(TimeUnit.SECONDS.toMillis(1));
        } while (retry++ <= 5);
        return result;
    }

    static class ActivityLifecycleCounts {
        final int[] mCounts = new int[ActivityCallback.SIZE];
        final int[] mLastIndexes = new int[ActivityCallback.SIZE];
        final List<ActivityCallback> mCallbackHistory;

        ActivityLifecycleCounts(ComponentName componentName) {
            this(TestJournalContainer.get(componentName).callbacks);
        }

        ActivityLifecycleCounts(List<ActivityCallback> callbacks) {
            mCallbackHistory = callbacks;
            for (int i = 0; i < callbacks.size(); i++) {
                final ActivityCallback callback = callbacks.get(i);
                final int ordinal = callback.ordinal();
                mCounts[ordinal]++;
                mLastIndexes[ordinal] = i;
            }
        }

        int getCount(ActivityCallback callback) {
            return mCounts[callback.ordinal()];
        }

        int getLastIndex(ActivityCallback callback) {
            return mLastIndexes[callback.ordinal()];
        }

        @SafeVarargs
        final void assertCountWithRetry(String message, CountSpec<ActivityCallback>... countSpecs) {
            new RetryValidator() {
                @Override
                protected String validate() {
                    return validateCount(countSpecs);
                }
            }.assertValidator(message);
        }

        @SafeVarargs
        final String validateCount(CountSpec<ActivityCallback>... countSpecs) {
            ArrayList<String> failedReasons = null;
            for (CountSpec<ActivityCallback> spec : countSpecs) {
                final int realCount = mCounts[spec.mEvent.ordinal()];
                if (!spec.validate(realCount)) {
                    if (failedReasons == null) {
                        failedReasons = new ArrayList<>();
                    }
                    failedReasons.add(spec.mMessage);
                }
            }
            return failedReasons == null ? null : String.join("\n", failedReasons);
        }
    }

    protected void stopTestPackage(final String packageName) {
        SystemUtil.runWithShellPermissionIdentity(() -> mAm.forceStopPackage(packageName));
    }

    protected LaunchActivityBuilder getLaunchActivityBuilder() {
        return new LaunchActivityBuilder(mAmWmState);
    }

    protected static class LaunchActivityBuilder implements LaunchProxy {
        private final ActivityAndWindowManagersState mAmWmState;

        // The activity to be launched
        private ComponentName mTargetActivity = TEST_ACTIVITY;
        private boolean mUseApplicationContext;
        private boolean mToSide;
        private boolean mRandomData;
        private boolean mNewTask;
        private boolean mMultipleTask;
        private boolean mAllowMultipleInstances = true;
        private int mDisplayId = INVALID_DISPLAY;
        private int mActivityType = ACTIVITY_TYPE_UNDEFINED;
        // A proxy activity that launches other activities including mTargetActivityName
        private ComponentName mLaunchingActivity = LAUNCHING_ACTIVITY;
        private boolean mReorderToFront;
        private boolean mWaitForLaunched;
        private boolean mSuppressExceptions;
        private boolean mWithShellPermission;
        // Use of the following variables indicates that a broadcast receiver should be used instead
        // of a launching activity;
        private ComponentName mBroadcastReceiver;
        private String mBroadcastReceiverAction;
        private int mIntentFlags;
        private Bundle mExtras;
        private LaunchInjector mLaunchInjector;

        private enum LauncherType {
            INSTRUMENTATION, LAUNCHING_ACTIVITY, BROADCAST_RECEIVER
        }

        private LauncherType mLauncherType = LauncherType.LAUNCHING_ACTIVITY;

        public LaunchActivityBuilder(ActivityAndWindowManagersState amWmState) {
            mAmWmState = amWmState;
            mWaitForLaunched = true;
            mWithShellPermission = true;
        }

        public LaunchActivityBuilder setToSide(boolean toSide) {
            mToSide = toSide;
            return this;
        }

        public LaunchActivityBuilder setRandomData(boolean randomData) {
            mRandomData = randomData;
            return this;
        }

        public LaunchActivityBuilder setNewTask(boolean newTask) {
            mNewTask = newTask;
            return this;
        }

        public LaunchActivityBuilder setMultipleTask(boolean multipleTask) {
            mMultipleTask = multipleTask;
            return this;
        }

        public LaunchActivityBuilder allowMultipleInstances(boolean allowMultipleInstances) {
            mAllowMultipleInstances = allowMultipleInstances;
            return this;
        }

        public LaunchActivityBuilder setReorderToFront(boolean reorderToFront) {
            mReorderToFront = reorderToFront;
            return this;
        }

        public LaunchActivityBuilder setUseApplicationContext(boolean useApplicationContext) {
            mUseApplicationContext = useApplicationContext;
            return this;
        }

        public ComponentName getTargetActivity() {
            return mTargetActivity;
        }

        public boolean isTargetActivityTranslucent() {
            return mAmWmState.getAmState().isActivityTranslucent(mTargetActivity);
        }

        public LaunchActivityBuilder setTargetActivity(ComponentName targetActivity) {
            mTargetActivity = targetActivity;
            return this;
        }

        public LaunchActivityBuilder setDisplayId(int id) {
            mDisplayId = id;
            return this;
        }

        public LaunchActivityBuilder setActivityType(int type) {
            mActivityType = type;
            return this;
        }

        public LaunchActivityBuilder setLaunchingActivity(ComponentName launchingActivity) {
            mLaunchingActivity = launchingActivity;
            mLauncherType = LauncherType.LAUNCHING_ACTIVITY;
            return this;
        }

        public LaunchActivityBuilder setWaitForLaunched(boolean shouldWait) {
            mWaitForLaunched = shouldWait;
            return this;
        }

        /** Use broadcast receiver as a launchpad for activities. */
        public LaunchActivityBuilder setUseBroadcastReceiver(final ComponentName broadcastReceiver,
                final String broadcastAction) {
            mBroadcastReceiver = broadcastReceiver;
            mBroadcastReceiverAction = broadcastAction;
            mLauncherType = LauncherType.BROADCAST_RECEIVER;
            return this;
        }

        /** Use {@link android.app.Instrumentation} as a launchpad for activities. */
        public LaunchActivityBuilder setUseInstrumentation() {
            mLauncherType = LauncherType.INSTRUMENTATION;
            // Calling startActivity() from outside of an Activity context requires the
            // FLAG_ACTIVITY_NEW_TASK flag.
            setNewTask(true);
            return this;
        }

        public LaunchActivityBuilder setSuppressExceptions(boolean suppress) {
            mSuppressExceptions = suppress;
            return this;
        }

        public LaunchActivityBuilder setWithShellPermission(boolean withShellPermission) {
            mWithShellPermission = withShellPermission;
            return this;
        }

        @Override
        public boolean shouldWaitForLaunched() {
            return mWaitForLaunched;
        }

        public LaunchActivityBuilder setIntentFlags(int flags) {
            mIntentFlags = flags;
            return this;
        }

        public LaunchActivityBuilder setIntentExtra(Consumer<Bundle> extrasConsumer) {
            if (extrasConsumer != null) {
                mExtras = new Bundle();
                extrasConsumer.accept(mExtras);
            }
            return this;
        }

        @Override
        public Bundle getExtras() {
            return mExtras;
        }

        @Override
        public void setLaunchInjector(LaunchInjector injector) {
            mLaunchInjector = injector;
        }

        @Override
        public void execute() {
            switch (mLauncherType) {
                case INSTRUMENTATION:
                    if (mWithShellPermission) {
                        SystemUtil.runWithShellPermissionIdentity(this::launchUsingInstrumentation);
                    } else {
                        launchUsingInstrumentation();
                    }
                    break;
                case LAUNCHING_ACTIVITY:
                case BROADCAST_RECEIVER:
                    launchUsingShellCommand();
            }

            if (mWaitForLaunched) {
                mAmWmState.waitForValidState(mTargetActivity);
            }
        }

        /** Launch an activity using instrumentation. */
        private void launchUsingInstrumentation() {
            final Bundle b = new Bundle();
            b.putBoolean(KEY_USE_INSTRUMENTATION, true);
            b.putBoolean(KEY_LAUNCH_ACTIVITY, true);
            b.putBoolean(KEY_LAUNCH_TO_SIDE, mToSide);
            b.putBoolean(KEY_RANDOM_DATA, mRandomData);
            b.putBoolean(KEY_NEW_TASK, mNewTask);
            b.putBoolean(KEY_MULTIPLE_TASK, mMultipleTask);
            b.putBoolean(KEY_MULTIPLE_INSTANCES, mAllowMultipleInstances);
            b.putBoolean(KEY_REORDER_TO_FRONT, mReorderToFront);
            b.putInt(KEY_DISPLAY_ID, mDisplayId);
            b.putInt(KEY_ACTIVITY_TYPE, mActivityType);
            b.putBoolean(KEY_USE_APPLICATION_CONTEXT, mUseApplicationContext);
            b.putString(KEY_TARGET_COMPONENT, getActivityName(mTargetActivity));
            b.putBoolean(KEY_SUPPRESS_EXCEPTIONS, mSuppressExceptions);
            b.putInt(KEY_INTENT_FLAGS, mIntentFlags);
            b.putBundle(KEY_INTENT_EXTRAS, getExtras());
            final Context context = getInstrumentation().getContext();
            launchActivityFromExtras(context, b, mLaunchInjector);
        }

        /** Build and execute a shell command to launch an activity. */
        private void launchUsingShellCommand() {
            StringBuilder commandBuilder = new StringBuilder();
            if (mBroadcastReceiver != null && mBroadcastReceiverAction != null) {
                // Use broadcast receiver to launch the target.
                commandBuilder.append("am broadcast -a ").append(mBroadcastReceiverAction)
                        .append(" -p ").append(mBroadcastReceiver.getPackageName())
                        // Include stopped packages
                        .append(" -f 0x00000020");
            } else {
                // Use launching activity to launch the target.
                commandBuilder.append(getAmStartCmd(mLaunchingActivity))
                        .append(" -f 0x20000020");
            }

            // Add a flag to ensure we actually mean to launch an activity.
            commandBuilder.append(" --ez " + KEY_LAUNCH_ACTIVITY + " true");

            if (mToSide) {
                commandBuilder.append(" --ez " + KEY_LAUNCH_TO_SIDE + " true");
            }
            if (mRandomData) {
                commandBuilder.append(" --ez " + KEY_RANDOM_DATA + " true");
            }
            if (mNewTask) {
                commandBuilder.append(" --ez " + KEY_NEW_TASK + " true");
            }
            if (mMultipleTask) {
                commandBuilder.append(" --ez " + KEY_MULTIPLE_TASK + " true");
            }
            if (mAllowMultipleInstances) {
                commandBuilder.append(" --ez " + KEY_MULTIPLE_INSTANCES + " true");
            }
            if (mReorderToFront) {
                commandBuilder.append(" --ez " + KEY_REORDER_TO_FRONT + " true");
            }
            if (mDisplayId != INVALID_DISPLAY) {
                commandBuilder.append(" --ei " + KEY_DISPLAY_ID + " ").append(mDisplayId);
            }
            if (mActivityType != ACTIVITY_TYPE_UNDEFINED) {
                commandBuilder.append(" --ei " + KEY_ACTIVITY_TYPE + " ").append(mActivityType);
            }

            if (mUseApplicationContext) {
                commandBuilder.append(" --ez " + KEY_USE_APPLICATION_CONTEXT + " true");
            }

            if (mTargetActivity != null) {
                // {@link ActivityLauncher} parses this extra string by
                // {@link ComponentName#unflattenFromString(String)}.
                commandBuilder.append(" --es " + KEY_TARGET_COMPONENT + " ")
                        .append(getActivityName(mTargetActivity));
            }

            if (mSuppressExceptions) {
                commandBuilder.append(" --ez " + KEY_SUPPRESS_EXCEPTIONS + " true");
            }

            if (mIntentFlags != 0) {
                commandBuilder.append(" --ei " + KEY_INTENT_FLAGS + " ").append(mIntentFlags);
            }

            if (mLaunchInjector != null) {
                mLaunchInjector.setupShellCommand(commandBuilder);
            }
            executeShellCommand(commandBuilder.toString());
        }
    }

    // Activity used in place of recents when home is the recents component.
    public static class SideActivity extends Activity {
    }
}
