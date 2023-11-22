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
 * limitations under the License.
 */

package android.server.cts;

import com.android.tradefed.device.DeviceNotAvailableException;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class ActivityManagerAmStartOptionsTests extends ActivityManagerTestBase {

    private static final String TEST_ACTIVITY_NAME = "TestActivity";
    private static final String ENTRYPOINT_ACTIVITY_NAME = "EntryPointAliasActivity";
    private static final String SINGLE_TASK_ACTIVITY_NAME = "SingleTaskActivity";

    public void testDashD() throws Exception {
        final String[] waitForActivitiesVisible = new String[] {TEST_ACTIVITY_NAME};
        AmStartLogcatVerifier verifier = new AmStartLogcatVerifier("android.server.app", TEST_ACTIVITY_NAME);

        // Run at least 2 rounds to verify that -D works with an existing process.
        // -D could fail in this case if the force stop of process is broken.
        for (int i = 0; i < 2; i++) {
            clearLogcat();
            executeShellCommand(getAmStartCmd(TEST_ACTIVITY_NAME) + " -D");

            // visibleOnly=false as the first window popping up will be the debugger window.
            mAmWmState.computeState(mDevice, false, waitForActivitiesVisible);
            verifier.verifyDashD();
        }
    }

    public void testDashW_Direct() throws Exception {
        testDashW(SINGLE_TASK_ACTIVITY_NAME, SINGLE_TASK_ACTIVITY_NAME);
    }

    public void testDashW_Indirect() throws Exception {
        testDashW(ENTRYPOINT_ACTIVITY_NAME, SINGLE_TASK_ACTIVITY_NAME);
    }

    private void testDashW(final String entryActivity, final String actualActivity)
            throws Exception {
        AmStartLogcatVerifier verifier = new AmStartLogcatVerifier("android.server.app", actualActivity);

        // Test cold start
        startActivityAndVerifyResult(verifier, entryActivity, actualActivity, true);

        // Test warm start
        pressHomeButton();
        startActivityAndVerifyResult(verifier, entryActivity, actualActivity, false);

        // Test "hot" start (app already in front)
        startActivityAndVerifyResult(verifier, entryActivity, actualActivity, false);
    }

    private static final Pattern sNotStartedWarningPattern = Pattern.compile(
            "Warning: Activity not started(.*)");
    private static final Pattern sStatusPattern = Pattern.compile(
            "Status: (.*)");
    private static final Pattern sActivityPattern = Pattern.compile(
            "Activity: (.*)");
    private static final String sStatusOk = "ok";

    private void startActivityAndVerifyResult(
            final AmStartLogcatVerifier verifier, final String entryActivity,
            final String actualActivity, boolean shouldStart) throws Exception {
        clearLogcat();

        // Pass in different data only when cold starting. This is to make the intent
        // different in subsequent warm/hot launches, so that the entrypoint alias
        // activity is always started, but the actual activity is not started again
        // because of the NEW_TASK and singleTask flags.
        final String result = executeShellCommand(getAmStartCmd(entryActivity) + " -W"
                + (shouldStart ? " -d about:blank" : ""));

        // Verify shell command return value
        verifyShellOutput(result, actualActivity, shouldStart);

        // Verify adb logcat log
        verifier.verifyDashW(shouldStart);
    }

    private void verifyShellOutput(
            final String result, final String activity, boolean shouldStart) {
        boolean warningFound = false;
        String status = null;
        String reportedActivity = null;
        String componentActivityName = getActivityComponentName(activity);

        for (String line : result.split("\\n")) {
            Matcher matcher = sNotStartedWarningPattern.matcher(line);
            if (matcher.matches()) {
                warningFound = true;
                continue;
            }
            matcher = sStatusPattern.matcher(line);
            if (matcher.matches()) {
                status = matcher.group(1);
                continue;
            }
            matcher = sActivityPattern.matcher(line);
            if (matcher.matches()) {
                reportedActivity = matcher.group(1);
                continue;
            }
        }

        assertTrue("Status " + status + " is not ok", sStatusOk.equals(status));
        assertTrue("Reported activity is " + reportedActivity + " not " + componentActivityName,
                componentActivityName.equals(reportedActivity));

        if (shouldStart && warningFound) {
            fail("Should start new activity but brought something to front.");
        } else if (!shouldStart && !warningFound){
            fail("Should bring existing activity to front but started new activity.");
        }
    }

    private static final Pattern sStartProcPattern =
            Pattern.compile("(.+): Start proc (\\d+):(.*) for activity (.*)");
    private static final Pattern sKillingPattern =
            Pattern.compile("(.+): Killing (\\d+):(.*)");
    private static final Pattern sWaitingForDebuggerPattern =
            Pattern.compile("(.+): Application (.+) is waiting for the debugger (.*)");
    private static final Pattern sDisplayTimePattern =
            Pattern.compile("(.+): Displayed (.*): (\\+{0,1})([0-9]+)ms(.*)");

    private class AmStartLogcatVerifier {
        private String mPrevProcId;
        private final String mPackageName;
        private final String mActivityName;

        AmStartLogcatVerifier(String packageName, String activityName) {
            mPackageName = packageName;
            mActivityName = activityName;
        }

        void verifyDashD() throws DeviceNotAvailableException {
            boolean prevProcKilled = false;;
            boolean waitingForDebugger = false;
            String newProcId = null;
            final String[] componentNames = new String[] {"ActivityManager", "ActivityThread"};

            for (String line : getDeviceLogsForComponents(componentNames)) {
                line = line.trim();

                Matcher matcher = sStartProcPattern.matcher(line);
                if (matcher.matches()) {
                    final String activity = matcher.group(4);
                    if (activity.contains(mActivityName)) {
                        newProcId = matcher.group(2);
                    }
                    continue;
                }

                matcher = sKillingPattern.matcher(line);
                if (matcher.matches()) {
                    final String procId = matcher.group(2);
                    if (procId.equals(mPrevProcId)) {
                        prevProcKilled = true;
                    }
                    continue;
                }

                matcher = sWaitingForDebuggerPattern.matcher(line);
                if (matcher.matches()) {
                    final String packageName = matcher.group(2);
                    if (packageName.equals(mPackageName)) {
                        waitingForDebugger = true;
                    }
                    continue;
                }
            }

            assertTrue("Didn't kill exisiting proc " + mPrevProcId + ".",
                    mPrevProcId == null || prevProcKilled);
            assertTrue("Didn't start new proc.", newProcId != null);
            assertTrue("Didn't wait for debugger.", waitingForDebugger);

            mPrevProcId = newProcId;
        }

        void verifyDashW(boolean shouldStart) throws DeviceNotAvailableException {
            int displayCount = 0;
            String activityName = null;

            for (String line : getDeviceLogsForComponent("ActivityManager")) {
                line = line.trim();

                Matcher matcher = sDisplayTimePattern.matcher(line);
                if (matcher.matches()) {
                    activityName = matcher.group(2);
                    // Ignore activitiy displays from other packages, we don't
                    // want some random activity starts to ruin our test.
                    if (!activityName.startsWith("android.server.app")) {
                        continue;
                    }
                    if (!shouldStart) {
                        fail("Shouldn't display anything but displayed " + activityName);
                    }
                    displayCount++;
                }
            }
            final String expectedActivityName = getActivityComponentName(mActivityName);
            if (shouldStart) {
                if (displayCount != 1) {
                    fail("Should display exactly one activity but displayed " + displayCount);
                } else if (!expectedActivityName.equals(activityName)) {
                    fail("Should display " + expectedActivityName +
                            " but displayed " + activityName);
                }
            }
        }
    }
}
