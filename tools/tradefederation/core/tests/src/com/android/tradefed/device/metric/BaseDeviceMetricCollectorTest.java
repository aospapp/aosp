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

import static org.mockito.Mockito.times;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.Collections;

/** Unit tests for {@link BaseDeviceMetricCollector}. */
@RunWith(JUnit4.class)
public class BaseDeviceMetricCollectorTest {

    private BaseDeviceMetricCollector mBase;
    private IInvocationContext mContext;
    private ITestInvocationListener mMockListener;

    @Before
    public void setUp() {
        mBase = new BaseDeviceMetricCollector();
        mContext = new InvocationContext();
        mMockListener = Mockito.mock(ITestInvocationListener.class);
    }

    @Test
    public void testInitAndForwarding() {
        mBase.init(mContext, mMockListener);
        mBase.invocationStarted(mContext);
        mBase.testRunStarted("testRun", 1);
        TestIdentifier test = new TestIdentifier("class", "method");
        mBase.testStarted(test);
        mBase.testLog("dataname", LogDataType.TEXT, new ByteArrayInputStreamSource("".getBytes()));
        mBase.testFailed(test, "trace");
        mBase.testAssumptionFailure(test, "trace");
        mBase.testIgnored(test);
        mBase.testEnded(test, Collections.emptyMap());
        mBase.testRunFailed("test run failed");
        mBase.testRunStopped(0l);
        mBase.testRunEnded(0l, Collections.emptyMap());
        mBase.invocationFailed(new Throwable());
        mBase.invocationEnded(0l);

        Mockito.verify(mMockListener, times(1)).invocationStarted(Mockito.any());
        Mockito.verify(mMockListener, times(1)).testRunStarted("testRun", 1);
        Mockito.verify(mMockListener, times(1)).testStarted(Mockito.eq(test), Mockito.anyLong());
        Mockito.verify(mMockListener, times(1))
                .testLog(Mockito.eq("dataname"), Mockito.eq(LogDataType.TEXT), Mockito.any());
        Mockito.verify(mMockListener, times(1)).testFailed(test, "trace");
        Mockito.verify(mMockListener, times(1)).testAssumptionFailure(test, "trace");
        Mockito.verify(mMockListener, times(1)).testIgnored(test);
        Mockito.verify(mMockListener, times(1))
                .testEnded(Mockito.eq(test), Mockito.anyLong(), Mockito.eq(Collections.emptyMap()));
        Mockito.verify(mMockListener, times(1)).testRunFailed("test run failed");
        Mockito.verify(mMockListener, times(1)).testRunStopped(0l);
        Mockito.verify(mMockListener, times(1)).testRunEnded(0l, Collections.emptyMap());
        Mockito.verify(mMockListener, times(1)).invocationFailed(Mockito.any());
        Mockito.verify(mMockListener, times(1)).invocationEnded(0l);

        Assert.assertSame(mMockListener, mBase.getInvocationListener());
        Assert.assertEquals(0, mBase.getDevices().size());
        Assert.assertEquals(0, mBase.getBuildInfos().size());
    }
}
