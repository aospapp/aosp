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

import static android.server.wm.ActivityManagerState.STATE_INITIALIZING;
import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.UiDeviceUtils.pressHomeButton;
import static android.server.wm.UiDeviceUtils.pressUnlockButton;
import static android.server.wm.UiDeviceUtils.pressWakeupButton;
import static android.server.wm.backgroundactivity.appa.Components.APP_A_BACKGROUND_ACTIVITY;
import static android.server.wm.backgroundactivity.appa.Components.APP_A_FOREGROUND_ACTIVITY;
import static android.server.wm.backgroundactivity.appa.Components.APP_A_SECOND_BACKGROUND_ACTIVITY;
import static android.server.wm.backgroundactivity.appa.Components.APP_A_SEND_PENDING_INTENT_RECEIVER;
import static android.server.wm.backgroundactivity.appa.Components.APP_A_SIMPLE_ADMIN_RECEIVER;
import static android.server.wm.backgroundactivity.appa.Components.APP_A_START_ACTIVITY_RECEIVER;
import static android.server.wm.backgroundactivity.appa.Components.ForegroundActivity.LAUNCH_BACKGROUND_ACTIVITY_EXTRA;
import static android.server.wm.backgroundactivity.appa.Components.ForegroundActivity.LAUNCH_SECOND_BACKGROUND_ACTIVITY_EXTRA;
import static android.server.wm.backgroundactivity.appa.Components.ForegroundActivity.RELAUNCH_FOREGROUND_ACTIVITY_EXTRA;
import static android.server.wm.backgroundactivity.appa.Components.ForegroundActivity.START_ACTIVITY_FROM_FG_ACTIVITY_DELAY_MS_EXTRA;
import static android.server.wm.backgroundactivity.appa.Components.ForegroundActivity.START_ACTIVITY_FROM_FG_ACTIVITY_NEW_TASK_EXTRA;
import static android.server.wm.backgroundactivity.appa.Components.SendPendingIntentReceiver.IS_BROADCAST_EXTRA;
import static android.server.wm.backgroundactivity.appa.Components.StartBackgroundActivityReceiver.START_ACTIVITY_DELAY_MS_EXTRA;
import static android.server.wm.backgroundactivity.appb.Components.APP_B_FOREGROUND_ACTIVITY;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.List;

/**
 * This class covers all test cases for starting/blocking background activities.
 * As instrumentation tests started by shell are whitelisted to allow starting background activity,
 * tests can't be done in this app alone.
 * Hence, there are 2 extra apps, appA and appB. This class will send commands to appA/appB, for
 * example, send a broadcast to appA and ask it to start a background activity, and we will monitor
 * the result and see if it starts an activity successfully.
 */
@Presubmit
public class BackgroundActivityLaunchTest extends ActivityManagerTestBase {

    private static final int ACTIVITY_FOCUS_TIMEOUT_MS = 3000;

    private static final String TEST_PACKAGE_APP_A = "android.server.wm.backgroundactivity.appa";
    private static final String TEST_PACKAGE_APP_B = "android.server.wm.backgroundactivity.appb";

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mAm = mContext.getSystemService(ActivityManager.class);
        mAtm = mContext.getSystemService(ActivityTaskManager.class);

        pressWakeupButton();
        pressUnlockButton();
        removeStacksWithActivityTypes(ALL_ACTIVITY_TYPE_BUT_HOME);
        assertNull(mAmWmState.getAmState().getTaskByActivity(APP_A_BACKGROUND_ACTIVITY));
        assertNull(mAmWmState.getAmState().getTaskByActivity(APP_A_FOREGROUND_ACTIVITY));
        assertNull(mAmWmState.getAmState().getTaskByActivity(APP_B_FOREGROUND_ACTIVITY));

