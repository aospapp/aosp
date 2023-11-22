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
package com.android.tradefed.sandbox;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.sandbox.SandboxConfigDump.DumpCmd;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.QuotationAwareTokenizer;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SerializationUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.SubprocessTestResultsParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sandbox container that can run a Trade Federation invocation. TODO: Allow Options to be passed to
 * the sandbox.
 */
public class TradefedSandbox implements ISandbox {

    /** The variable holding TF specific environment */
    public static final String TF_GLOBAL_CONFIG = "TF_GLOBAL_CONFIG";
    /** Timeout to wait for the events received from subprocess to finish being processed. */
    private static final long EVENT_THREAD_JOIN_TIMEOUT_MS = 30 * 1000;

    private File mStdoutFile = null;
    private File mStderrFile = null;
    private FileOutputStream mStdout = null;
    private FileOutputStream mStderr = null;

    private File mSandboxTmpFolder = null;
    private File mRootFolder = null;
    private File mSerializedContext = null;
    private File mSerializedConfiguration = null;

    private SubprocessTestResultsParser mEventParser = null;

    private IRunUtil mRunUtil;

    @Override
    public CommandResult run(IConfiguration config) {
        List<String> mCmdArgs = new ArrayList<>();
        mCmdArgs.add("java");
        mCmdArgs.add(String.format("-Djava.io.tmpdir=%s", mSandboxTmpFolder.getAbsolutePath()));
        mCmdArgs.add("-cp");
        mCmdArgs.add(new File(mRootFolder, "*").getAbsolutePath());
        mCmdArgs.add(TradefedSanboxRunner.class.getCanonicalName());
        mCmdArgs.add(mSerializedContext.getAbsolutePath());
        mCmdArgs.add(mSerializedConfiguration.getAbsolutePath());
        mCmdArgs.add("--subprocess-report-port");
        mCmdArgs.add(Integer.toString(mEventParser.getSocketServerPort()));

        long timeout = config.getCommandOptions().getInvocationTimeout();
        CommandResult result =
                mRunUtil.runTimedCmd(timeout, mStdout, mStderr, mCmdArgs.toArray(new String[0]));

        boolean failedStatus = false;
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            failedStatus = true;
        }

        if (!mEventParser.joinReceiver(EVENT_THREAD_JOIN_TIMEOUT_MS)) {
            if (!failedStatus) {
                result.setStatus(CommandStatus.EXCEPTION);
            }
            String stderrText;
            try {
                stderrText = FileUtil.readStringFromFile(mStderrFile);
            } catch (IOException e) {
                stderrText = "Could not read the stderr output from process.";
            }
            result.setStderr(
                    String.format("Event receiver thread did not complete.:\n%s", stderrText));
        }

        return result;
    }

    @Override
    public Exception prepareEnvironment(
            IInvocationContext context, IConfiguration config, ITestInvocationListener listener) {
        // Create our temp directories.
        try {
            mStdoutFile = FileUtil.createTempFile("stdout_subprocess_", ".log");
            mStderrFile = FileUtil.createTempFile("stderr_subprocess_", ".log");
            mStdout = new FileOutputStream(mStdoutFile);
            mStderr = new FileOutputStream(mStderrFile);

            mSandboxTmpFolder = FileUtil.createTempDir("tradefed-container");
        } catch (IOException e) {
            return e;
        }
        // Unset the current global environment
        mRunUtil = createRunUtil();
        mRunUtil.unsetEnvVariable(TF_GLOBAL_CONFIG);
        // TODO: add handling of setting and creating the subprocess global configuration

        try {
            mRootFolder =
                    getTradefedEnvironment(
                            QuotationAwareTokenizer.tokenizeLine(config.getCommandLine()));
        } catch (ConfigurationException e) {
            return e;
        }

        // Prepare the configuration
        Exception res = prepareConfiguration(context, config, listener);
        if (res != null) {
            return res;
        }

        // Prepare the context
        try {
            mSerializedContext = prepareContext(context);
        } catch (IOException e) {
            return e;
        }

        return null;
    }

    @Override
    public void tearDown() {
        StreamUtil.close(mEventParser);
        StreamUtil.close(mStdout);
        StreamUtil.close(mStderr);
        FileUtil.deleteFile(mStdoutFile);
        FileUtil.deleteFile(mStderrFile);
        FileUtil.recursiveDelete(mSandboxTmpFolder);
        FileUtil.deleteFile(mSerializedContext);
        FileUtil.deleteFile(mSerializedConfiguration);
    }

    @Override
    public File getTradefedEnvironment(String[] args) throws ConfigurationException {
        String tfDir = System.getProperty("TF_JAR_DIR");
        if (tfDir == null || tfDir.isEmpty()) {
            throw new ConfigurationException(
                    "Could not read TF_JAR_DIR to get current Tradefed instance.");
        }
        return new File(tfDir);
    }

    /**
     * Prepare the {@link IConfiguration} that will be passed to the subprocess and will drive the
     * container execution.
     *
     * @param context The current {@link IInvocationContext}.
     * @param config the {@link IConfiguration} to be prepared.
     * @param listener The current invocation {@link ITestInvocationListener}.
     * @return an Exception if anything went wrong, null otherwise.
     */
    protected Exception prepareConfiguration(
            IInvocationContext context, IConfiguration config, ITestInvocationListener listener) {
        try {
            // TODO: add option to disable the streaming back of results.
            mEventParser = new SubprocessTestResultsParser(listener, true, context);
            String[] args = QuotationAwareTokenizer.tokenizeLine(config.getCommandLine());
            mSerializedConfiguration =
                    SandboxConfigUtil.dumpConfigForVersion(
                            mRootFolder, mRunUtil, args, DumpCmd.RUN_CONFIG);
        } catch (ConfigurationException | IOException e) {
            return e;
        }
        return null;
    }

    @VisibleForTesting
    IRunUtil createRunUtil() {
        return new RunUtil();
    }

    /**
     * Prepare and serialize the {@link IInvocationContext}.
     *
     * @param context the {@link IInvocationContext} to be prepared.
     * @return the serialized {@link IInvocationContext}.
     * @throws IOException
     */
    protected File prepareContext(IInvocationContext context) throws IOException {
        return SerializationUtil.serialize(context);
    }
}
