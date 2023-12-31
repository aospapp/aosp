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

package com.android.nn.benchmark.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import androidx.test.InstrumentationRegistry;
import java.util.stream.Collectors;
import android.util.Log;

/** Information about available benchmarking models */
public class TestModels {
    /** Entry for a single benchmarking model */
    public static class TestModelEntry {
        /** Unique model name, used to find benchmark data */
        public final String mModelName;

        /** Expected inference performance in seconds */
        public final float mBaselineSec;

        /** Shape of input data */
        public final int[] mInputShape;

        /** File pair asset input/output pairs */
        public final InferenceInOutSequence.FromAssets[] mInOutAssets;

        /** Dataset inputs */
        public final InferenceInOutSequence.FromDataset[] mInOutDatasets;

        /** Readable name for test output */
        public final String mTestName;

        /** Name of model file, so that the same file can be reused */
        public final String mModelFile;

        /** The evaluator to use for validating the results. */
        public final EvaluatorConfig mEvaluator;

        /** Min SDK version that the model can run on. */
        public final int mMinSdkVersion;

        /* Number of bytes per input data entry  */
        public final int mInDataSize;

        public TestModelEntry(String modelName, float baselineSec, int[] inputShape,
                InferenceInOutSequence.FromAssets[] inOutAssets,
                InferenceInOutSequence.FromDataset[] inOutDatasets, String testName,
                String modelFile,
                EvaluatorConfig evaluator, int minSdkVersion, int inDataSize) {
            mModelName = modelName;
            mBaselineSec = baselineSec;
            mInputShape = inputShape;
            mInOutAssets = inOutAssets;
            mInOutDatasets = inOutDatasets;
            mTestName = testName;
            mModelFile = modelFile;
            mEvaluator = evaluator;
            mMinSdkVersion = minSdkVersion;
            mInDataSize = inDataSize;
        }

        // Used by VTS tests.
        public NNTestBase createNNTestBase() {
            return new NNTestBase(mModelName, mModelFile, mInputShape, mInOutAssets, mInOutDatasets,
                    mEvaluator, mMinSdkVersion);
        }

        public NNTestBase createNNTestBase(TfLiteBackend tfLiteBackend, boolean enableIntermediateTensorsDump) {
            return createNNTestBase(tfLiteBackend, enableIntermediateTensorsDump, /*mmapModel=*/false,
                /*useNnApiSl=*/false, /*extractNnApiSl=*/false, /*nnApiSlVendor=*/"");
        }

        // Used by CTS tests.
        public NNTestBase createNNTestBase(boolean useNNAPI, boolean enableIntermediateTensorsDump) {
            TfLiteBackend tfLiteBackend = useNNAPI ? TfLiteBackend.NNAPI : TfLiteBackend.CPU;
            return createNNTestBase(tfLiteBackend, enableIntermediateTensorsDump,
                /*mmapModel=*/false, /*useNnApiSl=*/false, /*extractNnApiSl=*/false, /*nnApiSlVendor=*/"");
        }

        public NNTestBase createNNTestBase(TfLiteBackend tfLiteBackend, boolean enableIntermediateTensorsDump,
                boolean mmapModel, boolean useNnApiSl, boolean extractNnApiSl, String nnApiSlVendor) {
            NNTestBase test = createNNTestBase();
            test.setTfLiteBackend(tfLiteBackend);
            test.enableIntermediateTensorsDump(enableIntermediateTensorsDump);
            test.setMmapModel(mmapModel);
            test.setUseNnApiSupportLibrary(useNnApiSl);
            test.setExtractNnApiSupportLibrary(extractNnApiSl);
            test.setNnApiSupportLibraryVendor(nnApiSlVendor);
            return test;
        }

        public String toString() {
            return mModelName;
        }

        public String getTestName() {
            return mTestName;
        }


        public TestModelEntry withDisabledEvaluation() {
            return new TestModelEntry(mModelName, mBaselineSec, mInputShape, mInOutAssets,
                    mInOutDatasets, mTestName, mModelFile,
                    null, // Disable evaluation.
                    mMinSdkVersion, mInDataSize);
        }
    }

    static private final List<TestModelEntry> sTestModelEntryList = new ArrayList<>();
    static private final AtomicReference<List<TestModelEntry>> frozenEntries =
            new AtomicReference<>(null);


    /** Add new benchmark model. */
    static public void registerModel(TestModelEntry model) {
        if (frozenEntries.get() != null) {
            throw new IllegalStateException("Can't register new models after its list is frozen");
        }
        sTestModelEntryList.add(model);
    }

    public static boolean isListFrozen() {
        return frozenEntries.get() != null;
    }

    static final String MODEL_FILTER_PROPERTY = "nnBenchmarkModelFilter";

    public static String getModelFilterRegex() {
        // All instrumentation arguments are passed as String so I have to convert the value here.
        return InstrumentationRegistry.getArguments().getString(MODEL_FILTER_PROPERTY, "");
    }

    /**
     * Returns the list of models eventually by a user specified instrumentation filter regex.
     */
    static public List<TestModelEntry> modelsList() {
        return modelsList(getModelFilterRegex());
    }

    /**
     * Returns the list of models eventually by a user specified instrumentation filter.
     */
    static public List<TestModelEntry> modelsList(String modelFilterRegex) {
        if (modelFilterRegex == null || modelFilterRegex.isEmpty()) {
            Log.i("NN_BENCHMARK", "No model filter, returning all models");
            return fullModelsList();
        }
        Log.i("NN_BENCHMARK", "Filtering model with filter " + modelFilterRegex);
        List<TestModelEntry> result = fullModelsList().stream()
                .filter( modelEntry ->
                    modelEntry.mModelName.matches(modelFilterRegex)
                )
                .collect(Collectors.toList());

        Log.i("NN_BENCHMARK", "Returning models: " + result);

        return result;
    }

    /**
     * Fetch list of test models.
     *
     * If this method was called at least once, then it's impossible to register new models.
     */
    static public List<TestModelEntry> fullModelsList() {
        frozenEntries.compareAndSet(null, sTestModelEntryList);
        return frozenEntries.get();
    }

    /** Fetch model by its name. */
    static public TestModelEntry getModelByName(String name) {
        for (TestModelEntry testModelEntry : modelsList()) {
            if (testModelEntry.mModelName.equals(name)) {
                return testModelEntry;
            }
        }
        throw new IllegalArgumentException("Unknown TestModelEntry named " + name);
    }

}
