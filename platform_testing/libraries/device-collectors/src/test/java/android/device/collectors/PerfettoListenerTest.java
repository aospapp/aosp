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
package android.device.collectors;

import android.app.Instrumentation;
import android.os.Bundle;
import androidx.test.runner.AndroidJUnit4;

import com.android.helpers.PerfettoHelper;

import org.junit.After;
import org.junit.Before;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


/**
 * Android Unit tests for {@link PerfettoListener}.
 *
 * To run:
 * atest CollectorDeviceLibTest:android.device.collectors.PerfettoListenerTest
 */
@RunWith(AndroidJUnit4.class)
public class PerfettoListenerTest {

    // A {@code Description} to pass when faking a test run start call.
    private static final Description FAKE_DESCRIPTION = Description.createSuiteDescription("run");

    private Description mRunDesc;
    private Description mTest1Desc;
    private Description mTest2Desc;
    private PerfettoListener mListener;
    private Instrumentation mInstrumentation;
    private Map<String, Integer> mInvocationCount;
    private DataRecord mDataRecord;

    @Mock
    private PerfettoHelper mPerfettoHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRunDesc = Description.createSuiteDescription("run");
        mTest1Desc = Description.createTestDescription("run", "test1");
        mTest2Desc = Description.createTestDescription("run", "test2");
    }

    private PerfettoListener initListener(Bundle b) {
        mPerfettoHelper = spy(new PerfettoHelper());
        mInvocationCount = new HashMap<>();
        PerfettoListener listener = new PerfettoListener(b, mPerfettoHelper, mInvocationCount);
        mDataRecord = listener.createDataRecord();
        listener.setInstrumentation(mInstrumentation);
        return listener;
    }

    /*
     * Verify perfetto start and stop collection methods called exactly once for single test.
     */
    @Test
    public void testPerfettoPerTestSuccessFlow() throws Exception {
        Bundle b = new Bundle();
        mListener = initListener(b);
        doReturn(true).when(mPerfettoHelper).startCollecting(anyString());
        doReturn(true).when(mPerfettoHelper).stopCollecting(anyLong(), anyString());

        // Test run start behavior
        mListener.testRunStarted(mRunDesc);

        // Test test start behavior
        mListener.testStarted(mTest1Desc);
        verify(mPerfettoHelper, times(1)).startCollecting(anyString());
        mListener.onTestEnd(mDataRecord, mTest1Desc);
        verify(mPerfettoHelper, times(1)).stopCollecting(anyLong(), anyString());

    }

    /*
     * Verify perfetto start and stop collection methods called exactly once for test run.
     * and not during each test method.
     */
    @Test
    public void testPerfettoPerRunSuccessFlow() throws Exception {
        Bundle b = new Bundle();
        b.putString(PerfettoListener.COLLECT_PER_RUN, "true");
        mListener = initListener(b);
        doReturn(true).when(mPerfettoHelper).startCollecting(anyString());
        doReturn(true).when(mPerfettoHelper).stopCollecting(anyLong(), anyString());

        // Test run start behavior
        mListener.onTestRunStart(mListener.createDataRecord(), FAKE_DESCRIPTION);
        verify(mPerfettoHelper, times(1)).startCollecting(anyString());
        mListener.testStarted(mTest1Desc);
        verify(mPerfettoHelper, times(1)).startCollecting(anyString());
        mListener.onTestEnd(mDataRecord, mTest1Desc);
        verify(mPerfettoHelper, times(0)).stopCollecting(anyLong(), anyString());
        mListener.onTestRunEnd(mListener.createDataRecord(), new Result());
        verify(mPerfettoHelper, times(1)).stopCollecting(anyLong(), anyString());
    }

    /*
     * Verify stop is not called if perfetto start is not success.
     */
    @Test
    public void testPerfettoPerRunFailureFlow() throws Exception {
        Bundle b = new Bundle();
        b.putString(PerfettoListener.COLLECT_PER_RUN, "true");
        mListener = initListener(b);
        doReturn(false).when(mPerfettoHelper).startCollecting(anyString());

        // Test run start behavior
        mListener.onTestRunStart(mListener.createDataRecord(), FAKE_DESCRIPTION);
        verify(mPerfettoHelper, times(1)).startCollecting(anyString());
        mListener.onTestRunEnd(mListener.createDataRecord(), new Result());
        verify(mPerfettoHelper, times(0)).stopCollecting(anyLong(), anyString());
    }

    /*
     * Verify perfetto stop is not invoked if start did not succeed.
     */
    @Test
    public void testPerfettoStartFailureFlow() throws Exception {
        Bundle b = new Bundle();
        mListener = initListener(b);
        doReturn(false).when(mPerfettoHelper).startCollecting(anyString());

        // Test run start behavior
        mListener.testRunStarted(mRunDesc);

        // Test test start behavior
        mListener.testStarted(mTest1Desc);
        verify(mPerfettoHelper, times(1)).startCollecting(anyString());
        mListener.onTestEnd(mDataRecord, mTest1Desc);
        verify(mPerfettoHelper, times(0)).stopCollecting(anyLong(), anyString());
    }

    /*
     * Verify test method invocation count is updated successfully based on the number of times the
     * test method is invoked.
     */
    @Test
    public void testPerfettoInvocationCount() throws Exception {
        Bundle b = new Bundle();
        mListener = initListener(b);
        doReturn(true).when(mPerfettoHelper).startCollecting(anyString());
        doReturn(true).when(mPerfettoHelper).stopCollecting(anyLong(), anyString());

        // Test run start behavior
        mListener.testRunStarted(mRunDesc);

        // Test1 invocation 1 start behavior
        mListener.testStarted(mTest1Desc);
        verify(mPerfettoHelper, times(1)).startCollecting(anyString());
        mListener.onTestEnd(mDataRecord, mTest1Desc);
        verify(mPerfettoHelper, times(1)).stopCollecting(anyLong(), anyString());

        // Test1 invocation 2 start behaviour
        mListener.testStarted(mTest1Desc);
        verify(mPerfettoHelper, times(2)).startCollecting(anyString());
        mListener.onTestEnd(mDataRecord, mTest1Desc);
        verify(mPerfettoHelper, times(2)).stopCollecting(anyLong(), anyString());

        // Test2 invocation 1 start behaviour
        mListener.testStarted(mTest2Desc);
        verify(mPerfettoHelper, times(3)).startCollecting(anyString());
        mDataRecord = mListener.createDataRecord();
        mListener.onTestEnd(mDataRecord, mTest2Desc);
        verify(mPerfettoHelper, times(3)).stopCollecting(anyLong(), anyString());

        // Check if the the test count is incremented properly.
        assertEquals(2, (int) mInvocationCount.get(mListener.getTestFileName(mTest1Desc)));
        assertEquals(1, (int) mInvocationCount.get(mListener.getTestFileName(mTest2Desc)));

    }
}
