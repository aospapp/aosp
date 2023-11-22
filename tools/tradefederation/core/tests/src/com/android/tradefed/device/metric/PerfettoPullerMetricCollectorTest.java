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

package com.android.tradefed.device.metric;

import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.Pair;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.HashMap;

/** Unit tests for {@link PerfettoPullerMetricCollector}. */
@RunWith(JUnit4.class)
public class PerfettoPullerMetricCollectorTest {

    private PerfettoPullerMetricCollector mPerfettoMetricCollector;
    @Mock
    private ITestInvocationListener mMockListener;
    @Mock
    private ITestDevice mMockDevice;
    private IInvocationContext mContext;


    @Before
    public void setUp() {

        MockitoAnnotations.initMocks(this);
        mContext = new InvocationContext();
        mContext.addAllocatedDevice("default", mMockDevice);
        mPerfettoMetricCollector = Mockito.spy(new PerfettoPullerMetricCollector());
        mPerfettoMetricCollector.init(mContext, mMockListener);
    }

    @Test
    public void testNoProcessingFlow() throws Exception {

        OptionSetter setter = new OptionSetter(mPerfettoMetricCollector);
        setter.setOptionValue("pull-pattern-keys", "perfettofile");
        HashMap<String, Metric> currentMetrics = new HashMap<>();

        Mockito.when(mMockDevice.pullFile(Mockito.eq("/data/trace.pb")))
                .thenReturn(new File("trace"));

        TestDescription testDesc = new TestDescription("xyz", "abc");
        mPerfettoMetricCollector.testStarted(testDesc);
        mPerfettoMetricCollector.testEnded(testDesc, currentMetrics);

        assertTrue("Trace duration available but not expected.",
                currentMetrics.size() == 0);
    }

    @Test
    public void testProcessingFlow() throws Exception {

        OptionSetter setter = new OptionSetter(mPerfettoMetricCollector);
        setter.setOptionValue("pull-pattern-keys", "perfettofile");
        setter.setOptionValue("perfetto-binary-path", "trx");
        HashMap<String, Metric> currentMetrics = new HashMap<>();
        currentMetrics.put("perfettofile", TfMetricProtoUtil.stringToMetric("/data/trace.pb"));
        Mockito.when(mMockDevice.pullFile(Mockito.eq("/data/trace.pb")))
                .thenReturn(new File("trace"));

        TestDescription testDesc = new TestDescription("xyz", "abc");
        CommandResult cr = new CommandResult();
        cr.setStatus(CommandStatus.SUCCESS);
        cr.setStdout("abc:efg");

        Mockito.doReturn(cr).when(mPerfettoMetricCollector).runHostCommand(Mockito.any());

        mPerfettoMetricCollector.testStarted(testDesc);
        mPerfettoMetricCollector.testEnded(testDesc, currentMetrics);

        Mockito.verify(mPerfettoMetricCollector).runHostCommand(Mockito.any());
        Mockito.verify(mMockListener)
                .testLog(Mockito.eq("trace"), Mockito.eq(LogDataType.PB), Mockito.any());
        assertTrue("Expected two metrics that includes success status",
                currentMetrics.get("perfetto_trace_extractor_status").getMeasurements()
                        .getSingleString().equals("1"));
        assertTrue("Trace duration metrics not available but expected.",
                currentMetrics.get("perfetto_trace_extractor_runtime").getMeasurements()
                        .getSingleDouble() >= 0);
    }

    @Test
    public void testScriptFailureStatus() throws Exception {

        OptionSetter setter = new OptionSetter(mPerfettoMetricCollector);
        setter.setOptionValue("pull-pattern-keys", "perfettofile");
        setter.setOptionValue("perfetto-binary-path", "trx");
        HashMap<String, Metric> currentMetrics = new HashMap<>();
        currentMetrics.put("perfettofile", TfMetricProtoUtil.stringToMetric("/data/trace.pb"));
        Mockito.when(mMockDevice.pullFile(Mockito.eq("/data/trace.pb")))
                .thenReturn(new File("trace"));

        TestDescription testDesc = new TestDescription("xyz", "abc");
        CommandResult cr = new CommandResult();
        cr.setStatus(CommandStatus.FAILED);
        cr.setStdout("abc:efg");

        Mockito.doReturn(cr).when(mPerfettoMetricCollector).runHostCommand(Mockito.any());

        mPerfettoMetricCollector.testStarted(testDesc);
        mPerfettoMetricCollector.testEnded(testDesc, currentMetrics);

        Mockito.verify(mPerfettoMetricCollector).runHostCommand(Mockito.any());
        Mockito.verify(mMockListener)
                .testLog(Mockito.eq("trace"), Mockito.eq(LogDataType.PB), Mockito.any());
        assertTrue("Expected two metrics that includes failure status",
                currentMetrics.get("perfetto_trace_extractor_status").getMeasurements()
                        .getSingleString().equals("0"));
        assertTrue("Trace duration metrics not available but expected.",
                currentMetrics.get("perfetto_trace_extractor_runtime").getMeasurements()
                        .getSingleDouble() >= 0);
    }

    @Test
    public void testSplitKeyValue() {

        Assert.assertNull(PerfettoPullerMetricCollector.splitKeyValue("a:"));
        Assert.assertNull(PerfettoPullerMetricCollector.splitKeyValue(""));
        Assert.assertNull(PerfettoPullerMetricCollector.splitKeyValue(":a"));
        Assert.assertEquals(
                PerfettoPullerMetricCollector.splitKeyValue("abc:xyz"), new Pair<>("abc", "xyz"));
        Assert.assertEquals(
                PerfettoPullerMetricCollector.splitKeyValue("a:b:c:xyz"),
                new Pair<>("a:b:c", "xyz"));
    }
}
