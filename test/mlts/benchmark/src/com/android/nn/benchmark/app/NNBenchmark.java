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

package com.android.nn.benchmark.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Trace;
import android.util.Log;
import android.util.Pair;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.nn.benchmark.core.BenchmarkException;
import com.android.nn.benchmark.core.BenchmarkResult;
import com.android.nn.benchmark.core.InferenceInOutSequence;
import com.android.nn.benchmark.core.InferenceResult;
import com.android.nn.benchmark.core.NNTestBase;
import com.android.nn.benchmark.core.TestModels;
import com.android.nn.benchmark.core.UnsupportedSdkException;

import java.util.List;
import java.io.IOException;

public class NNBenchmark extends Activity {
    protected static final String TAG = "NN_BENCHMARK";

    public static final String EXTRA_ENABLE_LONG = "enable long";
    public static final String EXTRA_ENABLE_PAUSE = "enable pause";
    public static final String EXTRA_DISABLE_NNAPI = "disable NNAPI";
    public static final String EXTRA_DEMO = "demo";
    public static final String EXTRA_TESTS = "tests";

    public static final String EXTRA_RESULTS_TESTS = "tests";
    public static final String EXTRA_RESULTS_RESULTS = "results";

    private int mTestList[];
    private BenchmarkResult mTestResults[];

    private TextView mTextView;
    private boolean mToggleLong;
    private boolean mTogglePause;

    private boolean mUseNNApi;
    private boolean mCompleteInputSet;

    protected void setUseNNApi(boolean useNNApi) {
        mUseNNApi = useNNApi;
    }

    protected void setCompleteInputSet(boolean completeInputSet) {
        mCompleteInputSet = completeInputSet;
    }

    // Initialize the parameters for Instrumentation tests.
    protected void prepareInstrumentationTest() {
        mTestList = new int[1];
        mTestResults = new BenchmarkResult[1];
        mProcessor = new Processor();
    }

    /////////////////////////////////////////////////////////////////////////
    // Processor is a helper thread for running the work without
    // blocking the UI thread.
    class Processor extends Thread {
        private float mLastResult;
        private boolean mRun = true;
        private boolean mDoingBenchmark;
        private NNTestBase mTest;

        // Method to retrieve benchmark results for instrumentation tests.
        BenchmarkResult getInstrumentationResult(
                TestModels.TestModelEntry t, float warmupTimeSeconds, float runTimeSeconds)
                throws IOException {
            mTest = changeTest(t);
            return getBenchmark(warmupTimeSeconds, runTimeSeconds);
        }

        // Run one loop of kernels for at least the specified minimum time.
        // The function returns the average time in ms for the test run
        private BenchmarkResult runBenchmarkLoop(float minTime, boolean completeInputSet)
                throws IOException {
            try {
                // Run the kernel
                Pair<List<InferenceInOutSequence>, List<InferenceResult>> results;
                if (minTime > 0.f) {
                    if (completeInputSet) {
                        results = mTest.runBenchmarkCompleteInputSet(1, minTime);
                    } else {
                        results = mTest.runBenchmark(minTime);
                    }
                } else {
                    results = mTest.runInferenceOnce();
                }
                return BenchmarkResult.fromInferenceResults(
                        mTest.getTestInfo(),
                        mUseNNApi ? BenchmarkResult.BACKEND_TFLITE_NNAPI
                                : BenchmarkResult.BACKEND_TFLITE_CPU,
                        results.first, results.second, mTest.getEvaluator());
            } catch (BenchmarkException e) {
                return new BenchmarkResult(e.getMessage());
            }
        }


