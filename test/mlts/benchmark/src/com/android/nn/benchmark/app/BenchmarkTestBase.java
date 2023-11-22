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

package com.android.nn.benchmark.app;


import android.app.Activity;
import android.os.Bundle;
import android.os.Trace;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.nn.benchmark.core.BenchmarkException;
import com.android.nn.benchmark.core.BenchmarkResult;
import com.android.nn.benchmark.core.TestModels;
import com.android.nn.benchmark.core.TestModels.TestModelEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.util.List;

/**
 * Benchmark test-case super-class.
 *
 * Helper code for managing NNAPI/NNAPI-less benchamarks.
 */
@RunWith(Parameterized.class)
public class BenchmarkTestBase extends ActivityInstrumentationTestCase2<NNBenchmark> {
    // Only run 1 iteration now to fit the MediumTest time requirement.
    // One iteration means running the tests continuous for 1s.
    private NNBenchmark mActivity;
    protected final TestModelEntry mModel;

    // The default 0.3s warmup and 1.0s runtime give reasonably repeatable results (run-to-run
    // variability of ~20%) when run under performance settings (fixed CPU cores enabled and at
    // fixed frequency). The continuous build is not allowed to take much more than 1s so we
    // can't change the defaults for @MediumTest.
    protected static final float WARMUP_SHORT_SECONDS = 0.3f;
    protected static final float RUNTIME_SHORT_SECONDS = 1.f;

    // For running like a normal user-initiated app, the variability for 0.3s/1.0s is easily 3x.
    // With 2s/10s it's 20-50%. This @LargeTest allows running with these timings.
    protected static final float WARMUP_REPEATABLE_SECONDS = 2.f;
    protected static final float RUNTIME_REPEATABLE_SECONDS = 10.f;

    // For running a complete dataset, the run should complete under 5 minutes.
    protected static final float COMPLETE_SET_TIMEOUT_SECOND = 300.f;

    public BenchmarkTestBase(TestModelEntry model) {
        super(NNBenchmark.class);
        mModel = model;
    }

    protected void setUseNNApi(boolean useNNApi) {
        mActivity.setUseNNApi(useNNApi);
    }

    protected void setCompleteInputSet(boolean completeInputSet) {
        mActivity.setCompleteInputSet(completeInputSet);
    }

    // Initialize the parameter for ImageProcessingActivityJB.
    protected void prepareTest() {
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        mActivity = getActivity();
        mActivity.prepareInstrumentationTest();
        setUseNNApi(true);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        prepareTest();
        setActivityInitialTouchMode(false);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    class TestAction implements Runnable {
        TestModelEntry mTestModel;
        BenchmarkResult mResult;
        float mWarmupTimeSeconds;
        float mRunTimeSeconds;
        Throwable mException;

        public TestAction(TestModelEntry testName) {
            mTestModel = testName;
        }
        public TestAction(TestModelEntry testName, float warmupTimeSeconds, float runTimeSeconds) {
            mTestModel = testName;
            mWarmupTimeSeconds = warmupTimeSeconds;
            mRunTimeSeconds = runTimeSeconds;
        }

        public void run() {
            try {
                mResult = mActivity.mProcessor.getInstrumentationResult(
                    mTestModel, mWarmupTimeSeconds, mRunTimeSeconds);
            } catch (IOException e) {
                mException = e;
                e.printStackTrace();
            }
            Log.v(NNBenchmark.TAG,
                    "Benchmark for test \"" + mTestModel.toString() + "\" is: " + mResult);
            synchronized (this) {
                this.notify();
            }
        }

        public BenchmarkResult getBenchmark() {
            if (mException != null) {
                throw new Error("run failed", mException);
            }
            return mResult;
        }
    }

    // Set the benchmark thread to run on ui thread
    // Synchronized the thread such that the test will wait for the benchmark thread to finish
    public void runOnUiThread(Runnable action) {
        synchronized (action) {
            mActivity.runOnUiThread(action);
            try {
                action.wait();
            } catch (InterruptedException e) {
                Log.v(NNBenchmark.TAG, "waiting for action running on UI thread is interrupted: " +
                        e.toString());
            }
        }
    }

    public void runTest(TestAction ta, String testName) {
        float sum = 0;
        // For NNAPI systrace usage documentation, see
        // frameworks/ml/nn/common/include/Tracing.h.
        final String traceName = "[NN_LA_PO]" + testName;
        try {
            Trace.beginSection(traceName);
            runOnUiThread(ta);
        } finally {
            Trace.endSection();
        }
        BenchmarkResult bmValue = ta.getBenchmark();

        // post result to INSTRUMENTATION_STATUS
        getInstrumentation().sendStatus(Activity.RESULT_OK, bmValue.toBundle(testName));
    }

    @Parameters(name = "{0}")
    public static List<TestModelEntry> modelsList() {
        return TestModels.modelsList();
    }
}
