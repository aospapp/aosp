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
package com.android.tradefed.device.metric;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;

import java.util.List;
import java.util.Map;

/**
 * Base implementation of {@link IMetricCollector} that allows to start and stop collection on
 * {@link #onTestRunStart(DeviceMetricData)} and {@link #onTestRunEnd(DeviceMetricData)}.
 */
public class BaseDeviceMetricCollector implements IMetricCollector {

    private IInvocationContext mContext;
    private ITestInvocationListener mForwarder;
    private DeviceMetricData mRunData;

    @Override
    public ITestInvocationListener init(
            IInvocationContext context, ITestInvocationListener listener) {
        mContext = context;
        mForwarder = listener;
        return this;
    }

    @Override
    public List<ITestDevice> getDevices() {
        return mContext.getDevices();
    }

    @Override
    public List<IBuildInfo> getBuildInfos() {
        return mContext.getBuildInfos();
    }

    @Override
    public ITestInvocationListener getInvocationListener() {
        return mForwarder;
    }

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        // Does nothing
    }

    @Override
    public void onTestRunEnd(DeviceMetricData runData) {
        // Does nothing
    }

    /** =================================== */
    /** Invocation Listeners for forwarding */
    @Override
    public final void invocationStarted(IInvocationContext context) {
        mForwarder.invocationStarted(context);
    }

    @Override
    public final void invocationFailed(Throwable cause) {
        mForwarder.invocationFailed(cause);
    }

    @Override
    public final void invocationEnded(long elapsedTime) {
        mForwarder.invocationEnded(elapsedTime);
    }

    @Override
    public final void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        mForwarder.testLog(dataName, dataType, dataStream);
    }

    /** Test run callbacks */
    @Override
    public final void testRunStarted(String runName, int testCount) {
        mRunData = new DeviceMetricData();
        onTestRunStart(mRunData);
        mForwarder.testRunStarted(runName, testCount);
    }

    @Override
    public final void testRunFailed(String errorMessage) {
        mForwarder.testRunFailed(errorMessage);
    }

    @Override
    public final void testRunStopped(long elapsedTime) {
        mForwarder.testRunStopped(elapsedTime);
    }

    @Override
    public final void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
        onTestRunEnd(mRunData);
        mRunData.addToMetrics(runMetrics);
        mForwarder.testRunEnded(elapsedTime, runMetrics);
    }

    /** Test cases callbacks */
    @Override
    public final void testStarted(TestIdentifier test) {
        testStarted(test, System.currentTimeMillis());
    }

    @Override
    public final void testStarted(TestIdentifier test, long startTime) {
        mForwarder.testStarted(test, startTime);
    }

    @Override
    public final void testFailed(TestIdentifier test, String trace) {
        mForwarder.testFailed(test, trace);
    }

    @Override
    public final void testEnded(TestIdentifier test, Map<String, String> testMetrics) {
        testEnded(test, System.currentTimeMillis(), testMetrics);
    }

    @Override
    public final void testEnded(
            TestIdentifier test, long endTime, Map<String, String> testMetrics) {
        mForwarder.testEnded(test, endTime, testMetrics);
    }

    @Override
    public final void testAssumptionFailure(TestIdentifier test, String trace) {
        mForwarder.testAssumptionFailure(test, trace);
    }

    @Override
    public final void testIgnored(TestIdentifier test) {
        mForwarder.testIgnored(test);
    }
}
