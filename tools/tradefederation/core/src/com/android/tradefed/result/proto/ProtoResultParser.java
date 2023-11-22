/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.result.proto;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.proto.InvocationContext.Context;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.LogFileProto.LogFileInfo;
import com.android.tradefed.result.proto.TestRecordProto.ChildReference;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import com.google.common.base.Strings;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

/** Parser for the Tradefed results proto format. */
public class ProtoResultParser {

    private ITestInvocationListener mListener;
    private String mCurrentRunName = null;
    /**
     * We don't always want to report the invocation level events again. If we are within an
     * invocation scope we should not report it again.
     */
    private boolean mReportInvocation = false;
    /** Prefix that will be added to the files logged through the parser. */
    private String mFilePrefix;
    /** The context from the invocation in progress, not the proto one. */
    private IInvocationContext mMainContext;

    private boolean mQuietParsing = true;

    /** Ctor. */
    public ProtoResultParser(
            ITestInvocationListener listener,
            IInvocationContext context,
            boolean reportInvocation) {
        this(listener, context, reportInvocation, "subprocess-");
    }

    /** Ctor. */
    public ProtoResultParser(
            ITestInvocationListener listener,
            IInvocationContext context,
            boolean reportInvocation,
            String prefixForFile) {
        mListener = listener;
        mMainContext = context;
        mReportInvocation = reportInvocation;
        mFilePrefix = prefixForFile;
    }

    /** Enumeration representing the current level of the proto being processed. */
    public enum TestLevel {
        INVOCATION,
        MODULE,
        TEST_RUN,
        TEST_CASE
    }

    /** Sets whether or not to print when events are received. */
    public void setQuiet(boolean quiet) {
        mQuietParsing = quiet;
    }

    /**
     * Main entry function that takes the finalized completed proto and replay its results.
     *
     * @param finalProto The final {@link TestRecord} to be parsed.
     */
    public void processFinalizedProto(TestRecord finalProto) {
        if (!finalProto.getParentTestRecordId().isEmpty()) {
            throw new IllegalArgumentException("processFinalizedProto only expect a root proto.");
        }

        // Invocation Start
        handleInvocationStart(finalProto);

        evalProto(finalProto.getChildrenList(), false);
        // Invocation End
        handleInvocationEnded(finalProto);
    }

    /**
     * Main entry function where each proto is presented to get parsed into Tradefed events.
     *
     * @param currentProto The current {@link TestRecord} to be parsed.
     * @return True if the proto processed was a module.
     */
    public TestLevel processNewProto(TestRecord currentProto) {
        // Handle initial root proto
        if (currentProto.getParentTestRecordId().isEmpty()) {
            handleRootProto(currentProto);
            return TestLevel.INVOCATION;
        } else if (currentProto.hasDescription()) {
            // If it has a Any Description with Context then it's a module
            handleModuleProto(currentProto);
            return TestLevel.MODULE;
        } else if (mCurrentRunName == null
                || currentProto.getTestRecordId().equals(mCurrentRunName)) {
            // Need to track the parent test run id to make sure we need testRunEnd or testRunFail
            handleTestRun(currentProto);
            return TestLevel.TEST_RUN;
        } else {
            // Test cases handling
            handleTestCase(currentProto);
            return TestLevel.TEST_CASE;
        }
    }

    private void evalProto(List<ChildReference> children, boolean isInRun) {
        for (ChildReference child : children) {
            TestRecord childProto = child.getInlineTestRecord();
            if (isInRun) {
                // test case
                String[] info = childProto.getTestRecordId().split("#");
                TestDescription description = new TestDescription(info[0], info[1]);
                mListener.testStarted(description, timeStampToMillis(childProto.getStartTime()));
                handleTestCaseEnd(description, childProto);
            } else {
                boolean inRun = false;
                if (childProto.hasDescription()) {
                    // Module start
                    handleModuleStart(childProto);
                } else {
                    // run start
                    handleTestRunStart(childProto);
                    inRun = true;
                }
                evalProto(childProto.getChildrenList(), inRun);
                if (childProto.hasDescription()) {
                    // Module end
                    handleModuleProto(childProto);
                } else {
                    // run end
                    handleTestRunEnd(childProto);
                }
            }
        }
    }

    /** Handles the root of the invocation: They have no parent record id. */
    private void handleRootProto(TestRecord rootProto) {
        if (rootProto.hasEndTime()) {
            handleInvocationEnded(rootProto);
        } else {
            handleInvocationStart(rootProto);
        }
    }

