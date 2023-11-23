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

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.server.wm.BuildUtils.HW_TIMEOUT_MULTIPLIER;
import static android.server.wm.ShellCommandHelper.executeShellCommand;
import static android.server.wm.UiDeviceUtils.pressHomeButton;
import static android.server.wm.WindowManagerState.STATE_INITIALIZING;
import static android.server.wm.backgroundactivity.common.CommonComponents.EVENT_NOTIFIER_EXTRA;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.AsbSecurityTest;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.server.wm.backgroundactivity.appa.Components;
import android.server.wm.backgroundactivity.appa.IBackgroundActivityTestService;
import android.server.wm.backgroundactivity.common.CommonComponents.Event;
import android.server.wm.backgroundactivity.common.EventReceiver;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.compatibility.common.util.AppOpsUtils;

import org.junit.Assume;
import org.junit.AssumptionViolatedException;
import org.junit.Ignore;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class covers all test cases for starting/blocking background activities.
 * As instrumentation tests started by shell are whitelisted to allow starting background activity,
 * tests can't be done in this app alone.
 * Hence, there are 2 extra apps, appA and appB. This class will send commands to appA/appB, for
 * example, send a broadcast to appA and ask it to start a background activity, and we will monitor
 * the result and see if it starts an activity successfully.
 */
@Presubmit
public class BackgroundActivityLaunchTest extends BackgroundActivityTestBase {

    private static final String TAG = "BackgroundActivityLaunchTest";

    private static final long ACTIVITY_BG_START_GRACE_PERIOD_MS = 10 * 1000;
    private static final int ACTIVITY_START_TIMEOUT_MS = 5000;
    private static final int ACTIVITY_NOT_RESUMED_TIMEOUT_MS = 5000;

    private static final String APP_C_PACKAGE_NAME = "android.server.wm.backgroundactivity.appc";
    private static final String APP_C33_PACKAGE_NAME = APP_C_PACKAGE_NAME + "33";

    public static final ComponentName APP_C_FOREGROUND_ACTIVITY =
            new ComponentName(APP_C_PACKAGE_NAME,
                    "android.server.wm.backgroundactivity.appc.ForegroundActivity");
    public static final ComponentName APP_C_33_FOREGROUND_ACTIVITY =
            new ComponentName(APP_C33_PACKAGE_NAME,
                    "android.server.wm.backgroundactivity.appc.ForegroundActivity");

    /**
     * Tests can be executed as soon as the device has booted. When that happens the broadcast queue
     * is long and it takes some time to process the broadcast we just sent.
     */
    private static final int BROADCAST_DELIVERY_TIMEOUT_MS = 60000;

    private IBackgroundActivityTestService mBackgroundActivityTestService;

