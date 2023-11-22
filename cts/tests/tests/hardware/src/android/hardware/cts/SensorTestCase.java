/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.cts;

import com.android.cts.util.ReportLog;
import com.android.cts.util.ResultType;
import com.android.cts.util.ResultUnit;

import android.app.Instrumentation;
import android.cts.util.DeviceReportLog;
import android.hardware.Sensor;
import android.hardware.cts.helpers.SensorStats;
import android.hardware.cts.helpers.SensorTestStateNotSupportedException;
import android.hardware.cts.helpers.TestSensorEnvironment;
import android.hardware.cts.helpers.sensoroperations.ISensorOperation;
import android.test.AndroidTestCase;
import android.util.Log;

/**
 * Test Case class that handles gracefully sensors that are not available in the device.
 */
public abstract class SensorTestCase extends AndroidTestCase {
    // TODO: consolidate all log tags
    protected final String LOG_TAG = "TestRunner";

    /**
     * By default tests need to run in a {@link TestSensorEnvironment} that assumes each sensor is
     * running with a load of several listeners, requesting data at different rates.
     *
     * In a better world the component acting as builder of {@link ISensorOperation} would compute
     * this value based on the tests composed.
     *
     * Ideally, each {@link Sensor} object would expose this information to clients.
     */
    private volatile boolean mEmulateSensorUnderLoad = true;

    protected SensorTestCase() {}

    @Override
    public void runTest() throws Throwable {
        try {
            super.runTest();
        } catch (SensorTestStateNotSupportedException e) {
            // the sensor state is not supported in the device, log a warning and skip the test
            Log.w(LOG_TAG, e.getMessage());
        }
    }

    public void setEmulateSensorUnderLoad(boolean value) {
        mEmulateSensorUnderLoad = value;
    }

    protected boolean shouldEmulateSensorUnderLoad() {
        return mEmulateSensorUnderLoad;
    }

    /**
     * Utility method to log selected stats to a {@link ReportLog} object.  The stats must be
     * a number or an array of numbers.
     */
    public static void logSelectedStatsToReportLog(Instrumentation instrumentation, int depth,
            String[] keys, SensorStats stats) {
        DeviceReportLog reportLog = new DeviceReportLog(depth);

        for (String key : keys) {
            Object value = stats.getValue(key);
            if (value instanceof Integer) {
                reportLog.printValue(key, (Integer) value, ResultType.NEUTRAL, ResultUnit.NONE);
            } else if (value instanceof Double) {
                reportLog.printValue(key, (Double) value, ResultType.NEUTRAL, ResultUnit.NONE);
            } else if (value instanceof Float) {
                reportLog.printValue(key, (Float) value, ResultType.NEUTRAL, ResultUnit.NONE);
            } else if (value instanceof double[]) {
                reportLog.printArray(key, (double[]) value, ResultType.NEUTRAL, ResultUnit.NONE);
            } else if (value instanceof float[]) {
                float[] tmpFloat = (float[]) value;
                double[] tmpDouble = new double[tmpFloat.length];
                for (int i = 0; i < tmpDouble.length; i++) tmpDouble[i] = tmpFloat[i];
                reportLog.printArray(key, tmpDouble, ResultType.NEUTRAL, ResultUnit.NONE);
            }
        }

        reportLog.printSummary("summary", 0, ResultType.NEUTRAL, ResultUnit.NONE);
        reportLog.deliverReportToHost(instrumentation);
    }
}
