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

import com.google.common.collect.ImmutableMap;

import java.util.Arrays;
import java.util.List;

/**
 * Activity for testing that NAN measurements are within the acceptable ranges
 */
public class NanPrecisionTestActivity extends PassFailButtons.Activity {
    private static final String TAG = NanPrecisionTestActivity.class.getName();

    // Report log schema
    private static final String KEY_BANDWIDTH = "nan_bandwidth";
    private static final String KEY_MEASUREMENT_RANGE_10CM_AT_68P = "measurement_range_10cm_68p";
    private static final String KEY_MEASUREMENT_RANGE_1M_AT_68P = "measurement_range_1m_68p";
    private static final String KEY_MEASUREMENT_RANGE_3M_AT_68p = "measurement_range_3m_68p";
    private static final String KEY_MEASUREMENT_RANGE_5M_AT_68p = "measurement_range_5m_68p";
    private static final String KEY_MEASUREMENT_RANGE_10CM_AT_90P = "measurement_range_10cm_90p";
    private static final String KEY_MEASUREMENT_RANGE_1M_AT_90P = "measurement_range_1m_90p";
    private static final String KEY_MEASUREMENT_RANGE_3M_AT_90p = "measurement_range_3m_90p";
    private static final String KEY_MEASUREMENT_RANGE_5M_AT_90p = "measurement_range_5m_90p";
    private static final String KEY_REFERENCE_DEVICE = "reference_device";

    // Thresholds
    private static final int MAX_DISTANCE_RANGE_METERS_160MHZ = 2;
    private static final int MAX_DISTANCE_RANGE_METERS_80MHZ = 4;
    private static final int MAX_DISTANCE_RANGE_METERS_40MHZ = 8;
    private static final int MAX_DISTANCE_RANGE_METERS_20MHZ = 16;

    // Maps NAN bandwidths to acceptable range thresholds
    private static final ImmutableMap<Integer, Integer> BANDWIDTH_TO_THRESHOLD_MAP =
            ImmutableMap.of(160, MAX_DISTANCE_RANGE_METERS_160MHZ, 80,
                    MAX_DISTANCE_RANGE_METERS_80MHZ, 40, MAX_DISTANCE_RANGE_METERS_40MHZ, 20,
                    MAX_DISTANCE_RANGE_METERS_20MHZ);