    @Test
    public void testBackgroundActivityBlocked() {
        // Start AppA background activity and blocked
        Intent intent = new Intent();
        intent.setComponent(APP_A.START_ACTIVITY_RECEIVER);
        mContext.sendBroadcast(intent);
        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStackIsEmpty(APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testStartBgActivity_usingStartActivitiesFromBackgroundPermission()
            throws Exception {
        // Disable SAW app op for shell, since that can also allow starting activities from bg.
        AppOpsUtils.setOpMode(SHELL_PACKAGE, "android:system_alert_window", MODE_ERRORED);

        // Launch the activity via a shell command, this way the system doesn't have info on which
        // app launched the activity and thus won't use instrumentation privileges to launch it. But
        // the shell has the START_ACTIVITIES_FROM_BACKGROUND permission, so we expect it to
        // succeed.
        // See testBackgroundActivityBlocked() for a case where an app without the
        // START_ACTIVITIES_FROM_BACKGROUND permission is blocked from launching the activity from
        // the background.
        launchActivity(APP_A.BACKGROUND_ACTIVITY);

        // If the activity launches, it means the START_ACTIVITIES_FROM_BACKGROUND permission works.
        assertEquals("Launched activity should be at the top",
                ComponentNameUtils.getActivityName(APP_A.BACKGROUND_ACTIVITY),
                mWmState.getTopActivityName(0));
    }

    @Test
    @FlakyTest(bugId = 155454710)
    public void testBackgroundActivity_withinGracePeriod_isNotBlocked() {
        // Start AppA foreground activity
        Intent firstIntent = new Intent();
        firstIntent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        firstIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(firstIntent);
        boolean firstResult = waitForActivityFocused(APP_A.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground activity", firstResult);
        // Don't press home button to avoid stop app switches
        mContext.sendBroadcast(new Intent(APP_A.FOREGROUND_ACTIVITY_ACTIONS.FINISH_ACTIVITY));
        mWmState.waitAndAssertActivityRemoved(APP_A.FOREGROUND_ACTIVITY);
        Intent secondIntent = new Intent();
        secondIntent.setComponent(APP_A.START_ACTIVITY_RECEIVER);

        mContext.sendBroadcast(secondIntent);
        boolean secondResult = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertTrue("Should be able to launch background activity", secondResult);
    }

    @Test
    public void testBackgroundActivityWhenSystemAlertWindowGranted_isNotBlocked()
            throws Exception {
        // enable appopp for SAW for this test
        AppOpsUtils.setOpMode(APP_A_33.APP_PACKAGE_NAME, "android:system_alert_window",
                MODE_ALLOWED);
        assertEquals(AppOpsUtils.getOpMode(APP_A_33.APP_PACKAGE_NAME,
                        "android:system_alert_window"),
                MODE_ALLOWED);

        // Start AppA background activity successfully as the package has SAW
        Intent intent = new Intent();
        intent.setComponent(APP_A_33.START_ACTIVITY_RECEIVER);
        mContext.sendBroadcast(intent);
        boolean result = waitForActivityFocused(APP_A_33.BACKGROUND_ACTIVITY);
        assertTrue("Not able to start foreground activity", result);
    }

    @Test
    public void testBackgroundActivityNotBlockedWhenForegroundActivityTop() {
        Intent intent = new Intent();
        intent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        mWmState.waitForValidState(APP_A.FOREGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);

        // Start AppA background activity successfully in new task as there's a foreground activity
        intent = new Intent();
        intent.setComponent(APP_A.START_ACTIVITY_RECEIVER);
        mContext.sendBroadcast(intent);
        mWmState.waitForValidState(APP_A.BACKGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.BACKGROUND_ACTIVITY, APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testBackgroundActivityWhenForegroundActivityNotTop_IsNotBlocked() {
        Intent intent = new Intent();
        intent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        mWmState.waitForValidState(APP_A.FOREGROUND_ACTIVITY);
        mContext.sendBroadcast(getLaunchActivitiesBroadcast(APP_A, APP_B.FOREGROUND_ACTIVITY));
        mWmState.waitForValidState(APP_B.FOREGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY,
                        APP_B.FOREGROUND_ACTIVITY,
                        APP_A.FOREGROUND_ACTIVITY);

        // Start AppA background activity successfully as there's a foreground activity
        intent = new Intent();
        intent.setComponent(APP_A.START_ACTIVITY_RECEIVER);
        mContext.sendBroadcast(intent);
        mWmState.waitForValidState(APP_A.BACKGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY,
                APP_B.FOREGROUND_ACTIVITY,
                APP_A.FOREGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.BACKGROUND_ACTIVITY, APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testActivityNotBlockedWhenForegroundActivityLaunch() throws Exception {
        // Start foreground activity, and foreground activity able to launch background activity
        // successfully
        Intent intent = new Intent();
        intent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(APP_A.FOREGROUND_ACTIVITY_EXTRA.LAUNCH_BACKGROUND_ACTIVITY, true);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY,
                APP_A.BACKGROUND_ACTIVITY,
                APP_A.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testActivityBroughtToTopOfTaskWhenLaunchedInTheBackground() throws Exception {
        // Start foreground activity, and foreground activity able to launch background activity
        // successfully
        Intent intent = new Intent();
        intent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(ACTIVITY_FOCUS_TIMEOUT_MS,
                APP_A.FOREGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);
        // We can't resume app switching after pressing home button, otherwise the grace period
        // will allow the starts.
        pressHomeAndWaitHomeResumed();

        mContext.sendBroadcast(getLaunchActivitiesBroadcast(APP_A, APP_A.BACKGROUND_ACTIVITY));

        result = waitForActivityFocused(APP_A.FOREGROUND_ACTIVITY);
        assertFalse("Previously foreground Activity should not be able to make it focused",
                result);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Previously background Activity should not be able to make it focused",
                result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY,
                APP_A.BACKGROUND_ACTIVITY,
                APP_A.FOREGROUND_ACTIVITY);
    }

    @Test
    @FlakyTest(bugId = 272082654)
    public void testActivityFromBgActivityInFgTask_isNotBlocked() {
        // Launch Activity A, B in the same task with different processes.
        final Intent intent = new Intent()
                .setComponent(APP_A.FOREGROUND_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        mWmState.waitForValidState(APP_A.FOREGROUND_ACTIVITY);
        mContext.sendBroadcast(getLaunchActivitiesBroadcast(APP_A, APP_B.FOREGROUND_ACTIVITY));
        mWmState.waitForValidState(APP_B.FOREGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY,
                APP_B.FOREGROUND_ACTIVITY,
                APP_A.FOREGROUND_ACTIVITY);

        // Refresh last-stop-app-switch-time by returning to home and then make the task foreground.
        pressHomeAndResumeAppSwitch();
        mContext.startActivity(intent);
        mWmState.waitForValidState(APP_B.FOREGROUND_ACTIVITY);
        // Though process A is in background, it is in a visible Task (top is B) so it should be
        // able to start activity successfully.
        mContext.sendBroadcast(new Intent(
                APP_A.FOREGROUND_ACTIVITY_ACTIONS.LAUNCH_BACKGROUND_ACTIVITIES)
                .putExtra(APP_A.FOREGROUND_ACTIVITY_EXTRA.LAUNCH_INTENTS,
                        new Intent[]{ new Intent()
                                .setComponent(APP_A.BACKGROUND_ACTIVITY)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }));
        mWmState.waitForValidState(APP_A.BACKGROUND_ACTIVITY);
        mWmState.assertFocusedActivity(
                "The background activity must be able to launch from a visible task",
                APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    @FlakyTest(bugId = 130800326)
    @Ignore  // TODO(b/145981637): Make this test work
    public void testActivityBlockedWhenForegroundActivityRestartsItself() throws Exception {
        // Start AppA foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(APP_A.FOREGROUND_ACTIVITY_EXTRA.RELAUNCH_FOREGROUND_ACTIVITY_EXTRA, true);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_A.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground activity", result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);

        // The foreground activity will be paused but will attempt to restart itself in onPause()
        pressHomeAndResumeAppSwitch();

        result = waitForActivityFocused(APP_A.FOREGROUND_ACTIVITY);
        assertFalse("Previously foreground Activity should not be able to relaunch itself",
                result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testSecondActivityNotBlockedWhenForegroundActivityLaunch() throws Exception {
        // Start AppA foreground activity, which will immediately launch one activity
        // and then the second.
        Intent intent = new Intent();
        intent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(APP_A.FOREGROUND_ACTIVITY_EXTRA.LAUNCH_BACKGROUND_ACTIVITY, true);
        intent.putExtra(APP_A.FOREGROUND_ACTIVITY_EXTRA.LAUNCH_SECOND_BACKGROUND_ACTIVITY, true);
        mContext.startActivity(intent);

        boolean result = waitForActivityFocused(APP_A.SECOND_BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch second background activity", result);

        waitAndAssertActivityState(APP_A.BACKGROUND_ACTIVITY, STATE_INITIALIZING,
                "First activity should have been created");
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY,
                APP_A.SECOND_BACKGROUND_ACTIVITY,
                APP_A.BACKGROUND_ACTIVITY,
                APP_A.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testSecondActivityBlockedWhenBackgroundActivityLaunch() throws Exception {
        Intent baseActivityIntent = new Intent();
        baseActivityIntent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        baseActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(baseActivityIntent);
        boolean result = waitForActivityFocused(APP_A.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground activity", result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);
        // We can't resume app switching after pressing home button, otherwise the grace period
        // will allow the starts.
        pressHomeAndWaitHomeResumed();

        // The activity, now in the background, will attempt to start 2 activities in quick
        // succession
        mContext.sendBroadcast(getLaunchActivitiesBroadcast(APP_A,
                APP_A.BACKGROUND_ACTIVITY,
                APP_A.SECOND_BACKGROUND_ACTIVITY));

        // There should be 2 activities in the background (not focused) INITIALIZING
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Activity should not have been launched in the foreground", result);
        result = waitForActivityFocused(APP_A.SECOND_BACKGROUND_ACTIVITY);
        assertFalse("Second activity should not have been launched in the foreground", result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY,
                APP_A.SECOND_BACKGROUND_ACTIVITY,
                APP_A.BACKGROUND_ACTIVITY,
                APP_A.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentActivityBlocked() throws Exception {
        // Cannot start activity by pending intent, as both appA and appB are in background
        sendPendingIntentActivity(APP_A, APP_B);
        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStackIsEmpty(APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentActivity_whenSenderAllowsBal_isNotBlocked() throws Exception {
        // creator (appa) is not privileged
        AppOpsUtils.setOpMode(APP_A.APP_PACKAGE_NAME, "android:system_alert_window", MODE_ERRORED);
        // sender (appb) is privileged, and grants
        AppOpsUtils.setOpMode(APP_B.APP_PACKAGE_NAME, "android:system_alert_window", MODE_ALLOWED);

        startPendingIntentSenderActivity(APP_A, APP_B, /* allowBalBySender */ true);
        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStackHasComponents(APP_A.BACKGROUND_ACTIVITY, APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentActivity_whenSenderDoesNotAllowBal_isBlocked() throws Exception {
        // creator (appa) is not privileged
        AppOpsUtils.setOpMode(APP_A.APP_PACKAGE_NAME, "android:system_alert_window", MODE_ERRORED);
        // sender (appb) is privileged, but revokes
        AppOpsUtils.setOpMode(APP_B.APP_PACKAGE_NAME, "android:system_alert_window", MODE_ALLOWED);

        startPendingIntentSenderActivity(APP_A, APP_B, /* allowBalBySender */ false);

        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
    }

    @Test
    public void testPendingIntentActivity_appAIsForeground_isNotBlocked() {
        // Start AppA foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_A.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_A.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentActivity(APP_A, APP_B);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStackHasComponents(APP_A.FOREGROUND_ACTIVITY, APP_A.FOREGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_A.BACKGROUND_ACTIVITY, APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastActivity_appBIsForeground_isBlocked() {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_B.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_B.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentActivity(APP_A, APP_B);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertWithMessage("Able to launch background activity").that(result).isFalse();
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastActivity_appBIsForegroundAndSdk33_isNotBlocked() {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_B_33.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_B_33.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStackHasComponents(APP_B_33.FOREGROUND_ACTIVITY, APP_B_33.FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentActivity(APP_A, APP_B_33);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStackHasComponents(APP_A.BACKGROUND_ACTIVITY, APP_A.BACKGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_B_33.FOREGROUND_ACTIVITY, APP_B_33.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastActivity_appBIsForegroundAndTryPassBalOnIntent_isBlocked() {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_B.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_B.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A.
        // ALLOW_BAL_EXTRA_ON_PENDING_INTENT will trigger AppA (the creator) to try to allow BAL on
        // behalf of the sender by adding the BAL option to the Intent's extras, which should have
        // no effect.
        sendPendingIntentActivity(APP_A, APP_B,
                APP_A.SEND_PENDING_INTENT_RECEIVER_EXTRA.ALLOW_BAL_EXTRA_ON_PENDING_INTENT);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertWithMessage("Able to launch background activity").that(result).isFalse();
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastActivity_appBIsFgAndTryPassBalOnIntentWithNullBundleOnPendingIntent_isBlocked() {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_B.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_B.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentActivity(APP_A, APP_B,
                APP_A.SEND_PENDING_INTENT_RECEIVER_EXTRA.ALLOW_BAL_EXTRA_ON_PENDING_INTENT
                /* on create by app A */,
                APP_B.START_PENDING_INTENT_ACTIVITY_EXTRA.USE_NULL_BUNDLE /* on send by app B */);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertWithMessage("Able to launch background activity").that(result).isFalse();
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastActivity_appBIsForegroundAndAllowsBal_isNotBlocked() {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(APP_B.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(APP_B.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentActivity(APP_A, APP_B, APP_B.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertTrue("Not able to launch background activity", result);
        assertTaskStackHasComponents(APP_A.BACKGROUND_ACTIVITY, APP_A.BACKGROUND_ACTIVITY);
        assertTaskStackHasComponents(APP_B.FOREGROUND_ACTIVITY, APP_B.FOREGROUND_ACTIVITY);
    }

    @Test
    public void testPendingIntentBroadcastTimeout_noDelay() throws Exception {
        assertPendingIntentBroadcastTimeoutTest(APP_A, APP_B, 0, true);
    }

    @Test
    @FlakyTest(bugId = 141344170)
    public void testPendingIntentBroadcastTimeout_delay1s() throws Exception {
        assertPendingIntentBroadcastTimeoutTest(APP_A, APP_B, 1000, true);
    }

    @Test
    public void testPendingIntentBroadcastTimeout_delay12s() throws Exception {
        // This test is testing that activity start is blocked after broadcast allowlist token
        // timeout. Before the timeout, the start would be allowed because app B (the PI sender) was
        // in the foreground during PI send, so app A (the PI creator) would have
        // (10s * hw_multiplier) to start background activity starts.
        assertPendingIntentBroadcastTimeoutTest(APP_A, APP_B, 12000 * HW_TIMEOUT_MULTIPLIER, false);
    }

    @Test
    public void testPendingIntentBroadcast_appBIsBackground() throws Exception {
        EventReceiver receiver = new EventReceiver(
                Event.APP_A_START_BACKGROUND_ACTIVITY_BROADCAST_RECEIVED);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentBroadcast(APP_A, 0, receiver.getNotifier());

        // Waits for final hoop in AppA to start looking for activity, otherwise it could succeed
        // if the broadcast took long time to get executed (which may happen after boot).
        receiver.waitForEventOrThrow(BROADCAST_DELIVERY_TIMEOUT_MS);
        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStackIsEmpty(APP_A.BACKGROUND_ACTIVITY);
    }

    /**
     * Returns a list of alive users on the device
     */
    private List<UserInfo> getAliveUsers() {
        // Setting the CREATE_USERS permission in AndroidManifest.xml has no effect when the test
        // is run through the CTS harness, so instead adopt it as a shell permission. We use
        // the CREATE_USERS permission instead of MANAGE_USERS because the shell can never use
        // MANAGE_USERS.
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CREATE_USERS);
        List<UserInfo> userList = mContext.getSystemService(UserManager.class).getAliveUsers();
        uiAutomation.dropShellPermissionIdentity();
        return userList;
    }

    /**
     * Removes the guest user from the device if present
     */
    private void removeGuestUser() {
        List<UserInfo> userList = getAliveUsers();
        for (UserInfo info : userList) {
            if (info.isGuest()) {
                removeUser(info.id);
                // Device is only allowed to have one alive guest user, so stop if it's found
                break;
            }
        }
    }

    /**
     * Removes a user from the device given their ID
     */
    private void removeUser(int userId) {
        executeShellCommand(String.format("pm remove-user %d", userId));
    }

    @Test
    public void testDeviceOwner() throws Exception {
        assumeTrue("Device doesn't support FEATURE_DEVICE_ADMIN",
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN));

        // Remove existing guest user. The device may already have a guest present if it is
        // configured with config_guestUserAutoCreated.
        //
        // In production flow the DO can only be created before device provisioning finishes
        // (e.g. during SUW), and we make sure the guest user in only created after the device
        // provision is finished. Ideally this test would use the provisioning flow and Device
        // Owner (DO) creation in a similar manner as that of production flow.
        removeGuestUser();

        // This test might be running as current user (on devices that use headless system user
        // mode), so it needs to get the context for the system user.
        Context context = runWithShellPermissionIdentity(
                () -> mContext.createContextAsUser(UserHandle.SYSTEM, /* flags= */ 0),
                INTERACT_ACROSS_USERS);

        String doComponent = APP_A.SIMPLE_ADMIN_RECEIVER.flattenToString();
        Log.d(TAG, "Setting DO as " + doComponent);
        String cmd = "dpm set-device-owner --user " + UserHandle.USER_SYSTEM + " " + doComponent;
        try {
            String cmdResult = runShellCommandOrThrow(cmd);
            assertWithMessage("Result of '%s'", cmd).that(cmdResult).contains("Success");
        } catch (AssertionError e) {
            // If failed to set the device owner, stop proceeding to the test case.
            // Log the error info so that we can investigate further in the future.
            Log.d(TAG, "Failed to set device owner.", e);
            String cmdResult = runShellCommandOrThrow("pm list user");
            Log.d(TAG, "users: " + cmdResult);
            cmdResult = runShellCommandOrThrow("dpm list-owner");
            Log.d(TAG, "device owners: " + cmdResult);
            throw new AssumptionViolatedException("This test needs to be able to set device owner");
        }

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        EventReceiver receiver = new EventReceiver(
                Event.APP_A_START_BACKGROUND_ACTIVITY_BROADCAST_RECEIVED);
        Intent intent = new Intent()
                .setComponent(APP_A.START_ACTIVITY_RECEIVER)
                .putExtra(EVENT_NOTIFIER_EXTRA, receiver.getNotifier());

        Log.d(TAG, "Launching " + intent + " on " + context.getUser());
        // Must run with IAC permission as it might be a context from other user
        runWithShellPermissionIdentity(() -> context.sendBroadcast(intent), INTERACT_ACROSS_USERS);

        // Waits for final hoop in AppA to start looking for activity
        receiver.waitForEventOrThrow(BROADCAST_DELIVERY_TIMEOUT_MS);
        boolean actualResult = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);

        if (UserManager.isHeadlessSystemUserMode()) {
            assertWithMessage("Launched bg activity (%s) for (headless) system user",
                    APP_A.BACKGROUND_ACTIVITY).that(actualResult).isFalse();
            assertTaskDoesNotHaveVisibleComponents(APP_A.BACKGROUND_ACTIVITY,
                    APP_A.BACKGROUND_ACTIVITY);
        } else {
            assertWithMessage("Launched bg activity (%s) for (full) system user",
                    APP_A.BACKGROUND_ACTIVITY).that(actualResult).isTrue();
            assertTaskStackHasComponents(APP_A.BACKGROUND_ACTIVITY, APP_A.BACKGROUND_ACTIVITY);
        }
    }

    @Test
    public void testAppCannotStartBgActivityAfterHomeButton() throws Exception {
        Intent intent = new Intent();
        intent.setComponent(APP_A.RELAUNCHING_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        assertTrue("Main activity not started", waitUntilForegroundChanged(
                APP_A.APP_PACKAGE_NAME, true, ACTIVITY_START_TIMEOUT_MS));
        assertActivityFocused(APP_A.RELAUNCHING_ACTIVITY);

        // Click home button, and test app activity onPause() will try to start a background
        // activity, but we expect this will be blocked BAL logic in system, as app cannot start
        // any background activity even within grace period after pressing home button.
        pressHomeAndWaitHomeResumed();

        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
    }

    // Check picture-in-picture(PIP) won't allow to start BAL after pressing home.
    @Test
    public void testPipCannotStartAfterHomeButton() throws Exception {

        Intent intent = new Intent();
        intent.setComponent(APP_A.PIP_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        assertTrue("Pip activity not started", waitUntilForegroundChanged(
                APP_A.APP_PACKAGE_NAME, true, ACTIVITY_START_TIMEOUT_MS));

        // Click home button, and test app activity onPause() will trigger pip window,
        // test will will try to start background activity, but we expect the background activity
        // will be blocked even the app has a visible pip window, as we do not allow background
        // activity to be started after pressing home button.
        pressHomeAndWaitHomeResumed();

        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
    }

    @Test
    @AsbSecurityTest(cveBugId = 271576718)
    public void testPipCannotStartFromBackground() throws Exception {
        Intent intent = new Intent();
        intent.setComponent(APP_A.LAUNCH_INTO_PIP_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        boolean result = waitForActivityFocused(APP_A.LAUNCH_INTO_PIP_ACTIVITY);
        assertTrue("Should not able to launch background activity", result);

        pressHomeAndWaitHomeResumed();
        result = waitForActivityFocused(APP_A.LAUNCH_INTO_PIP_ACTIVITY);
        assertFalse("Activity should be in background", result);

        Intent broadcast = new Intent(APP_A.LAUNCH_INTO_PIP_ACTIONS.LAUNCH_INTO_PIP);
        mContext.sendBroadcast(broadcast);
        result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch LaunchIntoPip activity", result);

        assertPinnedStackDoesNotExist();
    }

    // Check that a presentation on a virtual display won't allow BAL after pressing home.
    @Test
    public void testVirtualDisplayCannotStartAfterHomeButton() throws Exception {
        Intent intent = new Intent();
        intent.setComponent(APP_A.VIRTUAL_DISPLAY_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        assertTrue("VirtualDisplay activity not started", waitUntilForegroundChanged(
                APP_A.APP_PACKAGE_NAME, true, ACTIVITY_START_TIMEOUT_MS));

        // Click home button, and test app activity onPause() will trigger which tries to launch
        // the background activity.
        pressHomeAndWaitHomeResumed();

        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
    }


    // Test manage space pending intent created by system cannot bypass BAL check.
    @Test
    public void testManageSpacePendingIntentNoBalAllowed() throws Exception {
        setupPendingIntentService(APP_A);
        runWithShellPermissionIdentity(() -> {
            runShellCommandOrThrow("cmd appops set " + APP_A.APP_PACKAGE_NAME
                    + " android:manage_external_storage allow");
        });
        // Make sure AppA paused at least 10s so it can't start activity because of grace period.
        Thread.sleep(1000 * 10);
        mBackgroundActivityTestService.getAndStartManageSpaceActivity();
        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStackIsEmpty(APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testAppWidgetConfigNoBalBypass() throws Exception {
        // Click bind widget button and then go home screen so app A will enter background state
        // with bind widget ability.
        EventReceiver receiver = new EventReceiver(Event.APP_A_START_WIDGET_CONFIG_ACTIVITY);
        clickAllowBindWidget(APP_A, receiver.getNotifier());
        pressHomeAndWaitHomeResumed();

        // After pressing home button, wait for appA to start widget config activity.
        receiver.waitForEventOrThrow(1000 * 30);

        boolean result = waitForActivityFocused(APP_A.BACKGROUND_ACTIVITY);
        assertFalse("Should not able to launch background activity", result);
        assertTaskStackIsEmpty(APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testBalOptInBindToService_whenOptedIn_allowsActivityStarts() {
        Intent appcIntent = new Intent()
                .setComponent(APP_C_FOREGROUND_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("android.server.wm.backgroundactivity.appc.ALLOW_BAL", true);

        mContext.startActivity(appcIntent);
        assertActivityFocused(APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testBalOptInBindToService_whenNotOptedIn_blocksActivityStarts() {
        Intent appcIntent = new Intent()
                .setComponent(APP_C_FOREGROUND_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        mContext.startActivity(appcIntent);
        assertActivityNotFocused(APP_A.BACKGROUND_ACTIVITY);
    }

    @Test
    public void testBalOptInBindToService_whenNotOptedInAndSdk33_allowsActivityStart() {
        Intent appcIntent = new Intent()
                .setComponent(APP_C_33_FOREGROUND_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        mContext.startActivity(appcIntent);
        assertActivityFocused(APP_A.BACKGROUND_ACTIVITY);
    }

    private void clickAllowBindWidget(Components appA, ResultReceiver resultReceiver)
            throws Exception {
        PackageManager pm = mContext.getPackageManager();
        Assume.assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS));
        // Skip on auto and TV devices only as they don't support appwidget bind.
        Assume.assumeFalse(pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));
        Assume.assumeFalse(pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY));

        // Create appWidgetId so we can send it to appA, to request bind widget and start config
        // activity.
        UiDevice device = UiDevice.getInstance(mInstrumentation);
        AppWidgetHost appWidgetHost = new AppWidgetHost(mContext, 0);
        final int appWidgetId = appWidgetHost.allocateAppWidgetId();
        Intent appWidgetIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        appWidgetIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        appWidgetIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
                appA.WIDGET_PROVIDER);

        Intent intent = new Intent();
        intent.setComponent(appA.WIDGET_CONFIG_TEST_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_INTENT, appWidgetIntent);
        intent.putExtra(EVENT_NOTIFIER_EXTRA, resultReceiver);
        mContext.startActivity(intent);

        // Find settings package and bind widget activity and click the create button.
        String settingsPkgName = "";
        List<ResolveInfo> ris = pm.queryIntentActivities(appWidgetIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo.name.contains("AllowBindAppWidgetActivity")) {
                settingsPkgName = ri.activityInfo.packageName;
            }
        }
        assertNotEquals("Cannot find settings app", "", settingsPkgName);

        if (!device.wait(Until.hasObject(By.pkg(settingsPkgName)), 1000 * 10)) {
            fail("Unable to start AllowBindAppWidgetActivity");
        }
        boolean buttonClicked = false;
        BySelector selector = By.clickable(true);
        List<UiObject2> objects = device.findObjects(selector);
        for (UiObject2 object : objects) {
            String objectText = object.getText();
            if (objectText == null) {
                continue;
            }
            if (objectText.equalsIgnoreCase("CREATE") || objectText.equalsIgnoreCase("ALLOW")) {
                object.click();
                buttonClicked = true;
                break;
            }
        }
        if (!device.wait(Until.gone(By.pkg(settingsPkgName)), 1000 * 10) || !buttonClicked) {
            fail("Create' button not found/clicked");
        }

        // Wait the bind widget activity goes away.
        waitUntilForegroundChanged(settingsPkgName, false,
                ACTIVITY_NOT_RESUMED_TIMEOUT_MS);
    }

    private void pressHomeAndWaitHomeResumed() {
        assumeSetupComplete();
        pressHomeButton();
        mWmState.waitForHomeActivityVisible();
        mWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);
    }

    private void pressHomeAndWaitHomeResumed(int timeoutMs) {
        assumeSetupComplete();
        pressHomeButton();
        assertActivityFocused(timeoutMs, mWmState.getHomeActivityName());
    }

    private void assumeSetupComplete() {
        assumeThat(Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0), is(1));
    }

    private boolean checkPackageResumed(String pkg) {
        WindowManagerStateHelper helper = new WindowManagerStateHelper();
        helper.computeState();
        return ComponentName.unflattenFromString(
                helper.getFocusedActivity()).getPackageName().equals(pkg);
    }

    // Return true if the state of the package is changed to target state.
    private boolean waitUntilForegroundChanged(String targetPkg, boolean toBeResumed, int timeout)
            throws Exception {
        long startTime = System.currentTimeMillis();
        while (checkPackageResumed(targetPkg) != toBeResumed) {
            if (System.currentTimeMillis() - startTime < timeout) {
                Thread.sleep(100);
            } else {
                return false;
            }
        }
        return true;
    }

    private void assertActivityNotResumed(Components appA) throws Exception {
        assertFalse("Test activity is resumed",
                waitUntilForegroundChanged(appA.APP_PACKAGE_NAME, true,
                        ACTIVITY_NOT_RESUMED_TIMEOUT_MS));
    }

    private void pressHomeAndResumeAppSwitch() {
        // Press home key to ensure stopAppSwitches is called because the last-stop-app-switch-time
        // is a criteria of allowing background start.
        pressHomeButton();
        // Resume the stopped state (it won't affect last-stop-app-switch-time) so we don't need to
        // wait extra time to prevent the next launch from being delayed.
        resumeAppSwitches();
        mWmState.waitForHomeActivityVisible();
        // Resuming app switches again after home became visible because the previous call might
        // have raced with pressHomeButton().
        // TODO(b/155454710): Remove previous call after making sure all the tests don't depend on
        // the timing here.
        resumeAppSwitches();
    }

    private void assertPendingIntentBroadcastTimeoutTest(Components appA,
            android.server.wm.backgroundactivity.appb.Components appB,
            int delayMs, boolean expectedResult)
            throws TimeoutException {
        // Start AppB foreground activity
        Intent intent = new Intent();
        intent.setComponent(appB.FOREGROUND_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        boolean result = waitForActivityFocused(appB.FOREGROUND_ACTIVITY);
        assertTrue("Not able to start foreground Activity", result);
        assertTaskStackHasComponents(appB.FOREGROUND_ACTIVITY, appB.FOREGROUND_ACTIVITY);
        EventReceiver receiver = new EventReceiver(
                Event.APP_A_START_BACKGROUND_ACTIVITY_BROADCAST_RECEIVED);

        // Send pendingIntent from AppA to AppB, and the AppB launch the pending intent to start
        // activity in App A
        sendPendingIntentBroadcast(appA, delayMs, receiver.getNotifier(), true);

        // Waits for final hoop in AppA to start looking for activity
        receiver.waitForEventOrThrow(BROADCAST_DELIVERY_TIMEOUT_MS);
        result = waitForActivityFocused(ACTIVITY_FOCUS_TIMEOUT_MS + delayMs,
                appA.BACKGROUND_ACTIVITY);
        assertEquals(expectedResult, result);
        if (expectedResult) {
            assertTaskStackHasComponents(appA.BACKGROUND_ACTIVITY, appA.BACKGROUND_ACTIVITY);
        } else {
            assertTaskStackIsEmpty(appA.BACKGROUND_ACTIVITY);
        }
    }

    private void setupPendingIntentService(Components appA) throws Exception {
        Intent bindIntent = new Intent();
        bindIntent.setComponent(appA.BACKGROUND_ACTIVITY_TEST_SERVICE);
        final CountDownLatch bindLatch = new CountDownLatch(1);

        mBalServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mBackgroundActivityTestService =
                        IBackgroundActivityTestService.Stub.asInterface(service);
                bindLatch.countDown();
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mBackgroundActivityTestService = null;
            }
        };
        boolean success = mContext.bindService(bindIntent, mBalServiceConnection,
                Context.BIND_AUTO_CREATE);
        assertTrue(success);
        assertTrue("Timeout connecting to test service",
                bindLatch.await(1000, TimeUnit.MILLISECONDS));
    }

    private void startPendingIntentSenderActivity(Components appA,
            android.server.wm.backgroundactivity.appb.Components appB, boolean allowBal)
            throws Exception {
        setupPendingIntentService(appA);
        // Get a PendingIntent created by appA.
        final PendingIntent pi;
        try {
            pi = mBackgroundActivityTestService.generatePendingIntent(false);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        // Start app B's activity so it runs send() on PendingIntent created by app A.
        Intent secondIntent = new Intent();
        secondIntent.setComponent(appB.START_PENDING_INTENT_ACTIVITY);
        secondIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        secondIntent.putExtra(appB.START_PENDING_INTENT_RECEIVER_EXTRA.PENDING_INTENT, pi);
        secondIntent.putExtra(appB.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL, allowBal);
        mContext.startActivity(secondIntent);
    }

    private void sendPendingIntentActivity(Components appA,
            android.server.wm.backgroundactivity.appb.Components appB,
            String... booleanExtras) {
        Intent intent = new Intent();
        intent.setComponent(appA.SEND_PENDING_INTENT_RECEIVER);
        intent.putExtra(appA.SEND_PENDING_INTENT_RECEIVER_EXTRA.IS_BROADCAST, false);
        intent.putExtra(appA.SEND_PENDING_INTENT_RECEIVER_EXTRA.APP_B_PACKAGE,
                appB.APP_PACKAGE_NAME);
        for (String booleanExtra : booleanExtras) {
            intent.putExtra(booleanExtra, true);
        }
        Log.i(
                "BackgroundActivityLaunchTest",
                "Send broadcast to "
                        + intent.getComponent()
                        + " with extras: "
                        + intent.getExtras());
        mContext.sendBroadcast(intent);
    }

    private void sendPendingIntentBroadcast(Components appA, int delayMs,
            @Nullable ResultReceiver eventNotifier) {
        sendPendingIntentBroadcast(appA, delayMs, eventNotifier, false);
    }

    private void sendPendingIntentBroadcast(Components appA, int delayMs,
            @Nullable ResultReceiver eventNotifier,  boolean allowBalFromStartingApp) {
        Intent intent = new Intent();
        intent.setComponent(appA.SEND_PENDING_INTENT_RECEIVER);
        intent.putExtra(appA.SEND_PENDING_INTENT_RECEIVER_EXTRA.IS_BROADCAST, true);
        if (allowBalFromStartingApp) {
            intent.putExtra(APP_B.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL, true);
        }
        if (delayMs > 0) {
            intent.putExtra(appA.START_ACTIVITY_RECEIVER_EXTRA.START_ACTIVITY_DELAY_MS, delayMs);
        }
        intent.putExtra(EVENT_NOTIFIER_EXTRA, eventNotifier);
        mContext.sendBroadcast(intent);
    }
}
