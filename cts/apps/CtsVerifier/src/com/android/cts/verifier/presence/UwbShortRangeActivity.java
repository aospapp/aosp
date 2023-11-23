/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.cts.verifier.presence;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.widget.EditText;

import com.android.compatibility.common.util.ResultType;
import com.android.compatibility.common.util.ResultUnit;
import com.android.cts.verifier.PassFailButtons;
import com.android.cts.verifier.R;

/**
 * Activity for testing that UWB distance measurements are within the acceptable median.
 */
public class UwbShortRangeActivity extends PassFailButtons.Activity {
    private static final String TAG = UwbShortRangeActivity.class.getName();
    // Report log schema
    private static final String KEY_DISTANCE_MEDIAN_CM = "distance_median_cm";
    private static final String KEY_REFERENCE_DEVICE = "reference_device";
    // Median Thresholds
    private static final double MIN_MEDIAN = 0.75;
    private static final double MAX_MEDIAN = 1.25;
    private EditText mMedianInput;
    private EditText mReferenceDeviceInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uwb_short_range);
        setPassFailButtonClickListeners();
        getPassButton().setEnabled(false);

        mMedianInput = (EditText) findViewById(R.id.distance_median_meters);
        mReferenceDeviceInput = (EditText) findViewById(R.id.reference_device);

        DeviceFeatureChecker.checkFeatureSupported(this, getPassButton(),
                PackageManager.FEATURE_UWB);

        mMedianInput.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mReferenceDeviceInput.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
    }

    private void checkTestInputs() {
        getPassButton().setEnabled(checkMedianInput() && checkReferenceDeviceInput());
    }

    private boolean checkMedianInput() {
        String medianInput = mMedianInput.getText().toString();
        if (!medianInput.isEmpty()) {
            double median = Double.parseDouble(medianInput);
            return median >= MIN_MEDIAN && median <= MAX_MEDIAN;
        }
        return false;
    }

    private boolean checkReferenceDeviceInput() {
        return !mReferenceDeviceInput.getText().toString().isEmpty();
    }

    @Override
    public void recordTestResults() {
        String medianInput = mMedianInput.getText().toString();
        String referenceDeviceInput = mReferenceDeviceInput.getText().toString();
        if (!medianInput.isEmpty()) {
            Log.i(TAG, "UWB Distance Median: " + medianInput);
            getReportLog().addValue(KEY_DISTANCE_MEDIAN_CM, Double.parseDouble(medianInput),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }
        if (!referenceDeviceInput.isEmpty()) {
            Log.i(TAG, "UWB Reference Device: " + referenceDeviceInput);
            getReportLog().addValue(KEY_REFERENCE_DEVICE, referenceDeviceInput, ResultType.NEUTRAL,
                    ResultUnit.NONE);
        }
        getReportLog().submit();
    }
}
