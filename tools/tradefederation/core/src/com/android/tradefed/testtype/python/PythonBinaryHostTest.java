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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.targetprep.adb.AdbStopServerPreparer;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.PythonUnitTestResultParser;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SubprocessTestResultsParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Host test meant to run a python binary file from the Android Build system (Soong) */
@OptionClass(alias = "python-host")
public class PythonBinaryHostTest
        implements IRemoteTest, IDeviceTest, IBuildReceiver, IInvocationContextReceiver {

    protected static final String ANDROID_SERIAL_VAR = "ANDROID_SERIAL";
    protected static final String PATH_VAR = "PATH";
    protected static final long PATH_TIMEOUT_MS = 60000L;

    private static final String PYTHON_LOG_STDERR_FORMAT = "%s-stderr";

    @Option(name = "par-file-name", description = "The binary names inside the build info to run.")
    private Set<String> mBinaryNames = new HashSet<>();

    @Option(
        name = "python-binaries",
        description = "The full path to a runnable python binary. Can be repeated."
    )
    private Set<File> mBinaries = new HashSet<>();

    @Option(
        name = "test-timeout",
        description = "Timeout for a single par file to terminate.",
        isTimeVal = true
    )
    private long mTestTimeout = 20 * 1000L;

    @Option(
            name = "inject-serial-option",
            description = "Whether or not to pass a -s <serialnumber> option to the binary")
    private boolean mInjectSerial = false;

    @Option(
            name = "inject-android-serial",
            description = "Whether or not to pass a ANDROID_SERIAL variable to the process.")
    private boolean mInjectAndroidSerialVar = true;

    @Option(
        name = "python-options",
        description = "Option string to be passed to the binary when running"
    )
    private List<String> mTestOptions = new ArrayList<>();

    private ITestDevice mDevice;
    private IBuildInfo mBuildInfo;
    private IInvocationContext mContext;

    private IRunUtil mRunUtil;

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        mContext = invocationContext;
    }

    @Override
    public final void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        List<File> pythonFilesList = findParFiles();
        for (File pyFile : pythonFilesList) {
            if (!pyFile.exists()) {
                CLog.d(
                        "ignoring %s which doesn't look like a test file.",
                        pyFile.getAbsolutePath());
                continue;
            }
            pyFile.setExecutable(true);
            runSinglePythonFile(listener, pyFile);
        }
    }

    private List<File> findParFiles() {
        File testsDir = null;
        if (mBuildInfo instanceof IDeviceBuildInfo) {
            testsDir = ((IDeviceBuildInfo) mBuildInfo).getTestsDir();
        }
        List<File> files = new ArrayList<>();
        for (String parFileName : mBinaryNames) {
            File res = null;
            // search tests dir
            if (testsDir != null) {
                res = FileUtil.findFile(testsDir, parFileName);
            }

            // TODO: is there other places to search?
            if (res == null) {
                throw new RuntimeException(
                        String.format("Couldn't find a par file %s", parFileName));
            }
            files.add(res);
        }
        files.addAll(mBinaries);
        return files;
    }

    private void runSinglePythonFile(ITestInvocationListener listener, File pyFile) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(pyFile.getAbsolutePath());
        // If we have a physical device, pass it to the python test by serial
        if (!(getDevice().getIDevice() instanceof StubDevice) && mInjectSerial) {
            // TODO: support multi-device python tests?
            commandLine.add("-s");
            commandLine.add(getDevice().getSerialNumber());
        }

        if (mInjectAndroidSerialVar) {
            getRunUtil().setEnvVariable(ANDROID_SERIAL_VAR, getDevice().getSerialNumber());
        }

        File updatedAdb = mBuildInfo.getFile(AdbStopServerPreparer.ADB_BINARY_KEY);
        if (updatedAdb == null) {
            String adbPath = getAdbPath();
            updatedAdb = new File(adbPath);
            if (!updatedAdb.exists()) {
                updatedAdb = null;
            }
        }
        if (updatedAdb != null) {
            // If a special adb version is used, pass it to the PATH
            CommandResult pathResult =
                    getRunUtil()
                            .runTimedCmd(PATH_TIMEOUT_MS, "/bin/bash", "-c", "echo $" + PATH_VAR);
            if (!CommandStatus.SUCCESS.equals(pathResult.getStatus())) {
                throw new RuntimeException(
                        String.format(
                                "Failed to get the $PATH. status: %s, stdout: %s, stderr: %s",
                                pathResult.getStatus(),
                                pathResult.getStdout(),
                                pathResult.getStderr()));
            }
            // Include the directory of the adb on the PATH to be used.
            String path =
                    String.format(
                            "%s:%s",
                            updatedAdb.getParentFile().getAbsolutePath(),
                            pathResult.getStdout().trim());
            CLog.d("Using $PATH with updated adb: %s", path);
            getRunUtil().setEnvVariable(PATH_VAR, path);
            // Log the version of adb seen
            CommandResult versionRes = getRunUtil().runTimedCmd(PATH_TIMEOUT_MS, "adb", "version");
            CLog.d("%s", versionRes.getStdout());
            CLog.d("%s", versionRes.getStderr());
        }
        // Add all the other options
        commandLine.addAll(mTestOptions);

        CommandResult result =
                getRunUtil().runTimedCmd(mTestTimeout, commandLine.toArray(new String[0]));
        String runName = pyFile.getName();
        PythonForwarder forwarder = new PythonForwarder(listener, runName);
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            CLog.e(
                    "Something went wrong when running the python binary:\nstdout: "
                            + "%s\nstderr:%s",
                    result.getStdout(), result.getStderr());
        }

        File resultFile = null;
        try {
            resultFile = FileUtil.createTempFile("python-res", ".txt");
            FileUtil.writeToFile(result.getStderr(), resultFile);
            try (FileInputStreamSource data = new FileInputStreamSource(resultFile)) {
                listener.testLog(
                        String.format(PYTHON_LOG_STDERR_FORMAT, runName), LogDataType.TEXT, data);
            }
            // If it doesn't have the std output TEST_RUN_STARTED, use regular parser.
            if (!result.getStderr().contains("TEST_RUN_STARTED")) {
                // Attempt to parse the pure python output
                PythonUnitTestResultParser pythonParser =
                        new PythonUnitTestResultParser(forwarder, "python-run");
                pythonParser.processNewLines(result.getStderr().split("\n"));
            } else {
                try (SubprocessTestResultsParser parser =
                        new SubprocessTestResultsParser(forwarder, mContext)) {
                    parser.parseFile(resultFile);
                }
            }
        } catch (RuntimeException e) {
            reportFailure(
                    listener,
                    runName,
                    String.format("Failed to parse the python logs: %s", e.getMessage()));
            CLog.e(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            FileUtil.deleteFile(resultFile);
        }
    }

    @VisibleForTesting
    IRunUtil getRunUtil() {
        if (mRunUtil == null) {
            mRunUtil = new RunUtil();
        }
        return mRunUtil;
    }

    @VisibleForTesting
    String getAdbPath() {
        return GlobalConfiguration.getDeviceManagerInstance().getAdbPath();
    }

    private void reportFailure(
            ITestInvocationListener listener, String runName, String errorMessage) {
        listener.testRunStarted(runName, 0);
        listener.testRunFailed(errorMessage);
        listener.testRunEnded(0L, new HashMap<String, Metric>());
    }

    /** Result forwarder to replace the run name by the binary name. */
    public class PythonForwarder extends ResultForwarder {

        private String mRunName;

        /** Ctor with the run name using the binary name. */
        public PythonForwarder(ITestInvocationListener listener, String name) {
            super(listener);
            mRunName = name;
        }

        @Override
        public void testRunStarted(String runName, int testCount) {
            // Replace run name
            super.testRunStarted(mRunName, testCount);
        }
    }
}
