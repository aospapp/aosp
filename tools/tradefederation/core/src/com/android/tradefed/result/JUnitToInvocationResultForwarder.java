/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tradefed.result;

import com.android.ddmlib.testrunner.TestIdentifier;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A class that listens to {@link junit.framework.TestListener} events and forwards them to an
 * {@link ITestInvocationListener}.
 * <p/>
 */
public class JUnitToInvocationResultForwarder implements TestListener {

    private final List<ITestInvocationListener> mInvocationListeners;

    public JUnitToInvocationResultForwarder(ITestInvocationListener invocationListener) {
        mInvocationListeners = new ArrayList<ITestInvocationListener>(1);
        mInvocationListeners.add(invocationListener);
    }

    public JUnitToInvocationResultForwarder(List<ITestInvocationListener> invocationListeners) {
        mInvocationListeners = new ArrayList<ITestInvocationListener>(invocationListeners.size());
        mInvocationListeners.addAll(invocationListeners);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addError(Test test, Throwable t) {
        for (ITestInvocationListener listener : mInvocationListeners) {
            listener.testFailed(getTestId(test), getStackTrace(t));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addFailure(Test test, AssertionFailedError t) {
        for (ITestInvocationListener listener : mInvocationListeners) {
            listener.testFailed(getTestId(test), getStackTrace(t));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void endTest(Test test) {
        Map<String, String> emptyMap = Collections.emptyMap();
        for (ITestInvocationListener listener : mInvocationListeners) {
            listener.testEnded(getTestId(test), emptyMap);
        }
    }

    /**
     * Callback from JUnit3 tests that can forward metrics.
     *
     * @param test The {@link Test} that just finished running.
     * @param metrics The metrics in a Map format to be passed to the results callback.
     */
    public void endTest(Test test, Map<String, String> metrics) {
        for (ITestInvocationListener listener : mInvocationListeners) {
            listener.testEnded(getTestId(test), metrics);
        }
    }

    /**
     * Callback from JUnit3 forwarder in order to get the logs from a test.
     *
     * @param dataName a String descriptive name of the data. e.g. "device_logcat". Note dataName
     *     may not be unique per invocation. ie implementers must be able to handle multiple calls
     *     with same dataName
     * @param dataType the LogDataType of the data
     * @param dataStream the InputStreamSource of the data. Implementers should call
     *     createInputStream to start reading the data, and ensure to close the resulting
     *     InputStream when complete. Callers should ensure the source of the data remains present
     *     and accessible until the testLog method completes.
     */
    public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        for (ITestInvocationListener listener : mInvocationListeners) {
            listener.testLog(dataName, dataType, dataStream);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void startTest(Test test) {
        for (ITestInvocationListener listener : mInvocationListeners) {
            listener.testStarted(getTestId(test));
        }
    }

    /**
     * Return the {@link TestIdentifier} equivalent for the {@link Test}.
     *
     * @param test the {@link Test} to convert
     * @return the {@link TestIdentifier}
     */
    private TestIdentifier getTestId(Test test) {
        final String className = test.getClass().getName();
        String testName = "";
        if (test instanceof TestCase) {
            testName = ((TestCase)test).getName();
        }
        return new TestIdentifier(className, testName);
    }

    /**
     * Gets the stack trace in {@link String}.
     *
     * @param throwable the {@link Throwable} to convert.
     * @return a {@link String} stack trace
     */
    private String getStackTrace(Throwable throwable) {
        // dump the print stream results to the ByteArrayOutputStream, so contents can be evaluated
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream bytePrintStream = new PrintStream(outputStream);
        throwable.printStackTrace(bytePrintStream);
        return outputStream.toString();
    }
}
