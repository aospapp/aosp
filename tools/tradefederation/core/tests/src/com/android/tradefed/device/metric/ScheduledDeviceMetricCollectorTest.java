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

import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.RunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link ScheduledDeviceMetricCollector}. */
@RunWith(JUnit4.class)
public class ScheduledDeviceMetricCollectorTest {

    public static class TestableAsyncTimer extends ScheduledDeviceMetricCollector {
        private int mInternalCounter = 0;

        @Override
        void collect(DeviceMetricData runData) throws InterruptedException {
            mInternalCounter++;
            runData.addStringMetric("key" + mInternalCounter, "value" + mInternalCounter);
        }
    }

    private TestableAsyncTimer mBase;
    private IInvocationContext mContext;
    private ITestInvocationListener mMockListener;

    @Before
    public void setUp() {
        mBase = new TestableAsyncTimer();
        mContext = new InvocationContext();
        mMockListener = Mockito.mock(ITestInvocationListener.class);
    }

    /** Test the periodic run of the collector once testRunStarted has been called. */
    @Test
    public void testSetupAndPeriodicRun() throws Exception {
        OptionSetter setter = new OptionSetter(mBase);
        // 100 ms interval
        setter.setOptionValue("interval", "100");
        Map<String, String> metrics = new HashMap<>();
        mBase.init(mContext, mMockListener);
        try {
            mBase.testRunStarted("testRun", 1);
            RunUtil.getDefault().sleep(500);
        } finally {
            mBase.testRunEnded(0l, metrics);
        }
        // We give it 500msec to run and 100msec interval we should easily have at least three
        // iterations
        assertTrue(metrics.containsKey("key1"));
        assertTrue(metrics.containsKey("key2"));
        assertTrue(metrics.containsKey("key3"));
    }
}
