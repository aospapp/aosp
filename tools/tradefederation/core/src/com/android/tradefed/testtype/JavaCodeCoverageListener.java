/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tradefed.testtype;

import static com.google.common.base.Verify.verifyNotNull;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.util.FileUtil;

import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

/**
 * A {@link ResultForwarder} that will pull Java coverage measurements off of the device and log
 * them as test artifacts.
 */
final class JavaCodeCoverageListener extends ResultForwarder {

    public static final String MERGE_COVERAGE_MEASUREMENTS_TEST_NAME = "mergeCoverageMeasurements";
    public static final String COVERAGE_MEASUREMENT_KEY = "coverageFilePath";

    private final ITestDevice mDevice;

    private final boolean mMergeCoverageMeasurements;

    private final ExecFileLoader mExecFileLoader = new ExecFileLoader();

    private String mCurrentRunName;

    public JavaCodeCoverageListener(
            ITestDevice device, boolean mergeMeasurements, ITestInvocationListener... listeners) {
        super(listeners);
        mDevice = device;
        mMergeCoverageMeasurements = mergeMeasurements;
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        super.testRunStarted(runName, testCount);
        mCurrentRunName = runName;
    }

    @Override
    public void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        if (MERGE_COVERAGE_MEASUREMENTS_TEST_NAME.equals(mCurrentRunName)) {
            // Log the merged runtime coverage measurement.
            try {
                File mergedMeasurements =
                        FileUtil.createTempFile(
                                "merged_runtime_coverage_",
                                "." + LogDataType.COVERAGE.getFileExt());

                mExecFileLoader.save(mergedMeasurements, false);

                // Save the merged measurement as a test log.
                try (FileInputStreamSource source =
                        new FileInputStreamSource(mergedMeasurements, true)) {
                    testLog("merged_runtime_coverage", LogDataType.COVERAGE, source);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                super.testRunEnded(elapsedTime, runMetrics);
            }
        } else {
            // Get the path of the coverage measurement on the device.
            Metric devicePathMetric = runMetrics.get(COVERAGE_MEASUREMENT_KEY);
            if (devicePathMetric == null) {
                super.testRunFailed("No coverage measurement.");
                super.testRunEnded(elapsedTime, runMetrics);
                return;
            }
            String devicePath = devicePathMetric.getMeasurements().getSingleString();
            if (devicePath == null) {
                super.testRunFailed("No coverage measurement.");
                super.testRunEnded(elapsedTime, runMetrics);
                return;
            }

            File coverageFile = null;
            try {
                coverageFile = mDevice.pullFile(devicePath);
                verifyNotNull(coverageFile, "Failed to pull the coverage file from %s", devicePath);

                // When merging, load the measurement data. Otherwise log the measurement
                // immediately.
                if (mMergeCoverageMeasurements) {
                    mExecFileLoader.load(coverageFile);
                } else {
                    try (FileInputStreamSource source =
                            new FileInputStreamSource(coverageFile, true)) {
                        testLog(
                                mCurrentRunName + "_runtime_coverage",
                                LogDataType.COVERAGE,
                                source);
                    }
                }
            } catch (DeviceNotAvailableException | IOException e) {
                throw new RuntimeException(e);
            } finally {
                FileUtil.deleteFile(coverageFile);
                super.testRunEnded(elapsedTime, runMetrics);
            }
        }
    }
}
