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

package com.android.car.connecteddevice.ble;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;
import static com.android.car.connecteddevice.util.ScanDataAnalyzer.containsUuidsInOverflow;

import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Communication manager for a car that maintains continuous connections with all devices in the car
 * for the duration of a drive.
 */
public class CarBleCentralManager extends CarBleManager {

    private static final String TAG = "CarBleCentralManager";

    // system/bt/internal_include/bt_target.h#GATT_MAX_PHY_CHANNEL
    private static final int MAX_CONNECTIONS = 7;

    private static final UUID CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int STATUS_FORCED_DISCONNECT = -1;

    private final ScanSettings mScanSettings = new ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build();

    private final CopyOnWriteArraySet<BleDevice> mIgnoredDevices = new CopyOnWriteArraySet<>();

    private final Context mContext;

    private final BleCentralManager mBleCentralManager;

    private final UUID mServiceUuid;

    private final UUID mWriteCharacteristicUuid;

    private final UUID mReadCharacteristicUuid;

    private final BigInteger mParsedBgServiceBitMask;

    /**
     * Create a new manager.
     *
     * @param context The caller's [Context].
     * @param bleCentralManager [BleCentralManager] for establishing connections.
     * @param connectedDeviceStorage Shared [ConnectedDeviceStorage] for companion features.
     * @param serviceUuid [UUID] of peripheral's service.
     * @param bgServiceMask iOS overflow bit mask for service UUID.
     * @param writeCharacteristicUuid [UUID] of characteristic the car will write to.
     * @param readCharacteristicUuid [UUID] of characteristic the device will write to.
     */
    public CarBleCentralManager(
            @NonNull Context context,
            @NonNull BleCentralManager bleCentralManager,
            @NonNull ConnectedDeviceStorage connectedDeviceStorage,
            @NonNull UUID serviceUuid,
            @NonNull String bgServiceMask,
            @NonNull UUID writeCharacteristicUuid,
            @NonNull UUID readCharacteristicUuid) {
        super(connectedDeviceStorage);
        mContext = context;
        mBleCentralManager = bleCentralManager;
        mServiceUuid = serviceUuid;
        mWriteCharacteristicUuid = writeCharacteristicUuid;
        mReadCharacteristicUuid = readCharacteristicUuid;
        mParsedBgServiceBitMask = new BigInteger(bgServiceMask, 16);
    }

    @Override
    public void start() {
        super.start();
        mBleCentralManager.startScanning(/* filters= */ null, mScanSettings, mScanCallback);
    }

    @Override
    public void stop() {
        super.stop();
        mBleCentralManager.stopScanning();
    }

    @Override
    public void disconnectDevice(String deviceId) {
        logd(TAG, "Request to disconnect from device " + deviceId + ".");
        BleDevice device = getConnectedDevice(deviceId);
        if (device == null) {
            return;
        }

        deviceDisconnected(device, STATUS_FORCED_DISCONNECT);
    }

    private void ignoreDevice(@NonNull BleDevice device) {
        mIgnoredDevices.add(device);
    }

