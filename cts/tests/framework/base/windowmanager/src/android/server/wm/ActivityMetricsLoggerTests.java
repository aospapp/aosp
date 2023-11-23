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

package android.server.wm;

import static android.os.SystemClock.sleep;
import static android.server.wm.UiDeviceUtils.pressBackButton;
import static android.server.wm.UiDeviceUtils.pressHomeButton;
import static android.server.wm.UiDeviceUtils.waitForDeviceIdle;
import static android.server.wm.app.Components.ENTRY_POINT_ALIAS_ACTIVITY;
import static android.server.wm.app.Components.NO_DISPLAY_ACTIVITY;
import static android.server.wm.app.Components.REPORT_FULLY_DRAWN_ACTIVITY;
import static android.server.wm.app.Components.SINGLE_TASK_ACTIVITY;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.app.Components.TRANSLUCENT_TEST_ACTIVITY;
import static android.server.wm.second.Components.SECOND_ACTIVITY;
import static android.server.wm.third.Components.THIRD_ACTIVITY;
import static android.util.TimeUtils.formatDuration;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DEVICE_UPTIME_SECONDS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_REPORTED_DRAWN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_REPORTED_DRAWN_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_STARTING_WINDOW_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CLASS_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_COLD_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_HOT_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_REPORTED_DRAWN_NO_BUNDLE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_WARM_LAUNCH;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.ComponentName;
import android.metrics.LogMaker;
import android.metrics.MetricsReader;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.support.test.metricshelper.MetricsAsserts;
import android.util.EventLog.Event;

import androidx.test.filters.FlakyTest;

import com.android.compatibility.common.util.SystemUtil;

import org.hamcrest.collection.IsIn;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * CTS device tests for {@link com.android.server.wm.ActivityMetricsLogger}.
 * Build/Install/Run:
 * atest CtsWindowManagerDeviceTestCases:ActivityMetricsLoggerTests
 */
