/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.widget.EditText;

import com.android.compatibility.common.util.ResultType;
import com.android.compatibility.common.util.ResultUnit;
import com.android.cts.verifier.PassFailButtons;
import com.android.cts.verifier.R;

/** Tests the precision of the device's RSSI measurement wtfdelet */
public class BleRssiPrecisionActivity extends PassFailButtons.Activity {
    private static final String TAG = BleRssiPrecisionActivity.class.getName();

    // Report log schema
    private static final String KEY_RSSI_RANGE_DBM = "rssi_range_dbm";
    private static final String KEY_REFERENCE_DEVICE = "reference_device";

    // Thresholds
    private static final int MAX_RSSI_RANGE_DBM = 18;

    private EditText reportRssiRangeEditText;
    private EditText reportReferenceDeviceEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_rssi_precision);
        setPassFailButtonClickListeners();
        getPassButton().setEnabled(false);

        reportRssiRangeEditText = findViewById(R.id.report_rssi_range);
        reportReferenceDeviceEditText = findViewById(R.id.report_reference_device);

        DeviceFeatureChecker.checkFeatureSupported(this, getPassButton(),
                PackageManager.FEATURE_BLUETOOTH_LE);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (!adapter.isEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.ble_bluetooth_disable_title)
                    .setMessage(R.string.ble_bluetooth_disable_message)
                    .setOnCancelListener(dialog -> finish())
                    .create().show();
        }

        reportRssiRangeEditText.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        reportReferenceDeviceEditText.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
    }

    private void checkTestInputs() {
        getPassButton().setEnabled(checkDistanceRangeInput() && checkReferenceDeviceInput());
    }

    private boolean checkDistanceRangeInput() {
        String rssiRangeInput = reportRssiRangeEditText.getText().toString();

        if (!rssiRangeInput.isEmpty()) {
            int rssiRange = Integer.parseInt(rssiRangeInput);
            // RSSI range must be inputted and within acceptable range before test can be passed
            return rssiRange <= MAX_RSSI_RANGE_DBM;
        }
        return false;
    }

    private boolean checkReferenceDeviceInput() {
        // Reference device must be inputted before test can be passed
        return !reportReferenceDeviceEditText.getText().toString().isEmpty();
    }

    @Override
    public void recordTestResults() {
        String rssiRange = reportRssiRangeEditText.getText().toString();
        String referenceDevice = reportReferenceDeviceEditText.getText().toString();

        if (!rssiRange.isEmpty()) {
            Log.i(TAG, "BLE RSSI Range (dBm): " + rssiRange);
            getReportLog().addValue(KEY_RSSI_RANGE_DBM, Integer.parseInt(rssiRange),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!referenceDevice.isEmpty()) {
            Log.i(TAG, "BLE Reference Device: " + referenceDevice);
            getReportLog().addValue(KEY_REFERENCE_DEVICE, referenceDevice,
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }
        getReportLog().submit();
    }
}
