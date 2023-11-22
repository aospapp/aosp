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

import static com.android.tradefed.testtype.JavaCodeCoverageListener.MERGE_COVERAGE_MEASUREMENTS_TEST_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.base.VerifyException;
import com.google.protobuf.ByteString;

import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.internal.data.CRC64;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link JavaCodeCoverageListener}. */
@RunWith(JUnit4.class)
public class JavaCodeCoverageListenerTest {

    private static final int PROBE_COUNT = 10;

    private static final String RUN_NAME = "SomeTest";
    private static final int TEST_COUNT = 5;
    private static final long ELAPSED_TIME = 1000;

    private static final String DEVICE_PATH = "/some/path/on/the/device.ec";
    private static final ByteString COVERAGE_MEASUREMENT =
            ByteString.copyFromUtf8("Mi estas kovrado mezurado");

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Mock ITestDevice mMockDevice;

    @Spy LogFileReader mFakeListener = new LogFileReader();

    /** Object under test. */
    JavaCodeCoverageListener mCodeCoverageListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCodeCoverageListener = new JavaCodeCoverageListener(mMockDevice, false, mFakeListener);
    }

    @Test
    public void test() throws DeviceNotAvailableException, IOException {
        // Setup mocks.
        File coverageFile = folder.newFile("coverage.ec");
        try (OutputStream out = new FileOutputStream(coverageFile)) {
            COVERAGE_MEASUREMENT.writeTo(out);
        }
        doReturn(coverageFile).when(mMockDevice).pullFile(DEVICE_PATH);

        // Simulate a test run.
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        metric.put("coverageFilePath", DEVICE_PATH);
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify testLog(..) was called with the coverage file.
        verify(mFakeListener)
                .testLog(anyString(), eq(LogDataType.COVERAGE), eq(COVERAGE_MEASUREMENT));
    }

    @Test
    public void testFailure_noCoverageMetric() {
        // Simulate a test run.
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, new HashMap<String, Metric>());

        // Verify that the test run is marked as a failure.
        verify(mFakeListener).testRunFailed(anyString());

        // Verify testLog(..) was not called.
        verify(mFakeListener, never())
                .testLog(anyString(), eq(LogDataType.COVERAGE), any(InputStreamSource.class));
    }

    @Test
    public void testFailure_unableToPullFile() throws DeviceNotAvailableException {
        // Setup mocks.
        doReturn(null).when(mMockDevice).pullFile(DEVICE_PATH);

        // Simulate a test run.
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);

        try {
            Map<String, String> metric = new HashMap<>();
            metric.put("coverageFilePath", DEVICE_PATH);
            mCodeCoverageListener.testRunEnded(
                    ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));
            fail("Exception not thrown");
        } catch (VerifyException expected) {
        }

        // Verify testLog(..) was not called.
        verify(mFakeListener, never())
                .testLog(anyString(), eq(LogDataType.COVERAGE), any(InputStreamSource.class));
    }

    @Test
    public void testMerge_producesSingleMeasurement()
            throws DeviceNotAvailableException, IOException {
        // Setup mocks.
        File coverageFile1 = folder.newFile("coverage1.ec");
        try (OutputStream out = new FileOutputStream(coverageFile1)) {
            ByteString measurement = measurement(fullyCovered(JavaCodeCoverageListener.class));
            measurement.writeTo(out);
        }

        File coverageFile2 = folder.newFile("coverage2.ec");
        try (OutputStream out = new FileOutputStream(coverageFile2)) {
            ByteString measurement =
                    measurement(
                            partiallyCovered(JavaCodeCoverageListener.class),
                            partiallyCovered(JavaCodeCoverageListenerTest.class));
            measurement.writeTo(out);
        }

        mCodeCoverageListener = new JavaCodeCoverageListener(mMockDevice, true, mFakeListener);

        Map<String, String> metric = new HashMap<>();
        metric.put("coverageFilePath", DEVICE_PATH);

        // Simulate a test run.
        doReturn(coverageFile1).doReturn(coverageFile2).when(mMockDevice).pullFile(DEVICE_PATH);

        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));
        mCodeCoverageListener.testRunStarted(RUN_NAME + "2", TEST_COUNT);
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));
        mCodeCoverageListener.testRunStarted(MERGE_COVERAGE_MEASUREMENTS_TEST_NAME, TEST_COUNT);
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, new HashMap<String, Metric>());

        // Capture the merged coverage measurements that were passed to the fake listener.
        ArgumentCaptor<ByteString> stream = ArgumentCaptor.forClass(ByteString.class);
        verify(mFakeListener).testLog(anyString(), eq(LogDataType.COVERAGE), stream.capture());

        // Check the contents of the merged file.
        ExecFileLoader execLoader = new ExecFileLoader();
        execLoader.load(stream.getValue().newInput());

        ExecutionDataStore execData = execLoader.getExecutionDataStore();
        boolean[] fullyCovered = new boolean[PROBE_COUNT];
        Arrays.fill(fullyCovered, Boolean.TRUE);

        assertThat(execData.contains(vmName(JavaCodeCoverageListener.class))).isTrue();
        assertThat(getProbes(JavaCodeCoverageListener.class, execData)).isEqualTo(fullyCovered);

        boolean[] partiallyCovered = new boolean[PROBE_COUNT];
        partiallyCovered[0] = true;
        assertThat(execData.contains(vmName(JavaCodeCoverageListenerTest.class))).isTrue();
        assertThat(getProbes(JavaCodeCoverageListenerTest.class, execData))
                .isEqualTo(partiallyCovered);
    }

    private static <T> String vmName(Class<T> clazz) {
        return clazz.getName().replace('.', '/');
    }

    private static <T> ExecutionData fullyCovered(Class<T> clazz) throws IOException {
        boolean[] probes = new boolean[PROBE_COUNT];
        Arrays.fill(probes, Boolean.TRUE);
        return new ExecutionData(classId(clazz), vmName(clazz), probes);
    }

    private static <T> ExecutionData partiallyCovered(Class<T> clazz) throws IOException {
        boolean[] probes = new boolean[PROBE_COUNT];
        probes[0] = true;
        return new ExecutionData(classId(clazz), vmName(clazz), probes);
    }

    private static <T> long classId(Class<T> clazz) throws IOException {
        return Long.valueOf(CRC64.classId(classBytes(clazz).toByteArray()));
    }

    private static <T> ByteString classBytes(Class<T> clazz) throws IOException {
        return ByteString.readFrom(
                clazz.getClassLoader().getResourceAsStream(vmName(clazz) + ".class"));
    }

    private static ByteString measurement(ExecutionData... data) throws IOException {
        ExecutionDataStore dataStore = new ExecutionDataStore();
        Arrays.stream(data).forEach(dataStore::put);

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            dataStore.accept(new ExecutionDataWriter(bytes));
            return ByteString.copyFrom(bytes.toByteArray());
        }
    }

    private static <T> boolean[] getProbes(Class<T> clazz, ExecutionDataStore execData)
            throws IOException {
        return execData.get(classId(clazz), vmName(clazz), PROBE_COUNT).getProbes();
    }

    /** An {@link ITestInvocationListener} which reads test log data streams for verification. */
    private static class LogFileReader implements ITestInvocationListener {
        /**
         * Reads the contents of the {@code dataStream} and forwards it to the {@link
         * #testLog(String, LogDataType, ByteString)} method.
         */
        @Override
        public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
            try (InputStream input = dataStream.createInputStream()) {
                testLog(dataName, dataType, ByteString.readFrom(input));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** No-op method for {@link Spy} verification. */
        public void testLog(String dataName, LogDataType dataType, ByteString data) {}
    }
}

