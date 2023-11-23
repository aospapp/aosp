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
 * limitations under the License.
 */
package com.android.tradefed.device.metric;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.device.ILogcatReceiver;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.LogcatReceiver;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.util.HashMap;
import java.util.Map;

/** Collector that will capture and log a logcat when a test case fails. */
public class LogcatOnFailureCollector extends BaseDeviceMetricCollector {

    private static final int MAX_LOGAT_SIZE_BYTES = 4 * 1024 * 1024;
    /** Always include a bit of prior data to capture what happened before */
    private static final int OFFSET_CORRECTION = 20000;

    private static final String NAME_FORMAT = "%s-%s-logcat-on-failure";

    private Map<ITestDevice, ILogcatReceiver> mLogcatReceivers = new HashMap<>();
    private Map<ITestDevice, Integer> mOffset = new HashMap<>();

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        for (ITestDevice device : getDevices()) {
            // In case of multiple runs for the same test runner, re-init the receiver.
            initReceiver(device);
            // Get the current offset of the buffer to be able to query later
            int offset = (int) mLogcatReceivers.get(device).getLogcatData().size();
            if (offset > OFFSET_CORRECTION) {
                offset -= OFFSET_CORRECTION;
            }
            mOffset.put(device, offset);
        }
    }

    @Override
    public void onTestStart(DeviceMetricData testData) {
        // TODO: Handle the buffer to reset it at the test start
    }

    @Override
    public void onTestFail(DeviceMetricData testData, TestDescription test) {
        for (ITestDevice device : getDevices()) {
            // Delay slightly for the error to get in the logcat
            getRunUtil().sleep(100);
            try (InputStreamSource logcatSource =
                    mLogcatReceivers
                            .get(device)
                            .getLogcatData(MAX_LOGAT_SIZE_BYTES, mOffset.get(device))) {
                String name = String.format(NAME_FORMAT, test.toString(), device.getSerialNumber());
                super.testLog(name, LogDataType.LOGCAT, logcatSource);
            }
        }
    }

    @Override
    public void onTestRunEnd(DeviceMetricData runData, Map<String, Metric> currentRunMetrics) {
        clearReceivers();
    }

    @VisibleForTesting
    ILogcatReceiver createLogcatReceiver(ITestDevice device) {
        return new LogcatReceiver(device, "logcat", device.getOptions().getMaxLogcatDataSize(), 0);
    }

    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    private void initReceiver(ITestDevice device) {
        if (mLogcatReceivers.get(device) == null) {
            ILogcatReceiver receiver = createLogcatReceiver(device);
            mLogcatReceivers.put(device, receiver);
            receiver.start();
        }
    }

    private void clearReceivers() {
        for (ILogcatReceiver receiver : mLogcatReceivers.values()) {
            receiver.stop();
            receiver.clear();
        }
        mLogcatReceivers.clear();
        mOffset.clear();
    }
}
