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

package com.android.cts.verifier.presence.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.util.Log;

import com.google.common.base.Preconditions;

import java.util.Arrays;

/** Advertises BLE beacons. */
@SuppressLint("MissingPermission")
public class BleAdvertiser {
    private static final String TAG = BleAdvertiser.class.getName();

    private static final int AVAILABLE_BYTES = 31;
    private static final int PARCEL_UUID_BYTES = 16;
    private static final int OVERHEAD_BYTES = 2;

    private final BluetoothLeAdvertiser advertiser =
            BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
    private boolean isAdvertising;

    private final AdvertiseSettings settings =
            new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    // 1db per
                    // http://cs/android/packages/modules/Bluetooth/framework/java/android/bluetooth/le/BluetoothLeAdvertiser.java?l=156&rcl=53aad85c594243e9d8e2778ce582f5c7382ad19c
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();

    private final AdvertiseCallback advertisingCallback =
            new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    super.onStartSuccess(settingsInEffect);
                    Log.i(TAG, "Advertising onStartSuccess: " + settingsInEffect);
                }

                @Override
                public void onStartFailure(int errorCode) {
                    super.onStartFailure(errorCode);
                    Log.i(TAG, "Advertising onStartFailure: " + errorCode);
                }
            };

    public BleAdvertiser() {}

    public void startAdvertising(byte[] payload) {
        if (isAdvertising) {
            stopAdvertising();
        }

        int freeBytesForPayload = getFreeBytesForPayload();
        Preconditions.checkArgument(
                payload.length <= freeBytesForPayload,
                "provided payload is too large! Limit is %s bytes, provided=%s",
                freeBytesForPayload,
                payload.length);

        advertiser.startAdvertising(
                settings,
                new AdvertiseData.Builder()
                        // UUID (16 bytes) + service data overhead (2 bytes) + service data +
                        // device name
                        // overhead (2 bytes) = 20 bytes + service data bytes
                        // This leaves 10 bytes or fewer, depending on the service data, for the
                        // device name
                        // since we only have 31 bytes available in AdvertiseData. To prevent BLE
                        // advertising
                        // from failing, don't set the device name.
                        .setIncludeDeviceName(false)
                        .addServiceData(Const.PARCEL_UUID, payload)
                        .build(),
                advertisingCallback);

        Log.i(TAG, "startAdvertising: %s" + Arrays.toString(payload));

        isAdvertising = true;
    }

    public void stopAdvertising() {
        Log.i(TAG, "stopAdvertising");
        advertiser.stopAdvertising(advertisingCallback);
        isAdvertising = false;
    }

    public boolean isAdvertising() {
        return isAdvertising;
    }

    /** Returns the number of bytes available for a payload */
    private static int getFreeBytesForPayload() {
        int bytesForServiceData = OVERHEAD_BYTES + 1; // + 1 for txOffset
        return AVAILABLE_BYTES - PARCEL_UUID_BYTES - bytesForServiceData;
    }
}