    private EditText mBandwidthMhz;
    private EditText mMeasurementRange10cmGt68p;
    private EditText mMeasurementRange1mGt68p;
    private EditText mMeasurementRange3mGt68p;
    private EditText mMeasurementRange5mGt68p;
    private EditText mMeasurementRange10cmGt90p;
    private EditText mMeasurementRange1mGt90p;
    private EditText mMeasurementRange3mGt90p;
    private EditText mMeasurementRange5mGt90p;
    private EditText mReferenceDeviceInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.nan_precision);
        setPassFailButtonClickListeners();
        getPassButton().setEnabled(false);

        mBandwidthMhz = (EditText) findViewById(R.id.nan_bandwidth);
        mMeasurementRange10cmGt68p = (EditText) findViewById(R.id.distance_range_10cm_gt_68p);
        mMeasurementRange1mGt68p = (EditText) findViewById(R.id.distance_range_1m_gt_68p);
        mMeasurementRange3mGt68p = (EditText) findViewById(R.id.distance_range_3m_gt_68p);
        mMeasurementRange5mGt68p = (EditText) findViewById(R.id.distance_range_5m_gt_68p);
        mMeasurementRange10cmGt90p = (EditText) findViewById(R.id.distance_range_10cm_gt_90p);
        mMeasurementRange1mGt90p = (EditText) findViewById(R.id.distance_range_1m_gt_90p);
        mMeasurementRange3mGt90p = (EditText) findViewById(R.id.distance_range_3m_gt_90p);
        mMeasurementRange5mGt90p = (EditText) findViewById(R.id.distance_range_5m_gt_90p);
        mReferenceDeviceInput = (EditText) findViewById(R.id.reference_device);

        DeviceFeatureChecker.checkFeatureSupported(this, getPassButton(),
                PackageManager.FEATURE_WIFI_AWARE);

        mBandwidthMhz.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange10cmGt68p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange1mGt68p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange3mGt68p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange5mGt68p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange10cmGt90p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange1mGt90p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange3mGt90p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mMeasurementRange5mGt90p.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        mReferenceDeviceInput.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
    }

    private void checkTestInputs() {
        getPassButton().setEnabled(checkMeasurementRange68thPercentileInput()
                && checkMeasurementRange90thPercentileInput()
                && checkReferenceDeviceInput());
    }

    private boolean checkMeasurementRange68thPercentileInput() {
        return checkRequiredMeasurementRangeInput(mMeasurementRange10cmGt68p,
                mMeasurementRange1mGt68p, mMeasurementRange3mGt68p, mMeasurementRange5mGt68p);
    }

    private boolean checkMeasurementRange90thPercentileInput() {
        String measurementRangeInput10cmGt90p = mMeasurementRange10cmGt90p.getText().toString();
        String measurementRangeInput1mGt90p = mMeasurementRange1mGt90p.getText().toString();
        String measurementRangeInput3mGt90p = mMeasurementRange3mGt90p.getText().toString();
        String measurementRangeInput5mGt90p = mMeasurementRange5mGt90p.getText().toString();
        List<String> optionalMeasurementRangeList = Arrays.asList(measurementRangeInput10cmGt90p,
                measurementRangeInput1mGt90p,
                measurementRangeInput3mGt90p, measurementRangeInput5mGt90p);

        boolean inputted = false;
        for (String input : optionalMeasurementRangeList) {
            if (!input.isEmpty()) {
                inputted = true;
                break;
            }
        }
        // If one of the ranges is inputted for one of the distances, then it becomes required
        // that the ranges are inputted for all the distances and for tests to pass, must be
        // acceptable values
        return !inputted || checkRequiredMeasurementRangeInput(mMeasurementRange10cmGt90p,
                mMeasurementRange1mGt90p, mMeasurementRange3mGt90p, mMeasurementRange5mGt90p);
    }

    private boolean checkRequiredMeasurementRangeInput(EditText rangeInput10cm,
            EditText rangeInput1m, EditText rangeInput3m, EditText rangeInput5m) {
        String bandwidthInputMhz = mBandwidthMhz.getText().toString();
        String measurementRangeInput10cmGt = rangeInput10cm.getText().toString();
        String measurementRangeInput1mGt = rangeInput1m.getText().toString();
        String measurementRangeInput3mGt = rangeInput3m.getText().toString();
        String measurementRangeInput5mGt = rangeInput5m.getText().toString();
        List<String> requiredMeasurementRangeList = Arrays.asList(measurementRangeInput10cmGt,
                measurementRangeInput1mGt,
                measurementRangeInput3mGt, measurementRangeInput5mGt);

        for (String input : requiredMeasurementRangeList) {
            if (bandwidthInputMhz.isEmpty() || input.isEmpty()) {
                // Distance range must be inputted for all fields so fail early otherwise
                return false;
            }
            if (!BANDWIDTH_TO_THRESHOLD_MAP.containsKey(Integer.parseInt(bandwidthInputMhz))) {
                // bandwidth must be one of the expected thresholds
                return false;
            }
            double distanceRange = Double.parseDouble(input);
            int bandwidth = Integer.parseInt(bandwidthInputMhz);
            if (distanceRange > BANDWIDTH_TO_THRESHOLD_MAP.get(bandwidth)) {
                // All inputs must be in acceptable range so fail early otherwise
                return false;
            }
        }
        return true;
    }

    private boolean checkReferenceDeviceInput() {
        // Reference device used must be inputted before test can be passed.
        return !mReferenceDeviceInput.getText().toString().isEmpty();
    }

    @Override
    public void recordTestResults() {
        String nanBandwidthMhz = mBandwidthMhz.getText().toString();
        String measurementRange10cmGt68p = mMeasurementRange10cmGt68p.getText().toString();
        String measurementRange1mGt68p = mMeasurementRange1mGt68p.getText().toString();
        String measurementRange3mGt68p = mMeasurementRange3mGt68p.getText().toString();
        String measurementRange5mGt68p = mMeasurementRange5mGt68p.getText().toString();
        String measurementRange10cmGt90p = mMeasurementRange10cmGt90p.getText().toString();
        String measurementRange1mGt90p = mMeasurementRange1mGt90p.getText().toString();
        String measurementRange3mGt90p = mMeasurementRange3mGt90p.getText().toString();
        String measurementRange5mGt90p = mMeasurementRange5mGt90p.getText().toString();
        String referenceDevice = mReferenceDeviceInput.getText().toString();

        if (!nanBandwidthMhz.isEmpty()) {
            Log.i(TAG, "NAN Bandwidth at which data was collected: " + nanBandwidthMhz);
            getReportLog().addValue(KEY_BANDWIDTH,
                    Integer.parseInt(nanBandwidthMhz),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange10cmGt68p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 10cm: " + measurementRange10cmGt68p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_10CM_AT_68P,
                    Double.parseDouble(measurementRange10cmGt68p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange1mGt68p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 1m: " + measurementRange1mGt68p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_1M_AT_68P,
                    Double.parseDouble(measurementRange1mGt68p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange3mGt68p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 3m: " + measurementRange3mGt68p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_3M_AT_68p,
                    Double.parseDouble(measurementRange3mGt68p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange5mGt68p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 5m: " + measurementRange5mGt68p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_5M_AT_68p,
                    Double.parseDouble(measurementRange5mGt68p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange10cmGt90p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 10cm: " + measurementRange10cmGt68p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_10CM_AT_90P,
                    Double.parseDouble(measurementRange10cmGt90p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange1mGt90p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 1m: " + measurementRange1mGt90p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_1M_AT_90P,
                    Double.parseDouble(measurementRange1mGt90p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange3mGt90p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 3m: " + measurementRange3mGt90p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_3M_AT_90p,
                    Double.parseDouble(measurementRange3mGt90p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!measurementRange5mGt90p.isEmpty()) {
            Log.i(TAG, "NAN Measurement Range at 5m: " + measurementRange5mGt90p);
            getReportLog().addValue(KEY_MEASUREMENT_RANGE_5M_AT_90p,
                    Double.parseDouble(measurementRange5mGt90p),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!referenceDevice.isEmpty()) {
            Log.i(TAG, "NAN Reference Device: " + referenceDevice);
            getReportLog().addValue(KEY_REFERENCE_DEVICE, referenceDevice,
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }
        getReportLog().submit();
    }
}
