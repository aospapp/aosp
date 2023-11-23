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
package com.android.tradefed.testtype.binary;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.util.HashMap;

/** Unit tests for {@link ExecutableHostTest}. */
@RunWith(JUnit4.class)
public class ExecutableHostTestTest {

    private ExecutableHostTest mExecutableTest;
    private ITestInvocationListener mMockListener;
    private ITestDevice mMockDevice;
    private IRunUtil mMockRunUtil;

    @Before
    public void setUp() {
        mMockListener = Mockito.mock(ITestInvocationListener.class);
        mMockDevice = Mockito.mock(ITestDevice.class);
        mMockRunUtil = Mockito.mock(IRunUtil.class);
        mExecutableTest =
                new ExecutableHostTest() {
                    @Override
                    IRunUtil createRunUtil() {
                        return mMockRunUtil;
                    }
                };
        mExecutableTest.setDevice(mMockDevice);
    }

    @Test
    public void testRunHostExecutable_noBinaries() throws Exception {
        mExecutableTest.run(mMockListener);

        verify(mMockListener, times(0)).testRunStarted(any(), anyInt());
    }

    @Test
    public void testRunHostExecutable_doesNotExists() throws Exception {
        String path = "/does/not/exists/path/bin/test";
        OptionSetter setter = new OptionSetter(mExecutableTest);
        setter.setOptionValue("binary", path);

        mExecutableTest.run(mMockListener);

        verify(mMockListener, Mockito.times(1)).testRunStarted(eq("test"), eq(0));
        verify(mMockListener, Mockito.times(1))
                .testRunFailed(String.format(ExecutableBaseTest.NO_BINARY_ERROR, path));
        verify(mMockListener, Mockito.times(1))
                .testRunEnded(eq(0L), Mockito.<HashMap<String, Metric>>any());
    }

