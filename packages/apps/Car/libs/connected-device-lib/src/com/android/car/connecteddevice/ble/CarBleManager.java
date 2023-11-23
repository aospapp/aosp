/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.connecteddevice.ble;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;

import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ThreadSafeCallbacks;

import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

/**
 * Generic BLE manager for a car that keeps track of connected devices and their associated
 * callbacks.
 */
public abstract class CarBleManager {

    private static final String TAG = "CarBleManager";

    final ConnectedDeviceStorage mStorage;

    final CopyOnWriteArraySet<BleDevice> mConnectedDevices = new CopyOnWriteArraySet<>();

    final ThreadSafeCallbacks<Callback> mCallbacks = new ThreadSafeCallbacks<>();

    protected CarBleManager(@NonNull ConnectedDeviceStorage connectedDeviceStorage) {
        mStorage = connectedDeviceStorage;
    }

    /**
     * Initialize and start the manager.
     */
    public void start() {
    }

    /**
     * Stop the manager and clean up.
     */
    public void stop() {
        for (BleDevice device : mConnectedDevices) {
            if (device.mGatt != null) {
                device.mGatt.close();
            }
        }
        mConnectedDevices.clear();
    }

    /**
     * Register a {@link Callback} to be notified on the {@link Executor}.
     */
    public void registerCallback(@NonNull Callback callback, @NonNull Executor executor) {
        mCallbacks.add(callback, executor);
    }

    /**
     * Unregister a callback.
     *
     * @param callback The {@link Callback} to unregister.
     */
    public void unregisterCallback(@NonNull Callback callback) {
        mCallbacks.remove(callback);
    }

    /**
     * Send a message to a connected device.
     *
     * @param deviceId Id of connected device.
     * @param message  {@link DeviceMessage} to send.
     */
    public void sendMessage(@NonNull String deviceId, @NonNull DeviceMessage message) {
        BleDevice device = getConnectedDevice(deviceId);
        if (device == null) {
            logw(TAG, "Attempted to send message to unknown device $deviceId. Ignored.");
            return;
        }

        sendMessage(device, message);
    }

    /**
     * Send a message to a connected device.
     *
     * @param device  The connected {@link BleDevice}.
     * @param message {@link DeviceMessage} to send.
     */
    public void sendMessage(@NonNull BleDevice device, @NonNull DeviceMessage message) {
        String deviceId = device.mDeviceId;
        if (deviceId == null) {
            deviceId = "Unidentified device";
        }

        logd(TAG, "Writing " + message.getMessage().length + " bytes to " + deviceId + ".");
        device.mSecureChannel.sendClientMessage(message);
    }

    /**
     * Get the {@link BleDevice} with matching {@link BluetoothGatt} if available. Returns
     * {@code null} if no matches are found.
     */
    @Nullable
    BleDevice getConnectedDevice(@NonNull BluetoothGatt gatt) {
        for (BleDevice device : mConnectedDevices) {
            if (device.mGatt == gatt) {
                return device;
            }
        }

        return null;
    }

    /**
     * Get the {@link BleDevice} with matching {@link BluetoothDevice} if available. Returns
     * {@code null} if no matches are found.
     */
    @Nullable
    BleDevice getConnectedDevice(@NonNull BluetoothDevice device) {
        for (BleDevice connectedDevice : mConnectedDevices) {
            if (device.equals(connectedDevice.mDevice)) {
                return connectedDevice;
            }
        }

        return null;
    }

    /**
     * Get the {@link BleDevice} with matching device id if available. Returns {@code null} if
     * no matches are found.
     */
    @Nullable
    BleDevice getConnectedDevice(@NonNull String deviceId) {
        for (BleDevice device : mConnectedDevices) {
            if (deviceId.equals(device.mDeviceId)) {
                return device;
            }
        }

        return null;
    }

    /** Add the {@link BleDevice} that has connected. */
    void addConnectedDevice(@NonNull BleDevice device) {
        mConnectedDevices.add(device);
    }

    /** Return the number of devices currently connected. */
    int getConnectedDevicesCount() {
        return mConnectedDevices.size();
    }

    /** Remove [@link BleDevice} that has been disconnected. */
    void removeConnectedDevice(@NonNull BleDevice device) {
        mConnectedDevices.remove(device);
    }

    /** Disconnect the provided device from this manager. */
    public abstract void disconnectDevice(@NonNull String deviceId);

    /** State for a connected device. */
    enum BleDeviceState {
        CONNECTING,
        PENDING_VERIFICATION,
        CONNECTED,
        UNKNOWN
    }

    /**
     * Container class to hold information about a connected device.
     */
    static class BleDevice {

        BluetoothDevice mDevice;
        BluetoothGatt mGatt;
        BleDeviceState mState;
        String mDeviceId;
        SecureBleChannel mSecureChannel;

        BleDevice(@NonNull BluetoothDevice device, @Nullable BluetoothGatt gatt) {
            mDevice = device;
            mGatt = gatt;
            mState = BleDeviceState.UNKNOWN;
        }
    }

    /**
     * Callback for triggered events from {@link CarBleManager}.
     */
    public interface Callback {
        /**
         * Triggered when device is connected and device id retrieved. Device is now ready to
         * receive messages.
         *
         * @param deviceId Id of device that has connected.
         */
        void onDeviceConnected(@NonNull String deviceId);

        /**
         * Triggered when device is disconnected.
         *
         * @param deviceId Id of device that has disconnected.
         */
        void onDeviceDisconnected(@NonNull String deviceId);

        /**
         * Triggered when device has established encryption for secure communication.
         *
         * @param deviceId Id of device that has established encryption.
         */
        void onSecureChannelEstablished(@NonNull String deviceId);

        /**
         * Triggered when a new message is received.
         *
         * @param deviceId Id of the device that sent the message.
         * @param message  {@link DeviceMessage} received.
         */
        void onMessageReceived(@NonNull String deviceId, @NonNull DeviceMessage message);

        /**
         * Triggered when an error when establishing the secure channel.
         *
         * @param deviceId Id of the device that experienced the error.
         */
        void onSecureChannelError(@NonNull String deviceId);
    }
}
