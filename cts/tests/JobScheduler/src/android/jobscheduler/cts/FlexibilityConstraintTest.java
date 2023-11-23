/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.jobscheduler.cts;

import android.app.job.JobInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;

import androidx.test.uiautomator.UiDevice;

import com.android.compatibility.common.util.BatteryUtils;
import com.android.compatibility.common.util.DeviceConfigStateHelper;

public class FlexibilityConstraintTest extends BaseJobSchedulerTest {
    private static final String TAG = "FlexibilityConstraintTest";
    public static final int FLEXIBLE_JOB_ID = FlexibilityConstraintTest.class.hashCode();
    private static final long FLEXIBILITY_TIMEOUT_MILLIS = 5_000;
    private UiDevice mUiDevice;

    // Store previous values.
    private String mInitialDisplayTimeout;
    private String mPreviousLowPowerTriggerLevel;

    private DeviceConfigStateHelper mAlarmManagerDeviceConfigStateHelper;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mUiDevice = UiDevice.getInstance(getInstrumentation());

        mAlarmManagerDeviceConfigStateHelper =
                new DeviceConfigStateHelper(DeviceConfig.NAMESPACE_ALARM_MANAGER);
        mAlarmManagerDeviceConfigStateHelper
                .set("delay_nonwakeup_alarms_while_screen_off", "false");

        // Using jobs with no deadline, but having a short fallback deadline, lets us test jobs
        // whose lifecycle is smaller than the minimum allowed by JobStatus.
        mDeviceConfigStateHelper.set(
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setLong("fc_flexibility_deadline_proximity_limit_ms", 0L)
                        .setLong("fc_fallback_flexibility_deadline_ms", 100_000)
                        .setLong("fc_min_time_between_flexibility_alarms_ms", 0L)
                        .setString("fc_percents_to_drop_num_flexible_constraints", "3,6,12,25")
                        .setBoolean("fc_enable_flexibility", true)
                        .build());

        // Disable power save mode.
        mPreviousLowPowerTriggerLevel = Settings.Global.getString(getContext().getContentResolver(),
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL);
        Settings.Global.putInt(getContext().getContentResolver(),
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);

        // Make sure the screen doesn't turn off when the test turns it on.
        mInitialDisplayTimeout = mUiDevice.executeShellCommand(
                "settings get system screen_off_timeout");
        mUiDevice.executeShellCommand("settings put system screen_off_timeout 300000");

        // Satisfy no constraints by default.
        satisfySystemWideConstraints(false, false, false);
    }

    @Override
    public void tearDown() throws Exception {
        mJobScheduler.cancel(FLEXIBLE_JOB_ID);

        mDeviceConfigStateHelper.restoreOriginalValues();
        mAlarmManagerDeviceConfigStateHelper.restoreOriginalValues();
        mUiDevice.executeShellCommand(
                "settings put system screen_off_timeout " + mInitialDisplayTimeout);
        Settings.Global.putString(getContext().getContentResolver(),
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, mPreviousLowPowerTriggerLevel);

        super.tearDown();
    }

    /**
     * Tests that flex constraints don't affect jobs on devices that don't support flex constraints.
     */
    public void testUnsupportedDevice() throws Exception {
        if (deviceSupportsFlexConstraints()) {
            return;
        }
        // Make it so that constraints won't drop in time.
        mDeviceConfigStateHelper.set("fc_percents_to_drop_num_flexible_constraints", "25,30,35,50");
        kTestEnvironment.setExpectedExecutions(1);
        // TODO(236261941): add prefer*Constraint* APIs
        JobInfo job = new JobInfo.Builder(FLEXIBLE_JOB_ID, kJobServiceComponent).build();
        mJobScheduler.schedule(job);

        // Job should fire even though constraints haven't dropped.
        runJob();
        assertTrue("Job didn't fire on unsupported flex constraint device",
                kTestEnvironment.awaitExecution(FLEXIBILITY_TIMEOUT_MILLIS));
    }

    public void testNoConstraintSatisfied_noPreferred() throws Exception {
        if (!deviceSupportsFlexConstraints()) {
            return;
        }
        satisfySystemWideConstraints(false, false, false);
        kTestEnvironment.setExpectedExecutions(1);
        JobInfo job = new JobInfo.Builder(FLEXIBLE_JOB_ID, kJobServiceComponent).build();
        mJobScheduler.schedule(job);
        runJob();

        assertTrue("Job without flexible constraint did not fire when no constraints were required",
                kTestEnvironment.awaitExecution(FLEXIBILITY_TIMEOUT_MILLIS));
    }

    /**
     * Schedule an expedited job, verify it runs immediately.
     */
    public void testExpeditedJobBypassFlexibility() throws Exception {
        JobInfo job = new JobInfo.Builder(FLEXIBLE_JOB_ID, kJobServiceComponent)
                .setExpedited(true)
                .build();
        kTestEnvironment.setExpectedExecutions(1);
        mJobScheduler.schedule(job);
        runJob();
        assertTrue("Expedited job did not start.",
                kTestEnvironment.awaitExecution(FLEXIBILITY_TIMEOUT_MILLIS));
    }

    private boolean deviceSupportsFlexConstraints() {
        // Flex constraints are disabled on auto devices.
        return !getContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private void satisfyAllSystemWideConstraints() throws Exception {
        satisfySystemWideConstraints(true, true, true);
    }

    private void satisfySystemWideConstraints(
            boolean charging, boolean batteryNotLow, boolean idle) throws Exception {
        if (BatteryUtils.hasBattery()) {
            setBatteryState(charging, batteryNotLow ? 100 : 5);
        }
        toggleIdle(idle);
    }

    private void toggleIdle(boolean state) throws Exception {
        toggleScreenOn(!state);
        IdleConstraintTest.triggerIdleMaintenance(mUiDevice);
    }

    void assertJobNotReady() throws Exception {
        assertJobNotReady(FLEXIBLE_JOB_ID);
    }

    private void runJob() throws Exception {
        mUiDevice.executeShellCommand("cmd jobscheduler run -s"
                + " -u " + UserHandle.myUserId()
                + " " + kJobServiceComponent.getPackageName()
                + " " + FLEXIBLE_JOB_ID);
    }
}