    @Test
    public void testRunHostExecutable() throws Exception {
        File tmpBinary = FileUtil.createTempFile("test-executable", "");
        try {
            OptionSetter setter = new OptionSetter(mExecutableTest);
            setter.setOptionValue("binary", tmpBinary.getAbsolutePath());

            CommandResult result = new CommandResult(CommandStatus.SUCCESS);
            doReturn(result)
                    .when(mMockRunUtil)
                    .runTimedCmd(Mockito.anyLong(), Mockito.eq(tmpBinary.getAbsolutePath()));

            mExecutableTest.run(mMockListener);

            verify(mMockListener, Mockito.times(1)).testRunStarted(eq(tmpBinary.getName()), eq(1));
            verify(mMockListener, Mockito.times(0)).testRunFailed(any());
            verify(mMockListener, Mockito.times(0)).testFailed(any(), any());
            verify(mMockListener, Mockito.times(1))
                    .testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());
        } finally {
            FileUtil.recursiveDelete(tmpBinary);
        }
    }

    @Test
    public void testRunHostExecutable_dnae() throws Exception {
        File tmpBinary = FileUtil.createTempFile("test-executable", "");
        try {
            OptionSetter setter = new OptionSetter(mExecutableTest);
            setter.setOptionValue("binary", tmpBinary.getAbsolutePath());

            CommandResult result = new CommandResult(CommandStatus.SUCCESS);
            doReturn(result)
                    .when(mMockRunUtil)
                    .runTimedCmd(Mockito.anyLong(), Mockito.eq(tmpBinary.getAbsolutePath()));

            doThrow(new DeviceNotAvailableException()).when(mMockDevice).waitForDeviceAvailable();
            try {
                mExecutableTest.run(mMockListener);
                fail("Should have thrown an exception.");
            } catch (DeviceNotAvailableException expected) {
                // Expected
            }

            verify(mMockListener, Mockito.times(1)).testRunStarted(eq(tmpBinary.getName()), eq(1));
            verify(mMockListener, Mockito.times(1))
                    .testRunFailed(
                            eq(
                                    String.format(
                                            "Device became unavailable after %s.",
                                            tmpBinary.getAbsolutePath())));
            verify(mMockListener, Mockito.times(0)).testFailed(any(), any());
            verify(mMockListener, Mockito.times(1))
                    .testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());
        } finally {
            FileUtil.recursiveDelete(tmpBinary);
        }
    }

    /** If the binary is available from the tests directory we can find it and run it. */
    @Test
    public void testRunHostExecutable_search() throws Exception {
        File testsDir = FileUtil.createTempDir("executable-tests-dir");
        File tmpBinary = FileUtil.createTempFile("test-executable", "", testsDir);
        try {
            IDeviceBuildInfo info = new DeviceBuildInfo();
            info.setTestsDir(testsDir, "testversion");
            mExecutableTest.setBuild(info);
            OptionSetter setter = new OptionSetter(mExecutableTest);
            setter.setOptionValue("binary", tmpBinary.getName());

            CommandResult result = new CommandResult(CommandStatus.SUCCESS);
            doReturn(result)
                    .when(mMockRunUtil)
                    .runTimedCmd(Mockito.anyLong(), Mockito.eq(tmpBinary.getAbsolutePath()));

            mExecutableTest.run(mMockListener);

            verify(mMockListener, Mockito.times(1)).testRunStarted(eq(tmpBinary.getName()), eq(1));
            verify(mMockListener, Mockito.times(0)).testRunFailed(any());
            verify(mMockListener, Mockito.times(0)).testFailed(any(), any());
            verify(mMockListener, Mockito.times(1))
                    .testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());
        } finally {
            FileUtil.recursiveDelete(testsDir);
        }
    }

    @Test
    public void testRunHostExecutable_notFound() throws Exception {
        File testsDir = FileUtil.createTempDir("executable-tests-dir");
        File tmpBinary = FileUtil.createTempFile("test-executable", "", testsDir);
        try {
            IDeviceBuildInfo info = new DeviceBuildInfo();
            info.setTestsDir(testsDir, "testversion");
            mExecutableTest.setBuild(info);
            OptionSetter setter = new OptionSetter(mExecutableTest);
            setter.setOptionValue("binary", tmpBinary.getName());
            tmpBinary.delete();

            CommandResult result = new CommandResult(CommandStatus.SUCCESS);
            doReturn(result)
                    .when(mMockRunUtil)
                    .runTimedCmd(Mockito.anyLong(), Mockito.eq(tmpBinary.getAbsolutePath()));

            mExecutableTest.run(mMockListener);

            verify(mMockListener, Mockito.times(1)).testRunStarted(eq(tmpBinary.getName()), eq(0));
            verify(mMockListener, Mockito.times(1))
                    .testRunFailed(
                            eq(
                                    String.format(
                                            ExecutableBaseTest.NO_BINARY_ERROR,
                                            tmpBinary.getName())));
            verify(mMockListener, Mockito.times(1))
                    .testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());
        } finally {
            FileUtil.recursiveDelete(testsDir);
        }
    }

    @Test
    public void testRunHostExecutable_failure() throws Exception {
        File tmpBinary = FileUtil.createTempFile("test-executable", "");
        try {
            OptionSetter setter = new OptionSetter(mExecutableTest);
            setter.setOptionValue("binary", tmpBinary.getAbsolutePath());

            CommandResult result = new CommandResult(CommandStatus.FAILED);
            result.setExitCode(5);
            result.setStdout("stdout");
            doReturn(result)
                    .when(mMockRunUtil)
                    .runTimedCmd(Mockito.anyLong(), Mockito.eq(tmpBinary.getAbsolutePath()));

            mExecutableTest.run(mMockListener);

            verify(mMockListener, Mockito.times(1)).testRunStarted(eq(tmpBinary.getName()), eq(1));
            verify(mMockListener, Mockito.times(0)).testRunFailed(any());
            verify(mMockListener, Mockito.times(1)).testFailed(any(), eq("stdout\nExit Code: 5"));
            verify(mMockListener, Mockito.times(1))
                    .testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());
        } finally {
            FileUtil.recursiveDelete(tmpBinary);
        }
    }
}
