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

import static com.android.car.connecteddevice.ConnectedDeviceManager.DEVICE_ERROR_INVALID_HANDSHAKE;
import static com.android.car.connecteddevice.ConnectedDeviceManager.DEVICE_ERROR_UNEXPECTED_DISCONNECTION;
import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import com.android.car.connecteddevice.AssociationCallback;
import com.android.car.connecteddevice.model.AssociatedDevice;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;
import com.android.car.connecteddevice.util.EventLog;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Communication manager that allows for targeted connections to a specific device in the car.
 */
public class CarBlePeripheralManager extends CarBleManager {

    private static final String TAG = "CarBlePeripheralManager";

    // Attribute protocol bytes attached to message. Available write size is MTU size minus att
    // bytes.
    private static final int ATT_PROTOCOL_BYTES = 3;

    // Arbitrary delay time for a retry of association advertising if bluetooth adapter name change
    // fails.
    private static final long ASSOCIATE_ADVERTISING_DELAY_MS = 10L;

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int SALT_BYTES = 8;

    private static final int TOTAL_AD_DATA_BYTES = 16;

    private static final int TRUNCATED_BYTES = 3;

    private final BluetoothGattDescriptor mDescriptor =
            new BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG,
                    BluetoothGattDescriptor.PERMISSION_READ
                            | BluetoothGattDescriptor.PERMISSION_WRITE);

    private final ScheduledExecutorService mScheduler =
            Executors.newSingleThreadScheduledExecutor();

    private final BlePeripheralManager mBlePeripheralManager;

    private final UUID mAssociationServiceUuid;

    private final UUID mReconnectServiceUuid;

    private final UUID mReconnectDataUuid;

    private final BluetoothGattCharacteristic mWriteCharacteristic;

    private final BluetoothGattCharacteristic mReadCharacteristic;

    private final Handler mTimeoutHandler;

    private final Duration mMaxReconnectAdvertisementDuration;

    private final int mDefaultMtuSize;

    private String mOriginalBluetoothName;

    private String mClientDeviceName;

    private String mClientDeviceAddress;

    private String mReconnectDeviceId;

    private byte[] mReconnectChallenge;

    private AssociationCallback mAssociationCallback;

    private AdvertiseCallback mAdvertiseCallback;

    /**
     * Initialize a new instance of manager.
     *
     * @param blePeripheralManager    {@link BlePeripheralManager} for establishing connection.
     * @param connectedDeviceStorage  Shared {@link ConnectedDeviceStorage} for companion features.
     * @param associationServiceUuid  {@link UUID} of association service.
     * @param reconnectServiceUuid    {@link UUID} of reconnect service.
     * @param reconnectDataUuid       {@link UUID} key of reconnect advertisement data.
     * @param writeCharacteristicUuid {@link UUID} of characteristic the car will write to.
     * @param readCharacteristicUuid  {@link UUID} of characteristic the device will write to.
     * @param maxReconnectAdvertisementDuration Maximum duration to advertise for reconnect before
     *                                          restarting.
     * @param defaultMtuSize          Default MTU size for new channels.
     */
    public CarBlePeripheralManager(@NonNull BlePeripheralManager blePeripheralManager,
            @NonNull ConnectedDeviceStorage connectedDeviceStorage,
            @NonNull UUID associationServiceUuid,
            @NonNull UUID reconnectServiceUuid,
            @NonNull UUID reconnectDataUuid,
            @NonNull UUID writeCharacteristicUuid,
            @NonNull UUID readCharacteristicUuid,
            @NonNull Duration maxReconnectAdvertisementDuration,
            int defaultMtuSize) {
        super(connectedDeviceStorage);
        mBlePeripheralManager = blePeripheralManager;
        mAssociationServiceUuid = associationServiceUuid;
        mReconnectServiceUuid = reconnectServiceUuid;
        mReconnectDataUuid = reconnectDataUuid;
        mDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        mWriteCharacteristic = new BluetoothGattCharacteristic(writeCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_READ);
        mReadCharacteristic = new BluetoothGattCharacteristic(readCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        mReadCharacteristic.addDescriptor(mDescriptor);
        mTimeoutHandler = new Handler(Looper.getMainLooper());
        mMaxReconnectAdvertisementDuration = maxReconnectAdvertisementDuration;
        mDefaultMtuSize = defaultMtuSize;
    }

    @Override
    public void start() {
        super.start();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        String originalBluetoothName = mStorage.getStoredBluetoothName();
        if (originalBluetoothName == null) {
            return;
        }
        if (originalBluetoothName.equals(adapter.getName())) {
            mStorage.removeStoredBluetoothName();
            return;
        }

        logw(TAG, "Discovered mismatch in bluetooth adapter name. Resetting back to "
                + originalBluetoothName + ".");
        adapter.setName(originalBluetoothName);
        mScheduler.schedule(
                () -> verifyBluetoothNameRestored(originalBluetoothName),
                ASSOCIATE_ADVERTISING_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        super.stop();
        reset();
    }

    @Override
    public void disconnectDevice(@NonNull String deviceId) {
        BleDevice connectedDevice = getConnectedDevice();
        if (connectedDevice == null || !deviceId.equals(connectedDevice.mDeviceId)) {
            return;
        }
        reset();
    }

    private void reset() {
        resetBluetoothAdapterName();
        mClientDeviceAddress = null;
        mClientDeviceName = null;
        mAssociationCallback = null;
        mBlePeripheralManager.cleanup();
        mConnectedDevices.clear();
        mReconnectDeviceId = null;
        mReconnectChallenge = null;
    }

    /** Attempt to connect to device with provided id. */
    public void connectToDevice(@NonNull UUID deviceId) {
        for (BleDevice device : mConnectedDevices) {
            if (UUID.fromString(device.mDeviceId).equals(deviceId)) {
                logd(TAG, "Already connected to device " + deviceId + ".");
                // Already connected to this device. Ignore requests to connect again.
                return;
            }
        }

        // Clear any previous session before starting a new one.
        reset();
        mReconnectDeviceId = deviceId.toString();
        mAdvertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                mTimeoutHandler.postDelayed(mTimeoutRunnable,
                        mMaxReconnectAdvertisementDuration.toMillis());
                logd(TAG, "Successfully started advertising for device " + deviceId + ".");
            }
        };
        mBlePeripheralManager.unregisterCallback(mAssociationPeripheralCallback);
        mBlePeripheralManager.registerCallback(mReconnectPeripheralCallback);
        mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
        byte[] advertiseData = createReconnectData(mReconnectDeviceId);
        if (advertiseData == null) {
            loge(TAG, "Unable to create advertisement data. Aborting reconnect.");
            return;
        }
        startAdvertising(mReconnectServiceUuid, mAdvertiseCallback, /* includeDeviceName= */ false,
                advertiseData, mReconnectDataUuid);
    }

    /**
     * Create data for reconnection advertisement.
     *
     * <p></p><p>Process:</p>
     * <ol>
     * <li>Generate random {@value SALT_BYTES} byte salt and zero-pad to
     * {@value TOTAL_AD_DATA_BYTES} bytes.
     * <li>Hash with stored challenge secret and truncate to {@value TRUNCATED_BYTES} bytes.
     * <li>Concatenate hashed {@value TRUNCATED_BYTES} bytes with salt and return.
     * </ol>
     */
    @Nullable
    private byte[] createReconnectData(String deviceId) {
        byte[] salt = ByteUtils.randomBytes(SALT_BYTES);
        byte[] zeroPadded = ByteUtils.concatByteArrays(salt,
                new byte[TOTAL_AD_DATA_BYTES - SALT_BYTES]);
        mReconnectChallenge = mStorage.hashWithChallengeSecret(deviceId, zeroPadded);
        if (mReconnectChallenge == null) {
            return null;
        }
        return ByteUtils.concatByteArrays(Arrays.copyOf(mReconnectChallenge, TRUNCATED_BYTES),
                salt);

    }

    @Nullable
    private BleDevice getConnectedDevice() {
        if (mConnectedDevices.isEmpty()) {
            return null;
        }
        return mConnectedDevices.iterator().next();
    }

    /** Start the association with a new device */
    public void startAssociation(@NonNull String nameForAssociation,
            @NonNull AssociationCallback callback) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            loge(TAG, "Bluetooth is unavailable on this device. Unable to start associating.");
            return;
        }

        reset();
        mAssociationCallback = callback;
        if (mOriginalBluetoothName == null) {
            mOriginalBluetoothName = adapter.getName();
            mStorage.storeBluetoothName(mOriginalBluetoothName);
        }
        adapter.setName(nameForAssociation);
        logd(TAG, "Changing bluetooth adapter name from " + mOriginalBluetoothName + " to "
                + nameForAssociation + ".");
        mBlePeripheralManager.unregisterCallback(mReconnectPeripheralCallback);
        mBlePeripheralManager.registerCallback(mAssociationPeripheralCallback);
        mAdvertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                callback.onAssociationStartSuccess(nameForAssociation);
                logd(TAG, "Successfully started advertising for association.");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                callback.onAssociationStartFailure();
                logd(TAG, "Failed to start advertising for association. Error code: " + errorCode);
            }
        };
        attemptAssociationAdvertising(nameForAssociation, callback);
    }

    /** Stop the association with any device. */
    public void stopAssociation(@NonNull AssociationCallback callback) {
        if (!isAssociating() || callback != mAssociationCallback) {
            return;
        }
        reset();
    }

    private void attemptAssociationAdvertising(@NonNull String adapterName,
            @NonNull AssociationCallback callback) {
        if (mOriginalBluetoothName != null
                && adapterName.equals(BluetoothAdapter.getDefaultAdapter().getName())) {
            startAdvertising(mAssociationServiceUuid, mAdvertiseCallback,
                    /* includeDeviceName= */ true, /* serviceData= */ null,
                    /* serviceDataUuid= */ null);
            return;
        }

        ScheduledFuture future = mScheduler.schedule(
                () -> attemptAssociationAdvertising(adapterName, callback),
                ASSOCIATE_ADVERTISING_DELAY_MS, TimeUnit.MILLISECONDS);
        if (future.isCancelled()) {
            // Association failed to start.
            callback.onAssociationStartFailure();
            return;
        }
        logd(TAG, "Adapter name change has not taken affect prior to advertising attempt. Trying "
                + "again in " + ASSOCIATE_ADVERTISING_DELAY_MS + "  milliseconds.");
    }

    private void startAdvertising(@NonNull UUID serviceUuid, @NonNull AdvertiseCallback callback,
            boolean includeDeviceName, @Nullable byte[] serviceData,
            @Nullable UUID serviceDataUuid) {
        BluetoothGattService gattService = new BluetoothGattService(serviceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        gattService.addCharacteristic(mWriteCharacteristic);
        gattService.addCharacteristic(mReadCharacteristic);

        AdvertiseData.Builder builder = new AdvertiseData.Builder()
                .setIncludeDeviceName(includeDeviceName);
        ParcelUuid uuid = new ParcelUuid(serviceUuid);
        builder.addServiceUuid(uuid);
        if (serviceData != null) {
            ParcelUuid dataUuid = uuid;
            if (serviceDataUuid != null) {
                dataUuid = new ParcelUuid(serviceDataUuid);
            }
            builder.addServiceData(dataUuid, serviceData);
        }

        mBlePeripheralManager.startAdvertising(gattService, builder.build(), callback);
    }

    /** Notify that the user has accepted a pairing code or other out-of-band confirmation. */
    public void notifyOutOfBandAccepted() {
        if (getConnectedDevice() == null) {
            disconnectWithError("Null connected device found when out-of-band confirmation "
                    + "received.");
            return;
        }

        AssociationSecureChannel secureChannel =
                (AssociationSecureChannel) getConnectedDevice().mSecureChannel;
        if (secureChannel == null) {
            disconnectWithError("Null SecureBleChannel found for the current connected device "
                    + "when out-of-band confirmation received.");
            return;
        }

        secureChannel.notifyOutOfBandAccepted();
    }

    @VisibleForTesting
    @Nullable
    SecureBleChannel getConnectedDeviceChannel() {
        BleDevice connectedDevice = getConnectedDevice();
        if (connectedDevice == null) {
            return null;
        }

        return connectedDevice.mSecureChannel;
    }

    private void setDeviceId(@NonNull String deviceId) {
        logd(TAG, "Setting device id: " + deviceId);
        BleDevice connectedDevice = getConnectedDevice();
        if (connectedDevice == null) {
            disconnectWithError("Null connected device found when device id received.");
            return;
        }

        connectedDevice.mDeviceId = deviceId;
        mCallbacks.invoke(callback -> callback.onDeviceConnected(deviceId));
    }

    private void disconnectWithError(@NonNull String errorMessage) {
        loge(TAG, errorMessage);
        if (isAssociating()) {
            mAssociationCallback.onAssociationError(DEVICE_ERROR_INVALID_HANDSHAKE);
        }
        reset();
    }

    private void resetBluetoothAdapterName() {
        if (mOriginalBluetoothName == null) {
            return;
        }
        logd(TAG, "Changing bluetooth adapter name back to " + mOriginalBluetoothName + ".");
        BluetoothAdapter.getDefaultAdapter().setName(mOriginalBluetoothName);
        mOriginalBluetoothName = null;
    }

    private void verifyBluetoothNameRestored(@NonNull String expectedName) {
        String currentName = BluetoothAdapter.getDefaultAdapter().getName();
        if (expectedName.equals(currentName)) {
            logd(TAG, "Bluetooth adapter name restoration completed successfully. Removing stored "
                    + "adapter name.");
            mStorage.removeStoredBluetoothName();
            return;
        }
        logd(TAG, "Bluetooth adapter name restoration has not taken affect yet. Checking again in "
                + ASSOCIATE_ADVERTISING_DELAY_MS + " milliseconds.");
        mScheduler.schedule(
                () -> verifyBluetoothNameRestored(expectedName),
                ASSOCIATE_ADVERTISING_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void addConnectedDevice(BluetoothDevice device, boolean isReconnect) {
        EventLog.onDeviceConnected();
        mBlePeripheralManager.stopAdvertising(mAdvertiseCallback);
        mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
        mClientDeviceAddress = device.getAddress();
        mClientDeviceName = device.getName();
        if (mClientDeviceName == null) {
            logd(TAG, "Device connected, but name is null; issuing request to retrieve device "
                    + "name.");
            mBlePeripheralManager.retrieveDeviceName(device);
        }

        BleDeviceMessageStream secureStream = new BleDeviceMessageStream(mBlePeripheralManager,
                device, mWriteCharacteristic, mReadCharacteristic,
                mDefaultMtuSize - ATT_PROTOCOL_BYTES);
        secureStream.setMessageReceivedErrorListener(
                exception -> {
                    disconnectWithError("Error occurred in stream: " + exception.getMessage());
                });
        SecureBleChannel secureChannel;
        if (isReconnect) {
            secureChannel = new ReconnectSecureChannel(secureStream, mStorage, mReconnectDeviceId,
                    mReconnectChallenge);
        } else {
            secureChannel = new AssociationSecureChannel(secureStream, mStorage);
        }
        secureChannel.registerCallback(mSecureChannelCallback);
        BleDevice bleDevice = new BleDevice(device, /* gatt= */ null);
        bleDevice.mSecureChannel = secureChannel;
        addConnectedDevice(bleDevice);
        if (isReconnect) {
            setDeviceId(mReconnectDeviceId);
            mReconnectDeviceId = null;
            mReconnectChallenge = null;
        }
    }

    private void setMtuSize(int mtuSize) {
        BleDevice connectedDevice = getConnectedDevice();
        if (connectedDevice != null
                && connectedDevice.mSecureChannel != null
                && connectedDevice.mSecureChannel.getStream() != null) {
            connectedDevice.mSecureChannel.getStream()
                    .setMaxWriteSize(mtuSize - ATT_PROTOCOL_BYTES);
        }
    }

    private boolean isAssociating() {
        return mAssociationCallback != null;
    }

    private final BlePeripheralManager.Callback mReconnectPeripheralCallback =
            new BlePeripheralManager.Callback() {

                @Override
                public void onDeviceNameRetrieved(String deviceName) {
                    // Ignored.
                }

                @Override
                public void onMtuSizeChanged(int size) {
                    setMtuSize(size);
                }

                @Override
                public void onRemoteDeviceConnected(BluetoothDevice device) {
                    addConnectedDevice(device, /* isReconnect= */ true);
                }

                @Override
                public void onRemoteDeviceDisconnected(BluetoothDevice device) {
                    String deviceId = mReconnectDeviceId;
                    BleDevice connectedDevice = getConnectedDevice(device);
                    // Reset before invoking callbacks to avoid a race condition with reconnect
                    // logic.
                    reset();
                    if (connectedDevice != null) {
                        deviceId = connectedDevice.mDeviceId;
                    }
                    final String finalDeviceId = deviceId;
                    if (finalDeviceId != null) {
                        logd(TAG, "Connected device " + finalDeviceId + " disconnected.");
                        mCallbacks.invoke(callback -> callback.onDeviceDisconnected(finalDeviceId));
                    }
                }
            };

    private final BlePeripheralManager.Callback mAssociationPeripheralCallback =
            new BlePeripheralManager.Callback() {
                @Override
                public void onDeviceNameRetrieved(String deviceName) {
                    if (deviceName == null) {
                        return;
                    }
                    mClientDeviceName = deviceName;
                    BleDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mDeviceId == null) {
                        return;
                    }
                    mStorage.updateAssociatedDeviceName(connectedDevice.mDeviceId, deviceName);
                }

                @Override
                public void onMtuSizeChanged(int size) {
                    setMtuSize(size);
                }

                @Override
                public void onRemoteDeviceConnected(BluetoothDevice device) {
                    resetBluetoothAdapterName();
                    addConnectedDevice(device, /* isReconnect= */ false);
                    BleDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mSecureChannel == null) {
                        return;
                    }
                    ((AssociationSecureChannel) connectedDevice.mSecureChannel)
                            .setShowVerificationCodeListener(
                                    code -> {
                                        if (!isAssociating()) {
                                            loge(TAG, "No valid callback for association.");
                                            return;
                                        }
                                        mAssociationCallback.onVerificationCodeAvailable(code);
                                    });
                }

                @Override
                public void onRemoteDeviceDisconnected(BluetoothDevice device) {
                    BleDevice connectedDevice = getConnectedDevice(device);
                    if (isAssociating()) {
                        mAssociationCallback.onAssociationError(
                                DEVICE_ERROR_UNEXPECTED_DISCONNECTION);
                    }
                    // Reset before invoking callbacks to avoid a race condition with reconnect
                    // logic.
                    reset();
                    if (connectedDevice != null && connectedDevice.mDeviceId != null) {
                        mCallbacks.invoke(callback -> callback.onDeviceDisconnected(
                                connectedDevice.mDeviceId));
                    }
                }
            };

    private final SecureBleChannel.Callback mSecureChannelCallback =
            new SecureBleChannel.Callback() {
                @Override
                public void onSecureChannelEstablished() {
                    BleDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mDeviceId == null) {
                        disconnectWithError("Null device id found when secure channel "
                                + "established.");
                        return;
                    }
                    String deviceId = connectedDevice.mDeviceId;
                    if (mClientDeviceAddress == null) {
                        disconnectWithError("Null device address found when secure channel "
                                + "established.");
                        return;
                    }
                    if (isAssociating()) {
                        logd(TAG, "Secure channel established for un-associated device. Saving "
                                + "association of that device for current user.");
                        mStorage.addAssociatedDeviceForActiveUser(
                                new AssociatedDevice(deviceId, mClientDeviceAddress,
                                        mClientDeviceName, /* isConnectionEnabled= */ true));
                        if (mAssociationCallback != null) {
                            mAssociationCallback.onAssociationCompleted(deviceId);
                            mAssociationCallback = null;
                        }
                    }
                    mCallbacks.invoke(callback -> callback.onSecureChannelEstablished(deviceId));
                }

                @Override
                public void onEstablishSecureChannelFailure(int error) {
                    BleDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mDeviceId == null) {
                        disconnectWithError("Null device id found when secure channel failed to "
                                + "establish.");
                        return;
                    }
                    String deviceId = connectedDevice.mDeviceId;
                    mCallbacks.invoke(callback -> callback.onSecureChannelError(deviceId));

                    if (isAssociating()) {
                        mAssociationCallback.onAssociationError(error);
                    }

                    disconnectWithError("Error while establishing secure connection.");
                }

                @Override
                public void onMessageReceived(DeviceMessage deviceMessage) {
                    BleDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mDeviceId == null) {
                        disconnectWithError("Null device id found when message received.");
                        return;
                    }

                    logd(TAG, "Received new message from " + connectedDevice.mDeviceId
                            + " with " + deviceMessage.getMessage().length + " bytes in its "
                            + "payload. Notifying " + mCallbacks.size() + " callbacks.");
                    mCallbacks.invoke(
                            callback -> callback.onMessageReceived(connectedDevice.mDeviceId,
                                    deviceMessage));
                }

                @Override
                public void onMessageReceivedError(Exception exception) {
                    // TODO(b/143879960) Extend the message error from here to continue up the
                    // chain.
                    disconnectWithError("Error while receiving message.");
                }

                @Override
                public void onDeviceIdReceived(String deviceId) {
                    setDeviceId(deviceId);
                }
            };

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            logd(TAG, "Timeout period expired without a connection. Restarting advertisement.");
            mBlePeripheralManager.stopAdvertising(mAdvertiseCallback);
            connectToDevice(UUID.fromString(mReconnectDeviceId));
        }
    };
}
