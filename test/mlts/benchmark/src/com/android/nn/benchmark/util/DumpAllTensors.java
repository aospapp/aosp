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

package com.android.nn.benchmark.util;

import android.app.Activity;
import android.os.Bundle;
import com.android.nn.benchmark.core.NNTestBase;
import com.android.nn.benchmark.core.TestModels.TestModelEntry;
import com.android.nn.benchmark.core.TestModels;
import java.io.IOException;
import java.io.File;

/** Helper activity for dumping state of interference intermediate tensors.
 *
 * Example usage:
 * adb shell am start -n  com.android.nn.benchmark.app/com.android.nn.benchmark.\
 *  util.DumpAllTensors --es modelName mobilenet_quantized inputAssetIndex 0 useNNAPI true
 *
 * Assets will be then dumped into /data/data/com.android.nn.benchmark.app/files/dump
 * To fetch:
 * adb pull /data/data/com.android.benchmark.app/files/dump
 *
 */
public class DumpAllTensors extends Activity {
    public static final String EXTRA_MODEL_NAME = "modelName";
    public static final String EXTRA_USE_NNAPI = "useNNAPI";
    public static final String EXTRA_INPUT_ASSET_INDEX= "inputAssetIndex";
    public static final String EXTRA_INPUT_ASSET_SIZE= "inputAssetSize";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent().getExtras();

        String modelName = extras.getString(EXTRA_MODEL_NAME);
        if (modelName == null) {
            throw new IllegalArgumentException("No modelName extra passed with intent");
        }
        boolean useNNAPI = extras.getBoolean(EXTRA_USE_NNAPI, false);
        int inputAssetIndex = extras.getInt(EXTRA_INPUT_ASSET_INDEX, 0);
        int inputAssetSize = extras.getInt(EXTRA_INPUT_ASSET_SIZE, 1);

        try {
            File dumpDir = new File(getFilesDir(), "dump");
            deleteRecursive(dumpDir);
            dumpDir.mkdir();

            TestModelEntry modelEntry = TestModels.getModelByName(modelName);
            NNTestBase testBase = modelEntry.createNNTestBase(useNNAPI, true);
            testBase.setupModel(this);
            testBase.dumpAllLayers(dumpDir, inputAssetIndex, inputAssetSize);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to dump tensors", e);
        }
        finish();
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }
}
