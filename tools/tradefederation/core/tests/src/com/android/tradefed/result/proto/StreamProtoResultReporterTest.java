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

import static org.junit.Assert.assertNull;

import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;

/**
 * Unit tests for {@link StreamProtoResultReporter}.
 */
@RunWith(JUnit4.class)
public class StreamProtoResultReporterTest {

    private StreamProtoResultReporter mReporter;
    private IInvocationContext mInvocationContext;
    private IInvocationContext mMainInvocationContext;
    private ITestInvocationListener mMockListener;

    @Before
    public void setUp() {
        mReporter = new StreamProtoResultReporter();
        mInvocationContext = new InvocationContext();
        mMainInvocationContext = new InvocationContext();
        mInvocationContext.setConfigurationDescriptor(new ConfigurationDescriptor());
        mMockListener = EasyMock.createStrictMock(ITestInvocationListener.class);
    }

    @Test
    public void testStream() throws Exception {
        StreamProtoReceiver receiver =
                new StreamProtoReceiver(mMockListener, mMainInvocationContext, true);
        OptionSetter setter = new OptionSetter(mReporter);
        try {
            setter.setOptionValue(
                    "proto-report-port", Integer.toString(receiver.getSocketServerPort()));
            TestDescription test1 = new TestDescription("class1", "test1");
            TestDescription test2 = new TestDescription("class1", "test2");
            HashMap<String, Metric> metrics = new HashMap<String, Metric>();
            metrics.put("metric1", TfMetricProtoUtil.stringToMetric("value1"));
            // Verify mocks
            mMockListener.invocationStarted(EasyMock.anyObject());

            mMockListener.testModuleStarted(EasyMock.anyObject());
            mMockListener.testRunStarted(
                    EasyMock.eq("run1"), EasyMock.eq(2), EasyMock.eq(0), EasyMock.anyLong());
            mMockListener.testStarted(test1, 5L);
            mMockListener.testEnded(test1, 10L, new HashMap<String, Metric>());

            mMockListener.testStarted(test2, 11L);
            mMockListener.testFailed(test2, "I failed");
            mMockListener.testEnded(
                    EasyMock.eq(test2),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
            mMockListener.testModuleEnded();

            mMockListener.invocationEnded(500L);

            EasyMock.replay(mMockListener);
            mReporter.invocationStarted(mInvocationContext);
            // Run modules
            mReporter.testModuleStarted(createModuleContext("arm64 module1"));
            mReporter.testRunStarted("run1", 2);

            mReporter.testStarted(test1, 5L);
            mReporter.testEnded(test1, 10L, new HashMap<String, Metric>());

            mReporter.testStarted(test2, 11L);
            mReporter.testFailed(test2, "I failed");
            // test log
            mReporter.logAssociation(
                    "log1", new LogFile("path", "url", false, LogDataType.TEXT, 5));

            mReporter.testEnded(test2, 60L, metrics);
            // run log
            mReporter.logAssociation(
                    "run_log1", new LogFile("path", "url", false, LogDataType.LOGCAT, 5));
            mReporter.testRunEnded(50L, new HashMap<String, Metric>());

            mReporter.testModuleEnded();
            // Invocation ends
            mReporter.invocationEnded(500L);
        } finally {
            receiver.joinReceiver(5000);
            receiver.close();
        }
        EasyMock.verify(mMockListener);
        assertNull(receiver.getError());
    }

    @Test
    public void testStream_noInvocationReporting() throws Exception {
        StreamProtoReceiver receiver =
                new StreamProtoReceiver(
                        mMockListener,
                        mMainInvocationContext,
                        /** No invocation reporting */
                        false);
        OptionSetter setter = new OptionSetter(mReporter);
        try {
            setter.setOptionValue(
                    "proto-report-port", Integer.toString(receiver.getSocketServerPort()));
            TestDescription test1 = new TestDescription("class1", "test1");
            TestDescription test2 = new TestDescription("class1", "test2");
            HashMap<String, Metric> metrics = new HashMap<String, Metric>();
            metrics.put("metric1", TfMetricProtoUtil.stringToMetric("value1"));
            // Verify mocks
            mMockListener.testModuleStarted(EasyMock.anyObject());
            mMockListener.testRunStarted(
                    EasyMock.eq("run1"), EasyMock.eq(2), EasyMock.eq(0), EasyMock.anyLong());
            mMockListener.testStarted(test1, 5L);
            mMockListener.testEnded(test1, 10L, new HashMap<String, Metric>());

            mMockListener.testStarted(test2, 11L);
            mMockListener.testFailed(test2, "I failed");
            mMockListener.testEnded(
                    EasyMock.eq(test2),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
            mMockListener.testModuleEnded();

            EasyMock.replay(mMockListener);
            mReporter.invocationStarted(mInvocationContext);
            // Run modules
            mReporter.testModuleStarted(createModuleContext("arm64 module1"));
            mReporter.testRunStarted("run1", 2);

            mReporter.testStarted(test1, 5L);
            mReporter.testEnded(test1, 10L, new HashMap<String, Metric>());

            mReporter.testStarted(test2, 11L);
            mReporter.testFailed(test2, "I failed");
            // test log
            mReporter.logAssociation(
                    "log1", new LogFile("path", "url", false, LogDataType.TEXT, 5));

            mReporter.testEnded(test2, 60L, metrics);
            // run log
            mReporter.logAssociation(
                    "run_log1", new LogFile("path", "url", false, LogDataType.LOGCAT, 5));
            mReporter.testRunEnded(50L, new HashMap<String, Metric>());

            mReporter.testModuleEnded();
            // Invocation ends
            mReporter.invocationEnded(500L);
        } finally {
            receiver.joinReceiver(5000);
            receiver.close();
        }
        EasyMock.verify(mMockListener);
        assertNull(receiver.getError());
    }

    /** Helper to create a module context. */
    private IInvocationContext createModuleContext(String moduleId) {
        IInvocationContext context = new InvocationContext();
        context.addInvocationAttribute(ModuleDefinition.MODULE_ID, moduleId);
        context.setConfigurationDescriptor(new ConfigurationDescriptor());
        return context;
    }
}
