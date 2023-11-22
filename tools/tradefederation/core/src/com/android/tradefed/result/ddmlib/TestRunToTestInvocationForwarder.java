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
package com.android.tradefed.result.ddmlib;

import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestLifeCycleReceiver;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * Forwarder from ddmlib {@link ITestRunListener} to {@link ITestLifeCycleReceiver}. Interface that
 * ensure the convertion of results from ddmlib interface to Tradefed Interface.
 *
 * <p>Ddmlib interface is linked to running instrumentation tests.
 */
public class TestRunToTestInvocationForwarder implements ITestRunListener {

    private static final String NULL_STRING = "null";
    public static final String ERROR_MESSAGE_FORMAT =
            "Runner reported an invalid method 'null' (%s). Something went wrong, Skipping "
                    + "its reporting.";

    private Collection<ITestLifeCycleReceiver> mListeners;
    private Long mStartTime;
    // Sometimes the instrumentation runner (Android JUnit Runner / AJUR) fails to load some class
    // and report a "null" as a test method. This creates a lot of issues in the reporting pipeline
    // so catch it, and avoid it at the root.
    private TestIdentifier mNullMethod = null;

    public TestRunToTestInvocationForwarder(Collection<ITestLifeCycleReceiver> listeners) {
        mListeners = listeners;
        mStartTime = null;
    }

    public TestRunToTestInvocationForwarder(ITestLifeCycleReceiver listener) {
        this(Arrays.asList(listener));
    }

    @Override
    public void testStarted(TestIdentifier testId) {
        if (NULL_STRING.equals(testId.getTestName())) {
            mNullMethod = testId;
            return;
        }
        mNullMethod = null;
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testStarted(TestDescription.createFromTestIdentifier(testId));
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testStarted",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testStarted(TestIdentifier testId, long startTime) {
        if (NULL_STRING.equals(testId.getTestName())) {
            mNullMethod = testId;
            return;
        }
        mNullMethod = null;
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testStarted(TestDescription.createFromTestIdentifier(testId), startTime);
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testStarted",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testAssumptionFailure(TestIdentifier testId, String trace) {
        if (mNullMethod != null && mNullMethod.equals(testId)) {
            return;
        }
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testAssumptionFailure(
                        TestDescription.createFromTestIdentifier(testId), trace);
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testAssumptionFailure",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testFailed(TestIdentifier testId, String trace) {
        if (mNullMethod != null && mNullMethod.equals(testId)) {
            return;
        }
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testFailed(TestDescription.createFromTestIdentifier(testId), trace);
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testFailed",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testIgnored(TestIdentifier testId) {
        if (mNullMethod != null && mNullMethod.equals(testId)) {
            return;
        }
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testIgnored(TestDescription.createFromTestIdentifier(testId));
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testIgnored",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testEnded(TestIdentifier testId, Map<String, String> testMetrics) {
        for (ITestLifeCycleReceiver listener : mListeners) {
            if (mNullMethod != null && mNullMethod.equals(testId)) {
                listener.testRunFailed(String.format(ERROR_MESSAGE_FORMAT, mNullMethod));
                continue;
            }
            try {
                listener.testEnded(
                        TestDescription.createFromTestIdentifier(testId),
                        TfMetricProtoUtil.upgradeConvert(testMetrics));
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testEnded",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testEnded(TestIdentifier testId, long endTime, Map<String, String> testMetrics) {
        for (ITestLifeCycleReceiver listener : mListeners) {
            if (mNullMethod != null && mNullMethod.equals(testId)) {
                listener.testRunFailed(String.format(ERROR_MESSAGE_FORMAT, mNullMethod));
                continue;
            }
            try {
                listener.testEnded(
                        TestDescription.createFromTestIdentifier(testId),
                        endTime,
                        TfMetricProtoUtil.upgradeConvert(testMetrics));
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testEnded",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
        if (mStartTime != null) {
            elapsedTime = System.currentTimeMillis() - mStartTime;
        }
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testRunEnded(elapsedTime, TfMetricProtoUtil.upgradeConvert(runMetrics));
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testRunEnded",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testRunFailed(String failure) {
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testRunFailed(failure);
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testRunFailed",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        mStartTime = System.currentTimeMillis();
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testRunStarted(runName, testCount);
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testRunStarted",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }

    @Override
    public void testRunStopped(long elapsedTime) {
        for (ITestLifeCycleReceiver listener : mListeners) {
            try {
                listener.testRunStopped(elapsedTime);
            } catch (RuntimeException any) {
                CLog.e(
                        "RuntimeException when invoking %s#testRunStopped",
                        listener.getClass().getName());
                CLog.e(any);
            }
        }
    }
}