        runShellCommand("cmd deviceidle tempwhitelist -d 100000 "
                + APP_A_FOREGROUND_ACTIVITY.getPackageName());
        runShellCommand("cmd deviceidle tempwhitelist -d 100000 "
                + APP_B_FOREGROUND_ACTIVITY.getPackageName());
    }

    @After
    public void tearDown() throws Exception {
        stopTestPackage(TEST_PACKAGE_APP_A);
        stopTestPackage(TEST_PACKAGE_APP_B);
        pressHomeButton();
        mAmWmState.waitForHomeActivityVisible();
        runWithShellPermissionIdentity(() -> {
            runShellCommand("dpm remove-active-admin --user current "
                    + APP_A_SIMPLE_ADMIN_RECEIVER.flattenToString());
        });
        // TODO(b/130169434): Remove it when app switch protection bug is fixed
        SystemClock.sleep(5000);
    }

    @Test
    public void testBackgroundActivityBlocked() throws Exception {
        // Start AppA background activity and blocked
        Intent intent = new Intent();
        intent.setComponent(APP_A_START_ACTIVITY_RECEIVER);
        mContext.sendBroadcast(intent);
        boolean result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStack(null, APP_A_BACKGROUND_ACTIVITY);

        // TODO(b/137134312): Bring this back once the stacks leakage issue is fixed
        // Make sure aborting activity starts won't have any empty task/stack leaks.
        // List<ActivityManagerState.ActivityStack> stacks = mAmWmState.getAmState().getStacks();
        // for (ActivityManagerState.ActivityStack stack : stacks) {
        //     assertThat(stack.getTopTask()).isNotNull();
        //     List<ActivityManagerState.ActivityTask> tasks = stack.getTasks();
        //     for (ActivityManagerState.ActivityTask task : tasks) {
        //         assertThat(task.getActivities().size()).isGreaterThan(0);
        //     }
        // }
    }

    @Test
    public void testBackgroundActivityNotBlockedWhenForegroundActivityExists() throws Exception {
        // Start AppA foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_A_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_A_FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground activity", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);

        // Start AppA background activity successfully as there's a foreground activity
        intent = new Intent();
        intent.setComponent(APP_A_START_ACTIVITY_RECEIVER);
        mContext.sendBroadcast(intent);
        result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);
        assertTaskStack(new ComponentName[]{APP_A_BACKGROUND_ACTIVITY}, APP_A_BACKGROUND_ACTIVITY);
    }

    @Test
    public void testActivityNotBlockedWhenForegroundActivityLaunch() throws Exception {
        // Start foreground activity, and foreground activity able to launch background activity
        // successfully
        Intent intent = new Intent();
        intent.setComponent(APP_A_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(LAUNCH_BACKGROUND_ACTIVITY_EXTRA, true);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStack(new ComponentName[]{APP_A_BACKGROUND_ACTIVITY, APP_A_FOREGROUND_ACTIVITY},
                APP_A_FOREGROUND_ACTIVITY);
    }

    @Ignore // test temporarily disabled due to bg activity start grace period introduction
    @Test
    public void testActivityNotBlockedwhenForegroundActivityLaunchInSameTask() throws Exception {
        // Start foreground activity, and foreground activity able to launch background activity
        // successfully
        Intent intent = new Intent();
        intent.setComponent(APP_A_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(LAUNCH_BACKGROUND_ACTIVITY_EXTRA, true);
        intent.putExtra(START_ACTIVITY_FROM_FG_ACTIVITY_DELAY_MS_EXTRA, 7000);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(ACTIVITY_FOCUS_TIMEOUT_MS,
                APP_A_FOREGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);

        // The foreground activity will be paused but will attempt to restart itself in onPause()
        pressHomeButton();
        mAmWmState.waitForHomeActivityVisible();

        // Any activity launch will be blocked for 5s because of app switching protection.
        SystemClock.sleep(7000);

        result = waitForActivityFocused(APP_A_FOREGROUND_ACTIVITY);
        assertFalse("Previously foreground Activity should not be able to relaunch itself", result);
        result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertFalse("Previously foreground Activity should not be able to relaunch itself", result);
        assertTaskStack(new ComponentName[]{APP_A_BACKGROUND_ACTIVITY, APP_A_FOREGROUND_ACTIVITY},
                APP_A_FOREGROUND_ACTIVITY);
    }

    @Ignore // test temporarily disabled due to bg activity start grace period introduction
    @Test
    public void testActivityNotBlockedWhenForegroundActivityLaunchInDifferentTask()
            throws Exception {
        // Start foreground activity, and foreground activity able to launch background activity
        // successfully
        Intent intent = new Intent();
        intent.setComponent(APP_A_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(LAUNCH_BACKGROUND_ACTIVITY_EXTRA, true);
        intent.putExtra(START_ACTIVITY_FROM_FG_ACTIVITY_DELAY_MS_EXTRA, 7000);
        intent.putExtra(START_ACTIVITY_FROM_FG_ACTIVITY_NEW_TASK_EXTRA, true);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(ACTIVITY_FOCUS_TIMEOUT_MS,
                APP_A_FOREGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);

        // The foreground activity will be paused but will attempt to restart itself in onPause()
        pressHomeButton();
        mAmWmState.waitForHomeActivityVisible();

        // Any activity launch will be blocked for 5s because of app switching protection.
        SystemClock.sleep(7000);

        result = waitForActivityFocused(APP_A_FOREGROUND_ACTIVITY);
        assertFalse("Previously foreground Activity should not be able to relaunch itself", result);
        result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertFalse("Previously foreground Activity should not be able to relaunch itself", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);
        assertTaskStack(null, APP_A_BACKGROUND_ACTIVITY);
    }

    @Ignore // test temporarily disabled due to bg activity start grace period introduction
    @Test
    @FlakyTest(bugId = 130800326)
    public void testActivityBlockedWhenForegroundActivityRestartsItself() throws Exception {
        // Start AppA foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_A_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(RELAUNCH_FOREGROUND_ACTIVITY_EXTRA, true);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_A_FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground activity", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);

        // The foreground activity will be paused but will attempt to restart itself in onPause()
        pressHomeButton();
        mAmWmState.waitForHomeActivityVisible();

        // Any activity launch will be blocked for 5s because of app switching protection.
        SystemClock.sleep(5000);

        result = waitForActivityFocused(APP_A_FOREGROUND_ACTIVITY);
        assertFalse("Previously foreground Activity should not be able to relaunch itself", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);
    }

    @Test
    public void testSecondActivityNotBlockedWhenForegroundActivityLaunch() throws Exception {
        // Start AppA foreground activity, which will immediately launch one activity
        // and then the second.
        Intent intent = new Intent();
        intent.setComponent(APP_A_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(LAUNCH_BACKGROUND_ACTIVITY_EXTRA, true);
        intent.putExtra(LAUNCH_SECOND_BACKGROUND_ACTIVITY_EXTRA, true);
        mContext.startActivity(intent);

        boolean result = waitForActivityFocused(APP_A_SECOND_BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch second background activity", result);

        waitAndAssertActivityState(APP_A_BACKGROUND_ACTIVITY, STATE_INITIALIZING,
                "First activity should have been created");
        assertTaskStack(
                new ComponentName[]{APP_A_SECOND_BACKGROUND_ACTIVITY, APP_A_BACKGROUND_ACTIVITY,
                        APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentActivityBlocked() throws Exception {
        // Cannot start activity by pending intent, as both appA and appB are in background
        sendPendingIntentActivity();
        boolean result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStack(null, APP_A_BACKGROUND_ACTIVITY);
    }

    @Test
    @FlakyTest(bugId = 130800326)
    public void testPendingIntentActivityNotBlocked_appAIsForeground() throws Exception {
        // Start AppA foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_A_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_A_FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentActivity();
        result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStack(new ComponentName[]{APP_A_FOREGROUND_ACTIVITY}, APP_A_FOREGROUND_ACTIVITY);
        assertTaskStack(new ComponentName[]{APP_A_BACKGROUND_ACTIVITY}, APP_A_BACKGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastActivityNotBlocked_appBIsForeground() throws Exception {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_B_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_B_FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStack(new ComponentName[]{APP_B_FOREGROUND_ACTIVITY}, APP_B_FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentActivity();
        result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStack(new ComponentName[]{APP_A_BACKGROUND_ACTIVITY}, APP_A_BACKGROUND_ACTIVITY);
        assertTaskStack(new ComponentName[]{APP_B_FOREGROUND_ACTIVITY}, APP_B_FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastTimeout_noDelay() throws Exception {
        assertPendingIntentBroadcastTimeoutTest(0, true);
    }

    @Test
    public void testPendingIntentBroadcastTimeout_delay1s() throws Exception {
        assertPendingIntentBroadcastTimeoutTest(1000, true);
    }

    @Test
    public void testPendingIntentBroadcastTimeout_delay12s() throws Exception {
        assertPendingIntentBroadcastTimeoutTest(12000, false);
    }

    @Test
    public void testPendingIntentBroadcast_appBIsBackground() throws Exception {
        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentBroadcast(0);
        boolean result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStack(null, APP_A_BACKGROUND_ACTIVITY);
    }

    @Test
    public void testDeviceOwner() throws Exception {
        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            return;
        }

        String cmdResult = runShellCommand("dpm set-device-owner --user cur "
                + APP_A_SIMPLE_ADMIN_RECEIVER.flattenToString());
        assertThat(cmdResult).contains("Success");
        Intent intent = new Intent();
        intent.setComponent(APP_A_START_ACTIVITY_RECEIVER);
        mContext.sendBroadcast(intent);
        boolean result = waitForActivityFocused(APP_A_BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStack(new ComponentName[]{APP_A_BACKGROUND_ACTIVITY}, APP_A_BACKGROUND_ACTIVITY);
    }

    private void assertTaskStack(ComponentName[] expectedComponents,
            ComponentName sourceComponent) {
        if (expectedComponents == null) {
            assertNull(mAmWmState.getAmState().getTaskByActivity(sourceComponent));
            return;
        }
        List<ActivityManagerState.Activity> actual = mAmWmState.getAmState().getTaskByActivity(
                sourceComponent).mActivities;
        assertEquals(expectedComponents.length, actual.size());
        int size = expectedComponents.length;
        for (int i = 0; i < size; i++) {
            assertEquals(expectedComponents[i].flattenToShortString(), actual.get(i).getName());
        }
    }

    private void assertPendingIntentBroadcastTimeoutTest(int delayMs, boolean expectedResult) {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_B_FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_B_FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStack(new ComponentName[]{APP_B_FOREGROUND_ACTIVITY}, APP_B_FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentBroadcast(delayMs);
        result = waitForActivityFocused(ACTIVITY_FOCUS_TIMEOUT_MS + delayMs,
                APP_A_BACKGROUND_ACTIVITY);
        assertEquals(expectedResult, result);
        if (expectedResult) {
            assertTaskStack(new ComponentName[]{APP_A_BACKGROUND_ACTIVITY},
                    APP_A_BACKGROUND_ACTIVITY);
        } else {
            assertTaskStack(null, APP_A_BACKGROUND_ACTIVITY);
        }
    }

    private boolean waitForActivityFocused(ComponentName componentName) {
        return waitForActivityFocused(ACTIVITY_FOCUS_TIMEOUT_MS, componentName);
    }

    // Return true if the activity is shown before timeout
    private boolean waitForActivityFocused(int timeoutMs, ComponentName componentName) {
        long endTime = System.currentTimeMillis() + timeoutMs;
        while (endTime > System.currentTimeMillis()) {
            mAmWmState.getAmState().computeState();
            mAmWmState.getWmState().computeState();
            if (mAmWmState.getAmState().hasActivityState(componentName, STATE_RESUMED)) {
                SystemClock.sleep(200);
                mAmWmState.getAmState().computeState();
                mAmWmState.getWmState().computeState();
                break;
            }
            SystemClock.sleep(200);
            mAmWmState.getAmState().computeState();
            mAmWmState.getWmState().computeState();
        }
        return getActivityName(componentName).equals(mAmWmState.getAmState().getFocusedActivity());
    }

    private void sendPendingIntentActivity() {
        Intent intent = new Intent();
        intent.setComponent(APP_A_SEND_PENDING_INTENT_RECEIVER);
        intent.putExtra(IS_BROADCAST_EXTRA, false);
        mContext.sendBroadcast(intent);
    }

    private void sendPendingIntentBroadcast(int delayMs) {
        Intent intent = new Intent();
        intent.setComponent(APP_A_SEND_PENDING_INTENT_RECEIVER);
        intent.putExtra(IS_BROADCAST_EXTRA, true);
        if (delayMs > 0) {
            intent.putExtra(START_ACTIVITY_DELAY_MS_EXTRA, delayMs);
        }
        mContext.sendBroadcast(intent);
    }
}
