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

package com.android.tradefed.testtype;

import static com.google.common.base.Verify.verify;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.common.base.Splitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;

/**
 * A {@link ResultForwarder} that will pull native coverage measurements off of the device and log
 * them as test artifacts.
 */
public final class NativeCodeCoverageListener extends ResultForwarder {

    private static final String NATIVE_COVERAGE_DEVICE_PATH = "/data/misc/trace/proc/self/cwd/out";
    private static final String COVERAGE_FILE_LIST_COMMAND =
            String.format("find %s -name '*.gcda'", NATIVE_COVERAGE_DEVICE_PATH);

    private final ITestDevice mDevice;

    private String mCurrentRunName;

    public NativeCodeCoverageListener(ITestDevice device, ITestInvocationListener... listeners) {
        super(listeners);
        mDevice = device;
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        super.testRunStarted(runName, testCount);
        mCurrentRunName = runName;
    }

    @Override
    public void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        // Retrieve the list of .gcda files from the device.  Don't use pullDir since it will
        // not pull from hidden directories.  Keep the path of the files on the device in the
        // local directory so that the .gcda files can be mapped to the correct .gcno file.
        File localDir = null;
        try {
            localDir = FileUtil.createTempDir("native_coverage");

            // Enable abd root on the device, otherwise the list command will fail.
            verify(mDevice.enableAdbRoot(), "Failed to enable adb root.");
            String findResult = mDevice.executeShellCommand(COVERAGE_FILE_LIST_COMMAND);

            Path devicePathRoot = Paths.get(NATIVE_COVERAGE_DEVICE_PATH);
            for (String deviceFile : Splitter.on("\n").omitEmptyStrings().split(findResult)) {
                // Compute the relative path for the device file.
                Path relativePath = devicePathRoot.relativize(Paths.get(deviceFile));
                Path localFullPath = localDir.toPath().resolve(relativePath);

                // Create parent directories and pull the file.
                Files.createDirectories(localFullPath.getParent());
                verify(
                        mDevice.pullFile(deviceFile, localFullPath.toFile()),
                        "Failed to pull the coverage file from %s",
                        deviceFile);
            }

            // Zip the contents of the localDir (not including localDir in the path) and log
            // the resulting file.
            File coverageZip =
                    ZipUtil.createZip(
                            Arrays.asList(localDir.listFiles()),
                            mCurrentRunName + "_native_runtime_coverage");

            try (FileInputStreamSource source = new FileInputStreamSource(coverageZip, true)) {
                testLog(
                        mCurrentRunName + "_native_runtime_coverage",
                        LogDataType.NATIVE_COVERAGE,
                        source);
            }
        } catch (DeviceNotAvailableException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            FileUtil.recursiveDelete(localDir);
            super.testRunEnded(elapsedTime, runMetrics);
        }
    }
}