    private void handleInvocationStart(TestRecord startInvocationProto) {
        // invocation starting
        Any anyDescription = startInvocationProto.getDescription();
        if (!anyDescription.is(Context.class)) {
            throw new RuntimeException("Expected Any description of type Context");
        }
        IInvocationContext receivedProto;
        try {
            receivedProto = InvocationContext.fromProto(anyDescription.unpack(Context.class));
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }

        log("Invocation started proto");
        if (!mReportInvocation) {
            CLog.d("Skipping invocation start reporting.");
            return;
        }
        // Only report invocation start if enabled
        mListener.invocationStarted(receivedProto);
    }

    private void handleInvocationEnded(TestRecord endInvocationProto) {
        // Still report the logs even if not reporting the invocation level.
        handleLogs(endInvocationProto);

        // Get final context in case it changed.
        Any anyDescription = endInvocationProto.getDescription();
        if (!anyDescription.is(Context.class)) {
            throw new RuntimeException("Expected Any description of type Context");
        }
        try {
            IInvocationContext context =
                    InvocationContext.fromProto(anyDescription.unpack(Context.class));
            mergeInvocationContext(mMainContext, context);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }

        if (endInvocationProto.hasDebugInfo()) {
            // TODO: Re-interpret the exception with proper type.
            String trace = endInvocationProto.getDebugInfo().getTrace();
            mListener.invocationFailed(new Throwable(trace));
        }

        log("Invocation ended proto");
        if (!mReportInvocation) {
            CLog.d("Skipping invocation ended reporting.");
            return;
        }
        // Only report invocation ended if enabled
        long elapsedTime =
                timeStampToMillis(endInvocationProto.getEndTime())
                        - timeStampToMillis(endInvocationProto.getStartTime());
        mListener.invocationEnded(elapsedTime);
    }

    /** Handles module level of the invocation: They have a Description for the module context. */
    private void handleModuleProto(TestRecord moduleProto) {
        if (moduleProto.hasEndTime()) {
            handleModuleEnded(moduleProto);
        } else {
            handleModuleStart(moduleProto);
        }
    }