    private boolean isDeviceIgnored(@NonNull BluetoothDevice device) {
        for (BleDevice bleDevice : mIgnoredDevices) {
            if (device.equals(bleDevice.mDevice)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldAttemptConnection(@NonNull ScanResult result) {
        // Ignore any results that are not connectable.
        if (!result.isConnectable()) {
            return false;
        }

        // Do not attempt to connect if we have already hit our max. This should rarely happen
        // and is protecting against a race condition of scanning stopped and new results coming in.
        if (getConnectedDevicesCount() >= MAX_CONNECTIONS) {
            return false;
        }

        BluetoothDevice device = result.getDevice();

        // Do not connect if device has already been ignored.
        if (isDeviceIgnored(device)) {
            return false;
        }

        // Check if already attempting to connect to this device.
        if (getConnectedDevice(device) != null) {
            return false;
        }


        // Ignore any device without a scan record.
        ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord == null) {
            return false;
        }

        // Connect to any device that is advertising our service UUID.
        List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
        if (serviceUuids != null) {
            for (ParcelUuid serviceUuid : serviceUuids) {
                if (serviceUuid.getUuid().equals(mServiceUuid)) {
                    return true;
                }
            }
        }
        if (containsUuidsInOverflow(scanRecord.getBytes(), mParsedBgServiceBitMask)) {
            return true;
        }

        // Can safely ignore devices advertising unrecognized service uuids.
        if (serviceUuids != null && !serviceUuids.isEmpty()) {
            return false;
        }

        // TODO(b/139066293): Current implementation quickly exhausts connections resulting in
        // greatly reduced performance for connecting to devices we know we want to connect to.
        // Return true once fixed.
        return false;
    }

    private void startDeviceConnection(@NonNull BluetoothDevice device) {
        BluetoothGatt gatt = device.connectGatt(mContext, /* autoConnect= */ false,
                mConnectionCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            return;
        }

        BleDevice bleDevice = new BleDevice(device, gatt);
        bleDevice.mState = BleDeviceState.CONNECTING;
        addConnectedDevice(bleDevice);

        // Stop scanning if we have reached the maximum number of connections.
        if (getConnectedDevicesCount() >= MAX_CONNECTIONS) {
            mBleCentralManager.stopScanning();
        }
    }

    private void deviceConnected(@NonNull BleDevice device) {
        if (device.mGatt == null) {
            loge(TAG, "Device connected with null gatt. Disconnecting.");
            deviceDisconnected(device, BluetoothProfile.STATE_DISCONNECTED);
            return;
        }
        device.mState = BleDeviceState.PENDING_VERIFICATION;
        device.mGatt.discoverServices();
        logd(TAG, "New device connected: " + device.mGatt.getDevice().getAddress()
                + ". Active connections: " + getConnectedDevicesCount() + ".");
    }

    private void deviceDisconnected(@NonNull BleDevice device, int status) {
        removeConnectedDevice(device);
        if (device.mGatt != null) {
            device.mGatt.close();
        }
        if (device.mDeviceId != null) {
            mCallbacks.invoke(callback -> callback.onDeviceDisconnected(device.mDeviceId));
        }
        logd(TAG, "Device with id " + device.mDeviceId + " disconnected with state " + status
                + ". Remaining active connections: " + getConnectedDevicesCount() + ".");
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            if (shouldAttemptConnection(result)) {
                startDeviceConnection(result.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            loge(TAG, "BLE scanning failed with error code: " + errorCode);
        }
    };

    private final BluetoothGattCallback mConnectionCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (gatt == null) {
                logw(TAG, "Null gatt passed to onConnectionStateChange. Ignoring.");
                return;
            }

            BleDevice connectedDevice = getConnectedDevice(gatt);
            if (connectedDevice == null) {
                return;
            }

            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    deviceConnected(connectedDevice);
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    deviceDisconnected(connectedDevice, status);
                    break;
                default:
                    logd(TAG, "Connection state changed. New state: " + newState + " status: "
                            + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (gatt == null) {
                logw(TAG, "Null gatt passed to onServicesDiscovered. Ignoring.");
                return;
            }

            BleDevice connectedDevice = getConnectedDevice(gatt);
            if (connectedDevice == null) {
                return;
            }
            BluetoothGattService service = gatt.getService(mServiceUuid);
            if (service == null) {
                ignoreDevice(connectedDevice);
                gatt.disconnect();
                return;
            }

            connectedDevice.mState = BleDeviceState.CONNECTED;
            BluetoothGattCharacteristic writeCharacteristic =
                    service.getCharacteristic(mWriteCharacteristicUuid);
            BluetoothGattCharacteristic readCharacteristic =
                    service.getCharacteristic(mReadCharacteristicUuid);
            if (writeCharacteristic == null || readCharacteristic == null) {
                logw(TAG, "Unable to find expected characteristics on peripheral.");
                gatt.disconnect();
                return;
            }

            // Turn on notifications for read characteristic.
            BluetoothGattDescriptor descriptor =
                    readCharacteristic.getDescriptor(CHARACTERISTIC_CONFIG);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(descriptor)) {
                loge(TAG, "Write descriptor to read characteristic failed.");
                gatt.disconnect();
                return;
            }

            if (!gatt.setCharacteristicNotification(readCharacteristic, /* enable= */ true)) {
                loge(TAG, "Set notifications to read characteristic failed.");
                gatt.disconnect();
                return;
            }

            logd(TAG, "Service and characteristics successfully discovered.");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            if (gatt == null) {
                logw(TAG, "Null gatt passed to onDescriptorWrite. Ignoring.");
                return;
            }
            // TODO(b/141312136): Create SecureBleChannel and assign to connectedDevice.
        }
    };
}
