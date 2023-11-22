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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.base.VerifyException;
import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.ZipFile;

/** Unit tests for {@link NativeCodeCoverageListener}. */
@RunWith(JUnit4.class)
public class NativeCodeCoverageListenerTest {

    private static final String RUN_NAME = "SomeTest";
    private static final int TEST_COUNT = 5;
    private static final long ELAPSED_TIME = 1000;

    private static final ByteString COVERAGE_MEASUREMENT =
            ByteString.copyFromUtf8("Mi estas kovrado mezurado");

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Mock ITestDevice mMockDevice;

    LogFileReader mFakeListener = new LogFileReader();

    /** Object under test. */
    NativeCodeCoverageListener mCodeCoverageListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCodeCoverageListener = new NativeCodeCoverageListener(mMockDevice, mFakeListener);
    }

    @Test
    public void test_logsCoverageZip() throws DeviceNotAvailableException, IOException {
        // Setup mocks to write the coverage measurement to the file.
        doReturn(true).when(mMockDevice).enableAdbRoot();
        doReturn(
                        new StringJoiner("\n")
                                .add("/data/misc/trace/proc/self/cwd/out/path/to/coverage.gcda")
                                .add(
                                        "/data/misc/trace/proc/self/cwd/out/path/to/.hidden/coverage2.gcda")
                                .toString())
                .when(mMockDevice)
                .executeShellCommand(anyString());
        doAnswer(
                        inv -> {
                            File destFile = (File) inv.getArgument(1);
                            try (OutputStream out = new FileOutputStream(destFile)) {
                                // Write the filename as the contents.
                                out.write(destFile.getName().getBytes(StandardCharsets.UTF_8));
                            }
                            return true;
                        })
                .when(mMockDevice)
                .pullFile(anyString(), any());

        // Simulate a test run.
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify testLog(..) was called with the coverage file in a zip.
        List<ByteString> logs = mFakeListener.getLogs();
        assertThat(logs).hasSize(1);
        File outputZip = folder.newFile("coverage.zip");
        try (OutputStream out = new FileOutputStream(outputZip)) {
            logs.get(0).writeTo(out);
        }

        URI uri = URI.create(String.format("jar:file:%s", outputZip));
        try (FileSystem filesystem = FileSystems.newFileSystem(uri, new HashMap<>())) {
            Path path1 = filesystem.getPath("/path/to/coverage.gcda");
            assertThat(ByteString.readFrom(Files.newInputStream(path1)))
                    .isEqualTo(ByteString.copyFromUtf8("coverage.gcda"));

            Path path2 = filesystem.getPath("/path/to/.hidden/coverage2.gcda");
            assertThat(ByteString.readFrom(Files.newInputStream(path2)))
                    .isEqualTo(ByteString.copyFromUtf8("coverage2.gcda"));
        }
    }

    @Test
    public void testNoCoverageFiles_logsEmptyZip() throws DeviceNotAvailableException, IOException {
        doReturn(true).when(mMockDevice).enableAdbRoot();
        doReturn("").when(mMockDevice).executeShellCommand(anyString());

        // Simulate a test run.
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify testLog(..) was called with an empty zip.
        List<ByteString> logs = mFakeListener.getLogs();
        assertThat(logs).hasSize(1);
        File outputZip = folder.newFile("empty_coverage.zip");
        try (OutputStream out = new FileOutputStream(outputZip)) {
            logs.get(0).writeTo(out);
        }

        ZipFile loggedZip = new ZipFile(outputZip);
        assertThat(loggedZip.size()).isEqualTo(0);
    }

    @Test
    public void testFailure_unableToPullFile() throws DeviceNotAvailableException {
        // Setup mocks.
        doReturn(true).when(mMockDevice).enableAdbRoot();
        doReturn("/data/misc/trace/proc/self/cwd/out/some/path/to/coverage.gcda\n")
                .when(mMockDevice)
                .executeShellCommand(anyString());
        doReturn(false).when(mMockDevice).pullFile(anyString(), any());

        // Simulate a test run.
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);

        Map<String, String> metric = new HashMap<>();
        try {
            mCodeCoverageListener.testRunEnded(
                    ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));
            fail("an exception should have been thrown.");
        } catch (VerifyException e) {
            // Expected
        }

        // Verify testLog(..) was not called.
        assertThat(mFakeListener.getLogs()).isEmpty();
    }

    /** An {@link ITestInvocationListener} which reads test log data streams for verification. */
    private static class LogFileReader implements ITestInvocationListener {
        private List<ByteString> mLogs = new ArrayList<>();

        /** Reads the contents of the {@code dataStream} and saves it in the logs. */
        @Override
        public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
            try (InputStream input = dataStream.createInputStream()) {
                mLogs.add(ByteString.readFrom(input));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        List<ByteString> getLogs() {
            return new ArrayList<>(mLogs);
        }
    }
}
