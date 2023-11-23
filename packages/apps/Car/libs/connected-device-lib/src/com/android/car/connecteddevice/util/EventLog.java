/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.connecteddevice.util;

import static com.android.car.connecteddevice.util.SafeLog.logi;

import com.android.car.connecteddevice.ConnectedDeviceManager;

/** Logging class for collecting metrics. */
public class EventLog {

    private static final String TAG = "ConnectedDeviceEvent";

    private EventLog() { }

    /** Mark in log that the service has started. */
    public static void onServiceStarted() {
        logi(TAG, "SERVICE_STARTED");
    }

    /** Mark in log that the {@link ConnectedDeviceManager} has started. */
    public static void onConnectedDeviceManagerStarted() {
        logi(TAG, "CONNECTED_DEVICE_MANAGER_STARTED");
    }

    /** Mark in the log that BLE is on. */
    public static void onBleOn() {
        logi(TAG, "BLE_ON");
    }

    /** Mark in the log that a search for the user's device has started. */
    public static void onStartDeviceSearchStarted() {
        logi(TAG, "SEARCHING_FOR_DEVICE");
    }


    /** Mark in the log that a device connected. */
    public static void onDeviceConnected() {
        logi(TAG, "DEVICE_CONNECTED");
    }

    /** Mark in the log that the device has sent its id. */
    public static void onDeviceIdReceived() {
        logi(TAG, "RECEIVED_DEVICE_ID");
    }

    /** Mark in the log that a secure channel has been established with a device. */
    public static void onSecureChannelEstablished() {
        logi(TAG, "SECURE_CHANNEL_ESTABLISHED");
    }
}