        // Get a benchmark result for a specific test
        private BenchmarkResult getBenchmark(float warmupTimeSeconds, float runTimeSeconds)
            throws IOException {
            try {
                mTest.checkSdkVersion();
            } catch (UnsupportedSdkException e) {
                return new BenchmarkResult(e.getMessage());
            }

            mDoingBenchmark = true;

            long result = 0;

            // We run a short bit of work before starting the actual test
            // this is to let any power management do its job and respond.
            // For NNAPI systrace usage documentation, see
            // frameworks/ml/nn/common/include/Tracing.h.
            try {
                final String traceName = "[NN_LA_PWU]runBenchmarkLoop";
                Trace.beginSection(traceName);
                runBenchmarkLoop(warmupTimeSeconds, false);
            } finally {
                Trace.endSection();
            }

            // Run the actual benchmark
            BenchmarkResult r;
            try {
                final String traceName = "[NN_LA_PBM]runBenchmarkLoop";
                Trace.beginSection(traceName);
                r = runBenchmarkLoop(runTimeSeconds, mCompleteInputSet);
            } finally {
                Trace.endSection();
            }

            Log.v(TAG, "Test: " + r.toString());

            mDoingBenchmark = false;
            return r;
        }

        @Override
        public void run() {
            while (mRun) {
                // Our loop for launching tests or benchmarks
                synchronized (this) {
                    // We may have been asked to exit while waiting
                    if (!mRun) return;
                }

                try {
                    // Loop over the tests we want to benchmark
                    for (int ct = 0; (ct < mTestList.length) && mRun; ct++) {

                        // For reproducibility we wait a short time for any sporadic work
                        // created by the user touching the screen to launch the test to pass.
                        // Also allows for things to settle after the test changes.
                        try {
                            sleep(250);
                        } catch (InterruptedException e) {
                        }

                        // If we just ran a test, we destroy it here to relieve some memory
                        // pressure

                        if (mTest != null) {
                            mTest.destroy();
                        }

                        TestModels.TestModelEntry testModel =
                            TestModels.modelsList().get(mTestList[ct]);
                        int testNumber = ct + 1;
                        runOnUiThread(() -> {
                            mTextView.setText(
                                String.format(
                                    "Running test %d of %d: %s",
                                    testNumber,
                                    mTestList.length,
                                    testModel.toString()));
                        });

                        // Select the next test
                        mTest = changeTest(testModel);

                        // If the user selected the "long pause" option, wait
                        if (mTogglePause) {
                            for (int i = 0; (i < 100) && mRun; i++) {
                                try {
                                    sleep(100);
                                } catch (InterruptedException e) {
                                }
                            }
                        }

                        // Run the test
                        float warmupTime = 0.3f;
                        float runTime = 1.f;
                        if (mToggleLong) {
                            warmupTime = 2.f;
                            runTime = 10.f;
                        }
                        mTestResults[ct] = getBenchmark(warmupTime, runTime);
                    }
                    onBenchmarkFinish(mRun);
                } catch (IOException e) {
                    Log.e(TAG, "Exception during benchmark run", e);
                    break;
                }
            }
        }

        public void exit() {
            mRun = false;

            synchronized (this) {
                notifyAll();
            }

            try {
                this.join();
            } catch (InterruptedException e) {
            }

            if (mTest != null) {
                mTest.destroy();
                mTest = null;
            }
        }
    }


    private boolean mDoingBenchmark;
    public Processor mProcessor;

    NNTestBase changeTest(TestModels.TestModelEntry t) {
        NNTestBase tb = t.createNNTestBase(mUseNNApi,
                false /* enableIntermediateTensorsDump */);
        tb.setupModel(this);
        return tb;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTextView = new TextView(this);
        mTextView.setTextSize(20);
        mTextView.setText("Running NN benchmark...");
        setContentView(mTextView);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mProcessor != null) {
            mProcessor.exit();
        }
    }

    public void onBenchmarkFinish(boolean ok) {
        if (ok) {
            Intent intent = new Intent();
            intent.putExtra(EXTRA_RESULTS_TESTS, mTestList);
            intent.putExtra(EXTRA_RESULTS_RESULTS, mTestResults);
            setResult(RESULT_OK, intent);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent i = getIntent();
        mTestList = i.getIntArrayExtra(EXTRA_TESTS);
        mToggleLong = i.getBooleanExtra(EXTRA_ENABLE_LONG, false);
        mTogglePause = i.getBooleanExtra(EXTRA_ENABLE_PAUSE, false);
        setUseNNApi(!i.getBooleanExtra(EXTRA_DISABLE_NNAPI, false));

        if (mTestList != null) {
            mTestResults = new BenchmarkResult[mTestList.length];
            mProcessor = new Processor();
            mProcessor.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