@Presubmit
public class ActivityMetricsLoggerTests extends ActivityManagerTestBase {
    private static final String TAG_ATM = "ActivityTaskManager";
    private final MetricsReader mMetricsReader = new MetricsReader();
    private long mPreUptimeMs;
    private LogSeparator mLogSeparator;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mPreUptimeMs = SystemClock.uptimeMillis();
        mMetricsReader.checkpoint(); // clear out old logs
        mLogSeparator = separateLogs(); // add a new separator for logs
    }

    /**
     * Launch an app and verify:
     * - appropriate metrics logs are added
     * - "Displayed activity ..." log is added to logcat
     * - am_activity_launch_time event is generated
     * In all three cases, verify the delay measurements are the same.
     */
    @Test
    public void testAppLaunchIsLogged() {
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(TEST_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();

        final LogMaker metricsLog = getMetricsLog(TEST_ACTIVITY, APP_TRANSITION);
        final String[] deviceLogs = getDeviceLogsForComponents(mLogSeparator, TAG_ATM);
        final List<Event> eventLogs = getEventLogsForComponents(mLogSeparator,
                30009 /* AM_ACTIVITY_LAUNCH_TIME */);

        final long postUptimeMs = SystemClock.uptimeMillis();
        assertMetricsLogs(TEST_ACTIVITY, APP_TRANSITION, metricsLog, mPreUptimeMs, postUptimeMs);
        assertTransitionIsStartingWindow(metricsLog);
        final int windowsDrawnDelayMs =
                (int) metricsLog.getTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS);
        final String expectedLog =
                "Displayed " + TEST_ACTIVITY.flattenToShortString()
                        + ": " + formatDuration(windowsDrawnDelayMs);
        assertLogsContain(deviceLogs, expectedLog);
        assertEventLogsContainsLaunchTime(eventLogs, TEST_ACTIVITY, windowsDrawnDelayMs);
    }

    private void assertMetricsLogs(ComponentName componentName,
            int category, LogMaker log, long preUptimeMs, long postUptimeMs) {
        assertNotNull("did not find the metrics log for: " + componentName
                + " category:" + category, log);
        int startUptimeSec =
                ((Number) log.getTaggedData(APP_TRANSITION_DEVICE_UPTIME_SECONDS)).intValue();
        int preUptimeSec = (int) (TimeUnit.MILLISECONDS.toSeconds(preUptimeMs));
        int postUptimeSec = (int) (TimeUnit.MILLISECONDS.toSeconds(postUptimeMs));
        long testElapsedTimeMs = postUptimeMs - preUptimeMs;
        assertThat("must be either cold or warm launch", log.getType(),
                IsIn.oneOf(TYPE_TRANSITION_COLD_LAUNCH, TYPE_TRANSITION_WARM_LAUNCH));
        assertThat("reported uptime should be after the app was started", startUptimeSec,
                greaterThanOrEqualTo(preUptimeSec));
        assertThat("reported uptime should be before assertion time", startUptimeSec,
                lessThanOrEqualTo(postUptimeSec));
        assertNotNull("log should have delay", log.getTaggedData(APP_TRANSITION_DELAY_MS));
        assertNotNull("log should have windows drawn delay",
                log.getTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS));
        long windowsDrawnDelayMs = (int) log.getTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS);
        assertThat("windows drawn delay should be less that total elapsed time",
                windowsDrawnDelayMs,  lessThanOrEqualTo(testElapsedTimeMs));
    }

    private void assertTransitionIsStartingWindow(LogMaker log) {
        assertEquals("transition should be started because of starting window",
                1 /* APP_TRANSITION_STARTING_WINDOW */, log.getSubtype());
        assertNotNull("log should have starting window delay",
                log.getTaggedData(APP_TRANSITION_STARTING_WINDOW_DELAY_MS));
    }

    private void assertEventLogsContainsLaunchTime(List<Event> events, ComponentName componentName,
            int windowsDrawnDelayMs) {
        for (Event event : events) {
            Object[] arr = (Object[]) event.getData();
            assertEquals(4, arr.length);
            final String name = (String) arr[2];
            final int delay = (int) arr[3];
            if (name.equals(componentName.flattenToShortString())) {
                assertEquals("Unexpected windows drawn delay for " + componentName,
                        delay, windowsDrawnDelayMs);
                return;
            }
        }
        fail("Could not find am_activity_launch_time for " + componentName);
    }

    /**
     * Start an activity that reports full drawn and verify:
     * - fully drawn metrics are added to metrics logs
     * - "Fully drawn activity ..." log is added to logcat
     * In both cases verify fully drawn delay measurements are equal.
     * See {@link Activity#reportFullyDrawn()}
     */
    @Test
    public void testAppFullyDrawnReportIsLogged() {
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(REPORT_FULLY_DRAWN_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();

        // Sleep until activity under test has reported drawn (after 500ms)
        sleep(1000);

        final LogMaker metricsLog = getMetricsLog(REPORT_FULLY_DRAWN_ACTIVITY,
                APP_TRANSITION_REPORTED_DRAWN);
        final String[] deviceLogs = getDeviceLogsForComponents(mLogSeparator, TAG_ATM);

        assertNotNull("did not find the metrics log for: " + REPORT_FULLY_DRAWN_ACTIVITY
                + " category:" + APP_TRANSITION_REPORTED_DRAWN, metricsLog);
        assertThat("test activity has a 500ms delay before reporting fully drawn",
                (long) metricsLog.getTaggedData(APP_TRANSITION_REPORTED_DRAWN_MS),
                greaterThanOrEqualTo(500L));
        assertEquals(TYPE_TRANSITION_REPORTED_DRAWN_NO_BUNDLE, metricsLog.getType());

        final long fullyDrawnDelayMs =
                (long) metricsLog.getTaggedData(APP_TRANSITION_REPORTED_DRAWN_MS);
        final String expectedLog =
                "Fully drawn " + REPORT_FULLY_DRAWN_ACTIVITY.flattenToShortString()
                        + ": " + formatDuration(fullyDrawnDelayMs);
        assertLogsContain(deviceLogs, expectedLog);
    }

    /**
     * Warm launch an activity with wait option and verify that {@link android.app.WaitResult#totalTime}
     * totalTime is set correctly. Make sure the reported value is consistent with value reported to
     * metrics logs. Verify we output the correct launch state.
     */
    @Test
    @FlakyTest(bugId = 130764822)
    public void testAppWarmLaunchSetsWaitResultDelayData() {
        SystemUtil.runShellCommand("am start -S -W " + TEST_ACTIVITY.flattenToShortString());

        // Test warm launch
        pressBackButton();
        waitForDeviceIdle(1000);
        mMetricsReader.checkpoint(); // clear out old logs

        final String amStartOutput = SystemUtil.runShellCommand(
                "am start -W " + TEST_ACTIVITY.flattenToShortString());

        final LogMaker metricsLog = getMetricsLog(TEST_ACTIVITY, APP_TRANSITION);
        assertNotNull("log should have windows drawn delay", metricsLog);

        final int windowsDrawnDelayMs =
                (int) metricsLog.getTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS);

        assertEquals("Expected a cold launch.", metricsLog.getType(), TYPE_TRANSITION_WARM_LAUNCH);

        assertThat("did not find component in am start output.", amStartOutput,
                containsString(TEST_ACTIVITY.flattenToShortString()));

        assertThat("did not find windows drawn delay time in am start output.", amStartOutput,
                containsString(Integer.toString(windowsDrawnDelayMs)));

        assertThat("did not find launch state in am start output.", amStartOutput,
                containsString("WARM"));
    }


    /**
     * Hot launch an activity with wait option and verify that {@link android.app.WaitResult#totalTime}
     * totalTime is set correctly. Make sure the reported value is consistent with value reported to
     * metrics logs. Verify we output the correct launch state.
     */
    @Test
    @FlakyTest(bugId = 130764822)
    public void testAppHotLaunchSetsWaitResultDelayData() {
        SystemUtil.runShellCommand("am start -S -W " + TEST_ACTIVITY.flattenToShortString());

        // Test hot launch
        pressHomeButton();
        waitForDeviceIdle(1000);
        mMetricsReader.checkpoint(); // clear out old logs

        final String amStartOutput = SystemUtil.runShellCommand(
                "am start -W " + TEST_ACTIVITY.flattenToShortString());

        final LogMaker metricsLog = getMetricsLog(TEST_ACTIVITY, APP_TRANSITION);
        assertNotNull("log should have windows drawn delay", metricsLog);

        final int windowsDrawnDelayMs =
                (int) metricsLog.getTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS);

        assertEquals("Expected a cold launch.", metricsLog.getType(), TYPE_TRANSITION_HOT_LAUNCH);

        assertThat("did not find component in am start output.", amStartOutput,
                containsString(TEST_ACTIVITY.flattenToShortString()));

        // TODO (b/120981435) use ActivityMetricsLogger to populate hot launch times
        // assertThat("did not find windows drawn delay time in am start output.", amStartOutput,
        //       containsString(Integer.toString(windowsDrawnDelayMs)));

        assertThat("did not find launch state in am start output.", amStartOutput,
                containsString("HOT"));
    }

    /**
     * Cold launch an activity with wait option and verify that {@link android.app.WaitResult#totalTime}
     * totalTime is set correctly. Make sure the reported value is consistent with value reported to
     * metrics logs. Verify we output the correct launch state.
     */
    @Test
    @FlakyTest(bugId = 130764822)
    public void testAppColdLaunchSetsWaitResultDelayData() {
        final String amStartOutput = SystemUtil.runShellCommand(
                "am start -S -W " + TEST_ACTIVITY.flattenToShortString());

        final LogMaker metricsLog = getMetricsLog(TEST_ACTIVITY, APP_TRANSITION);
        assertNotNull("log should have windows drawn delay", metricsLog);

        final int windowsDrawnDelayMs =
                (int) metricsLog.getTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS);

        assertEquals("Expected a cold launch.", metricsLog.getType(), TYPE_TRANSITION_COLD_LAUNCH);

        assertThat("did not find component in am start output.", amStartOutput,
                containsString(TEST_ACTIVITY.flattenToShortString()));

        assertThat("did not find windows drawn delay time in am start output.", amStartOutput,
                containsString(Integer.toString(windowsDrawnDelayMs)));

        assertThat("did not find launch state in am start output.", amStartOutput,
                containsString("COLD"));
    }

    /**
     * Launch an app that is already visible and verify we handle cases where we will not
     * receive a windows drawn message.
     * see b/117148004
     */
    @Test
    public void testLaunchOfVisibleApp() {
        // Launch an activity.
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(SECOND_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();

        // Launch a translucent activity on top.
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(TRANSLUCENT_TEST_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();

        // Launch the first activity again. This will not trigger a windows drawn message since
        // its windows were visible before launching.
        mMetricsReader.checkpoint(); // clear out old logs
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(SECOND_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();
        LogMaker metricsLog = getMetricsLog(SECOND_ACTIVITY, APP_TRANSITION);
        // Verify transition logs are absent since we cannot measure windows drawn delay.
        assertNull("transition logs should be reset.", metricsLog);

        // Verify metrics for subsequent launches are generated as expected.
        mPreUptimeMs = SystemClock.uptimeMillis();
        mMetricsReader.checkpoint(); // clear out old logs

        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(THIRD_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();

        long postUptimeMs = SystemClock.uptimeMillis();
        metricsLog = getMetricsLog(THIRD_ACTIVITY, APP_TRANSITION);
        assertMetricsLogs(THIRD_ACTIVITY, APP_TRANSITION, metricsLog, mPreUptimeMs,
                postUptimeMs);
        assertTransitionIsStartingWindow(metricsLog);
    }

    /**
     * Launch a NoDisplay activity and verify it does not affect subsequent activity launch
     * metrics. NoDisplay activities do not draw any windows and may be incorrectly identified as a
     * trampoline activity. See b/80380150 (Long warm launch times reported in dev play console)
     */
    @Test
    public void testNoDisplayActivityLaunch() {
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(NO_DISPLAY_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();

        mPreUptimeMs = SystemClock.uptimeMillis();
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(SECOND_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();
        final LogMaker metricsLog = getMetricsLog(SECOND_ACTIVITY, APP_TRANSITION);
        final long postUptimeMs = SystemClock.uptimeMillis();
        assertMetricsLogs(SECOND_ACTIVITY, APP_TRANSITION, metricsLog, mPreUptimeMs, postUptimeMs);
        assertTransitionIsStartingWindow(metricsLog);
    }

    /**
     * Launch an activity with a trampoline activity and verify launch metrics measures the complete
     * launch sequence from when the trampoline activity is launching to when the target activity
     * draws on screen.
     */
    @Test
    public void testTrampolineActivityLaunch() {
        // Launch a trampoline activity that will launch single task activity.
        getLaunchActivityBuilder()
                .setUseInstrumentation()
                .setTargetActivity(ENTRY_POINT_ALIAS_ACTIVITY)
                .setWaitForLaunched(true)
                .execute();
        final LogMaker metricsLog = getMetricsLog(SINGLE_TASK_ACTIVITY, APP_TRANSITION);
        final long postUptimeMs = SystemClock.uptimeMillis();
        assertMetricsLogs(SINGLE_TASK_ACTIVITY, APP_TRANSITION, metricsLog, mPreUptimeMs,
                        postUptimeMs);
    }

    private LogMaker getMetricsLog(ComponentName componentName, int category) {
        final Queue<LogMaker> startLogs = MetricsAsserts.findMatchingLogs(mMetricsReader,
                new LogMaker(category));
        for (LogMaker log : startLogs) {
            final String actualClassName = (String) log.getTaggedData(FIELD_CLASS_NAME);
            final String actualPackageName = log.getPackageName();
            if (componentName.getClassName().equals(actualClassName) &&
                    componentName.getPackageName().equals(actualPackageName)) {
                return log;
            }
        }
        return null;
    }

    private void assertLogsContain(String[] logs, String expectedLog) {
        for (String line : logs) {
            if (line.contains(expectedLog)) {
                return;
            }
        }
        fail("Expected to find '" + expectedLog + "' in " + Arrays.toString(logs));
    }
}