    private void handleModuleStart(TestRecord moduleProto) {
        Any anyDescription = moduleProto.getDescription();
        if (!anyDescription.is(Context.class)) {
            throw new RuntimeException("Expected Any description of type Context");
        }
        try {
            IInvocationContext moduleContext =
                    InvocationContext.fromProto(anyDescription.unpack(Context.class));
            String message = "Test module started proto";
            if (moduleContext.getAttributes().containsKey(ModuleDefinition.MODULE_ID)) {
                message +=
                        (": "
                                + moduleContext
                                        .getAttributes()
                                        .getUniqueMap()
                                        .get(ModuleDefinition.MODULE_ID));
            }
            log(message);
            mListener.testModuleStarted(moduleContext);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleModuleEnded(TestRecord moduleProto) {
        handleLogs(moduleProto);
        log("Test module ended proto");
        mListener.testModuleEnded();
    }

    /** Handles the test run level of the invocation. */
    private void handleTestRun(TestRecord runProto) {
        // If the proto end-time is present we are evaluating the end of a test run.
        if (runProto.hasEndTime()) {
            handleTestRunEnd(runProto);
            mCurrentRunName = null;
        } else {
            // If the end-time is not populated yet we are dealing with the start of a run.
            mCurrentRunName = runProto.getTestRecordId();
            handleTestRunStart(runProto);
        }
    }

    private void handleTestRunStart(TestRecord runProto) {
        String id = runProto.getTestRecordId();
        log(
                "Test run started proto: %s. Expected tests: %s. Attempt: %s",
                id, runProto.getNumExpectedChildren(), runProto.getAttemptId());
        mListener.testRunStarted(
                id,
                (int) runProto.getNumExpectedChildren(),
                (int) runProto.getAttemptId(),
                timeStampToMillis(runProto.getStartTime()));
    }

    private void handleTestRunEnd(TestRecord runProto) {
        // If we find debugging information, the test run failed and we reflect it.
        if (runProto.hasDebugInfo()) {
            mListener.testRunFailed(runProto.getDebugInfo().getErrorMessage());
        }
        handleLogs(runProto);
        log("Test run ended proto: %s", runProto.getTestRecordId());
        long elapsedTime =
                timeStampToMillis(runProto.getEndTime())
                        - timeStampToMillis(runProto.getStartTime());
        HashMap<String, Metric> metrics = new HashMap<>(runProto.getMetrics());
        mListener.testRunEnded(elapsedTime, metrics);
    }

    /** Handles the test cases level of the invocation. */
    private void handleTestCase(TestRecord testcaseProto) {
        String[] info = testcaseProto.getTestRecordId().split("#");
        TestDescription description = new TestDescription(info[0], info[1]);
        if (testcaseProto.hasEndTime()) {
            handleTestCaseEnd(description, testcaseProto);
        } else {
            log("Test case started proto: %s", description.toString());
            mListener.testStarted(description, timeStampToMillis(testcaseProto.getStartTime()));
        }
    }

    private void handleTestCaseEnd(TestDescription description, TestRecord testcaseProto) {
        switch (testcaseProto.getStatus()) {
            case FAIL:
                mListener.testFailed(description, testcaseProto.getDebugInfo().getErrorMessage());
                log(
                        "Test case failed proto: %s - %s",
                        description.toString(), testcaseProto.getDebugInfo().getErrorMessage());
                break;
            case ASSUMPTION_FAILURE:
                mListener.testAssumptionFailure(
                        description, testcaseProto.getDebugInfo().getTrace());
                log(
                        "Test case assumption failure proto: %s - %s",
                        description.toString(), testcaseProto.getDebugInfo().getTrace());
                break;
            case IGNORED:
                mListener.testIgnored(description);
                log("Test case ignored proto: %s", description.toString());
                break;
            case PASS:
                break;
            default:
                throw new RuntimeException(
                        String.format(
                                "Received unexpected test status %s.", testcaseProto.getStatus()));
        }
        handleLogs(testcaseProto);
        HashMap<String, Metric> metrics = new HashMap<>(testcaseProto.getMetrics());
        log("Test case ended proto: %s", description.toString());
        mListener.testEnded(description, timeStampToMillis(testcaseProto.getEndTime()), metrics);
    }

    private long timeStampToMillis(Timestamp stamp) {
        return stamp.getSeconds() * 1000L + (stamp.getNanos() / 1000000L);
    }

    private void handleLogs(TestRecord proto) {
        if (!(mListener instanceof ILogSaverListener)) {
            return;
        }
        ILogSaverListener logger = (ILogSaverListener) mListener;
        for (Entry<String, Any> entry : proto.getArtifacts().entrySet()) {
            try {
                LogFileInfo info = entry.getValue().unpack(LogFileInfo.class);
                LogFile file =
                        new LogFile(
                                info.getPath(),
                                info.getUrl(),
                                info.getIsCompressed(),
                                LogDataType.valueOf(info.getLogType()),
                                info.getSize());
                if (file.getPath() == null) {
                    CLog.e("Log '%s' was registered but without a path.", entry.getKey());
                    return;
                }
                File path = new File(file.getPath());
                if (Strings.isNullOrEmpty(file.getUrl()) && path.exists()) {
                    try (InputStreamSource source = new FileInputStreamSource(path)) {
                        LogDataType type = file.getType();
                        // File might have already been compressed
                        if (file.getPath().endsWith(LogDataType.ZIP.getFileExt())) {
                            type = LogDataType.ZIP;
                        }
                        log("Logging %s from subprocess: %s ", entry.getKey(), file.getPath());
                        logger.testLog(mFilePrefix + entry.getKey(), type, source);
                    }
                } else {
                    log("Logging %s from subprocess: %s", entry.getKey(), file.getUrl());
                    logger.logAssociation(mFilePrefix + entry.getKey(), file);
                }
            } catch (InvalidProtocolBufferException e) {
                CLog.e("Couldn't unpack %s as a LogFileInfo", entry.getKey());
                CLog.e(e);
            }
        }
    }

    /**
     * Copy the build info and invocation attributes from the proto context to the current
     * invocation context
     *
     * @param receiverContext The context receiving the attributes
     * @param endInvocationContext The context providing the attributes
     */
    private void mergeInvocationContext(
            IInvocationContext receiverContext, IInvocationContext endInvocationContext) {
        if (receiverContext == null) {
            return;
        }
        // Gather attributes of build infos
        for (IBuildInfo info : receiverContext.getBuildInfos()) {
            String name = receiverContext.getBuildInfoName(info);
            IBuildInfo endInvocationInfo = endInvocationContext.getBuildInfo(name);
            if (endInvocationInfo == null) {
                CLog.e("No build info named: %s", name);
                continue;
            }
            info.addBuildAttributes(endInvocationInfo.getBuildAttributes());
        }

        try {
            Method unlock = InvocationContext.class.getDeclaredMethod("unlock");
            unlock.setAccessible(true);
            unlock.invoke(receiverContext);
            unlock.setAccessible(false);
        } catch (NoSuchMethodException
                | SecurityException
                | IllegalAccessException
                | IllegalArgumentException
                | InvocationTargetException e) {
            CLog.e("Couldn't unlock the main context. Skip copying attributes");
            return;
        }
        // Copy invocation attributes
        receiverContext.addInvocationAttributes(endInvocationContext.getAttributes());
    }

    private void log(String format, Object... obj) {
        if (!mQuietParsing) {
            CLog.d(format, obj);
        }
    }
}
