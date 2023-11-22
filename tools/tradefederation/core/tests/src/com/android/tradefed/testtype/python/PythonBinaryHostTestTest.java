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
package com.android.tradefed.testtype.python;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.targetprep.adb.AdbStopServerPreparer;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.HashMap;

/** Unit tests for {@link PythonBinaryHostTest}. */
@RunWith(JUnit4.class)
public class PythonBinaryHostTestTest {
    private PythonBinaryHostTest mTest;
    private IRunUtil mMockRunUtil;
    private IBuildInfo mMockBuildInfo;
    private ITestDevice mMockDevice;
    private ITestInvocationListener mMockListener;
    private File mFakeAdb;

    @Before
    public void setUp() throws Exception {
        mFakeAdb = FileUtil.createTempFile("adb-python-tests", "");
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mTest =
                new PythonBinaryHostTest() {
                    @Override
                    IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    String getAdbPath() {
                        return mFakeAdb.getAbsolutePath();
                    }
                };
        EasyMock.expect(mMockBuildInfo.getFile(AdbStopServerPreparer.ADB_BINARY_KEY))
                .andReturn(null);
        mTest.setBuild(mMockBuildInfo);
        mTest.setDevice(mMockDevice);
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn("SERIAL");
        mMockRunUtil.setEnvVariable(PythonBinaryHostTest.ANDROID_SERIAL_VAR, "SERIAL");
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.deleteFile(mFakeAdb);
    }

    /** Test that when running a python binary the output is parsed to obtain results. */
    @Test
    public void testRun() throws Exception {
        File binary = FileUtil.createTempFile("python-dir", "");
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", binary.getAbsolutePath());

            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            res.setStderr("TEST_RUN_STARTED {\"testCount\": 5, \"runName\": \"TestSuite\"}");
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(), EasyMock.eq(binary.getAbsolutePath())))
                    .andReturn(res);
            mMockListener.testRunStarted(binary.getName(), 5);
            mMockListener.testLog(
                    EasyMock.eq(binary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));
            
            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(binary);
        }
    }

    /**
     * Test running the python tests when an adb path has been set. In that case we ensure the
     * python script will use the provided adb.
     */
    @Test
    public void testRun_withAdbPath() throws Exception {
        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        EasyMock.expect(mMockBuildInfo.getFile(AdbStopServerPreparer.ADB_BINARY_KEY))
                .andReturn(new File("/test/adb"));
        mTest.setBuild(mMockBuildInfo);

        File binary = FileUtil.createTempFile("python-dir", "");
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", binary.getAbsolutePath());

            expectedAdbPath(new File("/test/adb"));

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            res.setStderr("TEST_RUN_STARTED {\"testCount\": 5, \"runName\": \"TestSuite\"}");
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(), EasyMock.eq(binary.getAbsolutePath())))
                    .andReturn(res);
            mMockListener.testRunStarted(binary.getName(), 5);
            mMockListener.testLog(
                    EasyMock.eq(binary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(binary);
        }
    }

    /**
     * If the binary returns an exception status, we should throw a runtime exception since
     * something went wrong with the binary setup.
     */
    @Test
    public void testRunFail_exception() throws Exception {
        File binary = FileUtil.createTempFile("python-dir", "");
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", binary.getAbsolutePath());

            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.EXCEPTION);
            res.setStderr("Could not execute.");
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(), EasyMock.eq(binary.getAbsolutePath())))
                    .andReturn(res);
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            mMockListener.testLog(
                    EasyMock.eq(binary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            // Report a failure if we cannot parse the logs
            mMockListener.testRunStarted(binary.getName(), 0);
            mMockListener.testRunFailed(EasyMock.anyObject());
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(binary);
        }
    }

    /**
     * If the binary reports a FAILED status but the output actually have some tests, it most likely
     * means that some tests failed. So we simply continue with parsing the results.
     */
    @Test
    public void testRunFail_failureOnly() throws Exception {
        File binary = FileUtil.createTempFile("python-dir", "");
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", binary.getAbsolutePath());

            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.FAILED);
            res.setStderr("TEST_RUN_STARTED {\"testCount\": 5, \"runName\": \"TestSuite\"}");
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(), EasyMock.eq(binary.getAbsolutePath())))
                    .andReturn(res);
            mMockListener.testRunStarted(binary.getName(), 5);
            mMockListener.testLog(
                    EasyMock.eq(binary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));
            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(binary);
        }
    }

    private void expectedAdbPath(File adbPath) {
        CommandResult pathRes = new CommandResult();
        pathRes.setStatus(CommandStatus.SUCCESS);
        pathRes.setStdout("bin/");
        EasyMock.expect(
                        mMockRunUtil.runTimedCmd(
                                PythonBinaryHostTest.PATH_TIMEOUT_MS,
                                "/bin/bash",
                                "-c",
                                "echo $PATH"))
                .andReturn(pathRes);
        mMockRunUtil.setEnvVariable("PATH", String.format("%s:bin/", adbPath.getParent()));

        CommandResult versionRes = new CommandResult();
        versionRes.setStatus(CommandStatus.SUCCESS);
        versionRes.setStdout("bin/");
        EasyMock.expect(
                        mMockRunUtil.runTimedCmd(
                                PythonBinaryHostTest.PATH_TIMEOUT_MS, "adb", "version"))
                .andReturn(versionRes);
    }
}
