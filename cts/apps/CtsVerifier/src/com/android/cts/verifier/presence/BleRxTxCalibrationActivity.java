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

/**
 * Tests that the device's Rx/Tx calibration results in a median range (cm) within the specified
 * bounds
 */
public class BleRxTxCalibrationActivity extends PassFailButtons.Activity {
    private static final String TAG = BleRxTxCalibrationActivity.class.getName();

    // Report log schema
    private static final String KEY_CHANNEL_RSSI_RANGE = "channel_rssi_range";
    private static final String KEY_CORE_RSSI_RANGE = "core_rssi_range";
    private static final String KEY_REFERENCE_DEVICE = "reference_device";

    // Thresholds
    private static final int MAX_RSSI_RANGE = 6;

    private EditText reportChannelsRssiRangeEditText;
    private EditText reportCoresRssiRangeEditText;
    private EditText reportReferenceDeviceEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_rx_tx_calibration);
        setPassFailButtonClickListeners();
        getPassButton().setEnabled(false);

        reportChannelsRssiRangeEditText = findViewById(R.id.report_channels_rssi_range);
        reportCoresRssiRangeEditText = findViewById(R.id.report_cores_rssi_range);
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

        reportChannelsRssiRangeEditText.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        reportCoresRssiRangeEditText.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
        reportReferenceDeviceEditText.addTextChangedListener(
                InputTextHandler.getOnTextChangedHandler((Editable s) -> checkTestInputs()));
    }

    private void checkTestInputs() {
        getPassButton().setEnabled(
                checkChannelRssiInput() && checkCoreRssiInput() && checkReferenceDeviceInput());
    }

    private boolean checkChannelRssiInput() {
        String channelsRssiRangeInput = reportChannelsRssiRangeEditText.getText().toString();
        if (!channelsRssiRangeInput.isEmpty()) {
            int channelsRssiRange = Integer.parseInt(channelsRssiRangeInput);
            // RSSI range must be inputted and within acceptable range before test can be passed
            return channelsRssiRange <= MAX_RSSI_RANGE;
        }
        return false;
    }

    private boolean checkCoreRssiInput() {
        String coresRssiRangeInput = reportCoresRssiRangeEditText.getText().toString();
        if (!coresRssiRangeInput.isEmpty()) {
            int coresRssiRange = Integer.parseInt(coresRssiRangeInput);
            // RSSI range must be inputted and within acceptable range before test can be passed
            return coresRssiRange <= MAX_RSSI_RANGE;
        }
        // This field is optional, so return true even if the user has not inputted anything
        return true;
    }

    private boolean checkReferenceDeviceInput() {
        // Reference device must be inputted before test can be passed
        return !reportReferenceDeviceEditText.getText().toString().isEmpty();
    }

    @Override
    public void recordTestResults() {
        String channelRssiRange = reportChannelsRssiRangeEditText.getText().toString();
        String coreRssiRange = reportCoresRssiRangeEditText.getText().toString();
        String referenceDevice = reportReferenceDeviceEditText.getText().toString();

        if (!channelRssiRange.isEmpty()) {
            Log.i(TAG, "BLE RSSI Range Across Channels (dBm): " + channelRssiRange);
            getReportLog().addValue(KEY_CHANNEL_RSSI_RANGE, Integer.parseInt(channelRssiRange),
                    ResultType.NEUTRAL, ResultUnit.NONE);
        }

        if (!coreRssiRange.isEmpty()) {
            Log.i(TAG, "BLE RSSI Range Across Cores (dBm): " + coreRssiRange);
            getReportLog().addValue(KEY_CORE_RSSI_RANGE, Integer.parseInt(coreRssiRange),
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
