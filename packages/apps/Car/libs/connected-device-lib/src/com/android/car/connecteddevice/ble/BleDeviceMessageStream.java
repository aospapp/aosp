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

import static com.android.car.connecteddevice.BleStreamProtos.BleOperationProto.OperationType;
import static com.android.car.connecteddevice.BleStreamProtos.BlePacketProto.BlePacket;
import static com.android.car.connecteddevice.BleStreamProtos.VersionExchangeProto.BleVersionExchange;
import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;

import com.android.car.connecteddevice.BleStreamProtos.BleDeviceMessageProto.BleDeviceMessage;
import com.android.car.connecteddevice.util.ByteUtils;
import com.android.car.protobuf.ByteString;
import com.android.car.protobuf.InvalidProtocolBufferException;
import com.android.internal.annotations.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** BLE message stream to a device. */
class BleDeviceMessageStream {

    private static final String TAG = "BleDeviceMessageStream";

    // Only version 2 of the messaging and version 2 of the security supported.
    private static final int MESSAGING_VERSION = 2;
    private static final int SECURITY_VERSION = 2;

    /*
     * During bandwidth testing, it was discovered that allowing the stream to send as fast as it
     * can blocked outgoing notifications from being received by the connected device. Adding a
     * throttle to the outgoing messages alleviated this block and allowed both sides to
     * send/receive in parallel successfully.
     */
    private static final long THROTTLE_DEFAULT_MS = 10L;
    private static final long THROTTLE_WAIT_MS = 75L;

    private final ArrayDeque<BlePacket> mPacketQueue = new ArrayDeque<>();

    private final Map<Integer, ByteArrayOutputStream> mPendingData = new HashMap<>();

    // messageId -> nextExpectedPacketNumber
    private final Map<Integer, Integer> mPendingPacketNumber = new HashMap<>();

    private final MessageIdGenerator mMessageIdGenerator = new MessageIdGenerator();

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean mIsVersionExchanged = new AtomicBoolean(false);

    private final AtomicBoolean mIsSendingInProgress = new AtomicBoolean(false);

    private final AtomicLong mThrottleDelay = new AtomicLong(THROTTLE_DEFAULT_MS);

    private final BlePeripheralManager mBlePeripheralManager;

    private final BluetoothDevice mDevice;

    private final BluetoothGattCharacteristic mWriteCharacteristic;

    private final BluetoothGattCharacteristic mReadCharacteristic;

    private MessageReceivedListener mMessageReceivedListener;

    private MessageReceivedErrorListener mMessageReceivedErrorListener;

    private int mMaxWriteSize;

    BleDeviceMessageStream(@NonNull BlePeripheralManager blePeripheralManager,
            @NonNull BluetoothDevice device,
            @NonNull BluetoothGattCharacteristic writeCharacteristic,
            @NonNull BluetoothGattCharacteristic readCharacteristic,
            int defaultMaxWriteSize) {
        mBlePeripheralManager = blePeripheralManager;
        mDevice = device;
        mWriteCharacteristic = writeCharacteristic;
        mReadCharacteristic = readCharacteristic;
        mBlePeripheralManager.addOnCharacteristicWriteListener(this::onCharacteristicWrite);
        mBlePeripheralManager.addOnCharacteristicReadListener(this::onCharacteristicRead);
        mMaxWriteSize = defaultMaxWriteSize;
    }

    /**
     * Writes the given message to the write characteristic of this stream with operation type
     * {@code CLIENT_MESSAGE}.
     *
     * This method will handle the chunking of messages based on the max write size.
     *
     * @param deviceMessage The data object contains recipient, isPayloadEncrypted and message.
     */
    void writeMessage(@NonNull DeviceMessage deviceMessage) {
        writeMessage(deviceMessage, OperationType.CLIENT_MESSAGE);
    }

    /**
     * Writes the given message to the write characteristic of this stream.
     *
     * This method will handle the chunking of messages based on the max write size. If it is
     * a handshake message, the message recipient should be {@code null} and it cannot be
     * encrypted.
     *
     * @param deviceMessage The data object contains recipient, isPayloadEncrypted and message.
     * @param operationType The {@link OperationType} of this message.
     */
    void writeMessage(@NonNull DeviceMessage deviceMessage, OperationType operationType) {
        logd(TAG, "Writing message with " + deviceMessage.getMessage().length + " bytes to device: "
                + mDevice.getAddress() + ".");
        BleDeviceMessage.Builder builder = BleDeviceMessage.newBuilder()
                .setOperation(operationType)
                .setIsPayloadEncrypted(deviceMessage.isMessageEncrypted())
                .setPayload(ByteString.copyFrom(deviceMessage.getMessage()));

        UUID recipient = deviceMessage.getRecipient();
        if (recipient != null) {
            builder.setRecipient(ByteString.copyFrom(ByteUtils.uuidToBytes(recipient)));
        }

        BleDeviceMessage bleDeviceMessage = builder.build();
        byte[] rawBytes = bleDeviceMessage.toByteArray();
        List<BlePacket> blePackets;
        try {
            blePackets = BlePacketFactory.makeBlePackets(rawBytes, mMessageIdGenerator.next(),
                    mMaxWriteSize);
        } catch (BlePacketFactoryException e) {
            loge(TAG, "Error while creating message packets.", e);
            return;
        }
        mPacketQueue.addAll(blePackets);
        writeNextMessageInQueue();
    }

    private void writeNextMessageInQueue() {
        mHandler.postDelayed(() -> {
            if (mPacketQueue.isEmpty()) {
                logd(TAG, "No more packets to send.");
                return;
            }
            if (mIsSendingInProgress.get()) {
                logd(TAG, "Unable to send packet at this time.");
                return;
            }

            mIsSendingInProgress.set(true);
            BlePacket packet = mPacketQueue.remove();
            logd(TAG, "Writing packet " + packet.getPacketNumber() + " of "
                    + packet.getTotalPackets() + " for " + packet.getMessageId() + ".");
            mWriteCharacteristic.setValue(packet.toByteArray());
            mBlePeripheralManager.notifyCharacteristicChanged(mDevice, mWriteCharacteristic,
                    /* confirm= */ false);
        }, mThrottleDelay.get());
    }

    private void onCharacteristicRead(@NonNull BluetoothDevice device) {
        if (!mDevice.equals(device)) {
            logw(TAG, "Received a read notification from a device (" + device.getAddress()
                    + ") that is not the expected device (" + mDevice.getAddress() + ") registered "
                    + "to this stream. Ignoring.");
            return;
        }

        logd(TAG, "Releasing lock on characteristic.");
        mIsSendingInProgress.set(false);
        writeNextMessageInQueue();
    }

    private void onCharacteristicWrite(@NonNull BluetoothDevice device,
            @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
        logd(TAG, "Received a message from a device (" + device.getAddress() + ").");
        if (!mDevice.equals(device)) {
            logw(TAG, "Received a message from a device (" + device.getAddress() + ") that is not "
                    + "the expected device (" + mDevice.getAddress() + ") registered to this "
                    + "stream. Ignoring.");
            return;
        }

        if (!characteristic.getUuid().equals(mReadCharacteristic.getUuid())) {
            logw(TAG, "Received a write to a characteristic (" + characteristic.getUuid() + ") that"
                    + " is not the expected UUID (" + mReadCharacteristic.getUuid() + "). "
                    + "Ignoring.");
            return;
        }

        if (!mIsVersionExchanged.get()) {
            processVersionExchange(device, value);
            return;
        }

        BlePacket packet;
        try {
            packet = BlePacket.parseFrom(value);
        } catch (InvalidProtocolBufferException e) {
            loge(TAG, "Can not parse Ble packet from client.", e);
            if (mMessageReceivedErrorListener != null) {
                mMessageReceivedErrorListener.onMessageReceivedError(e);
            }
            return;
        }
        processPacket(packet);
    }

    private void processVersionExchange(@NonNull BluetoothDevice device, @NonNull byte[] value) {
        BleVersionExchange versionExchange;
        try {
            versionExchange = BleVersionExchange.parseFrom(value);
        } catch (InvalidProtocolBufferException e) {
            loge(TAG, "Could not parse version exchange message", e);
            if (mMessageReceivedErrorListener != null) {
                mMessageReceivedErrorListener.onMessageReceivedError(e);
            }
            return;
        }
        int minMessagingVersion = versionExchange.getMinSupportedMessagingVersion();
        int maxMessagingVersion = versionExchange.getMaxSupportedMessagingVersion();
        int minSecurityVersion = versionExchange.getMinSupportedSecurityVersion();
        int maxSecurityVersion = versionExchange.getMaxSupportedSecurityVersion();
        if (minMessagingVersion > MESSAGING_VERSION || maxMessagingVersion < MESSAGING_VERSION
                || minSecurityVersion > SECURITY_VERSION || maxSecurityVersion < SECURITY_VERSION) {
            loge(TAG, "Unsupported message version for min " + minMessagingVersion + " and max "
                    + maxMessagingVersion + " or security version for " + minSecurityVersion
                    + " and max " + maxSecurityVersion + ".");
            if (mMessageReceivedErrorListener != null) {
                mMessageReceivedErrorListener.onMessageReceivedError(
                        new IllegalStateException("Unsupported version."));
            }
            return;
        }

        BleVersionExchange headunitVersion = BleVersionExchange.newBuilder()
                .setMinSupportedMessagingVersion(MESSAGING_VERSION)
                .setMaxSupportedMessagingVersion(MESSAGING_VERSION)
                .setMinSupportedSecurityVersion(SECURITY_VERSION)
                .setMaxSupportedSecurityVersion(SECURITY_VERSION)
                .build();
        mWriteCharacteristic.setValue(headunitVersion.toByteArray());
        mBlePeripheralManager.notifyCharacteristicChanged(device, mWriteCharacteristic,
                /* confirm= */ false);
        mIsVersionExchanged.set(true);
        logd(TAG, "Sent supported version to the phone.");
    }

    @VisibleForTesting
    void processPacket(@NonNull BlePacket packet) {
        // Messages are coming in. Need to throttle outgoing messages to allow outgoing
        // notifications to make it to the device.
        mThrottleDelay.set(THROTTLE_WAIT_MS);

        int messageId = packet.getMessageId();
        int packetNumber = packet.getPacketNumber();
        int expectedPacket = mPendingPacketNumber.getOrDefault(messageId, 1);
        if (packetNumber == expectedPacket - 1) {
            logw(TAG, "Received duplicate packet " + packet.getPacketNumber() + " for message "
                    + messageId + ". Ignoring.");
            return;
        }
        if (packetNumber != expectedPacket) {
            loge(TAG, "Received unexpected packet " + packetNumber + " for message "
                    + messageId + ".");
            if (mMessageReceivedErrorListener != null) {
                mMessageReceivedErrorListener.onMessageReceivedError(
                        new IllegalStateException("Packet received out of order."));
            }
            return;
        }
        mPendingPacketNumber.put(messageId, packetNumber + 1);

        ByteArrayOutputStream currentPayloadStream =
                mPendingData.getOrDefault(messageId, new ByteArrayOutputStream());
        mPendingData.putIfAbsent(messageId, currentPayloadStream);

        byte[] payload = packet.getPayload().toByteArray();
        try {
            currentPayloadStream.write(payload);
        } catch (IOException e) {
            loge(TAG, "Error writing packet to stream.", e);
            if (mMessageReceivedErrorListener != null) {
                mMessageReceivedErrorListener.onMessageReceivedError(e);
            }
            return;
        }
        logd(TAG, "Parsed packet " + packet.getPacketNumber() + " of "
                + packet.getTotalPackets() + " for message " + messageId + ". Writing "
                + payload.length + ".");

        if (packet.getPacketNumber() != packet.getTotalPackets()) {
            return;
        }

        byte[] messageBytes = currentPayloadStream.toByteArray();
        mPendingData.remove(messageId);

        // All message packets received. Resetting throttle back to default until next message
        // started.
        mThrottleDelay.set(THROTTLE_DEFAULT_MS);

        logd(TAG, "Received complete device message " + messageId + " of " + messageBytes.length
                + " bytes.");
        BleDeviceMessage message;
        try {
            message = BleDeviceMessage.parseFrom(messageBytes);
        } catch (InvalidProtocolBufferException e) {
            loge(TAG, "Cannot parse device message from client.", e);
            if (mMessageReceivedErrorListener != null) {
                mMessageReceivedErrorListener.onMessageReceivedError(e);
            }
            return;
        }

        DeviceMessage deviceMessage = new DeviceMessage(
                ByteUtils.bytesToUUID(message.getRecipient().toByteArray()),
                message.getIsPayloadEncrypted(), message.getPayload().toByteArray());
        if (mMessageReceivedListener != null) {
            mMessageReceivedListener.onMessageReceived(deviceMessage, message.getOperation());
        }
    }

    /** The maximum amount of bytes that can be written over BLE. */
    void setMaxWriteSize(int maxWriteSize) {
        if (maxWriteSize <= 0) {
            return;
        }
        mMaxWriteSize = maxWriteSize;
    }

    /**
     * Set the given listener to be notified when a new message was received from the
     * client. If listener is {@code null}, clear.
     */
    void setMessageReceivedListener(@Nullable MessageReceivedListener listener) {
        mMessageReceivedListener = listener;
    }

    /**
     * Set the given listener to be notified when there was an error during receiving
     * message from the client. If listener is {@code null}, clear.
     */
    void setMessageReceivedErrorListener(
            @Nullable MessageReceivedErrorListener listener) {
        mMessageReceivedErrorListener = listener;
    }

    /**
     * Listener to be invoked when a complete message is received from the client.
     */
    interface MessageReceivedListener {

        /**
         * Called when a complete message is received from the client.
         *
         * @param deviceMessage The message received from the client.
         * @param operationType The {@link OperationType} of the received message.
         */
        void onMessageReceived(@NonNull DeviceMessage deviceMessage, OperationType operationType);
    }

    /**
     * Listener to be invoked when there was an error during receiving message from the client.
     */
    interface MessageReceivedErrorListener {
        /**
         * Called when there was an error during receiving message from the client.
         *
         * @param exception The error.
         */
        void onMessageReceivedError(@NonNull Exception exception);
    }

    /** A generator of unique IDs for messages. */
    private static class MessageIdGenerator {
        private final AtomicInteger mMessageId = new AtomicInteger(0);

        int next() {
            int current = mMessageId.getAndIncrement();
            mMessageId.compareAndSet(Integer.MAX_VALUE, 0);
            return current;
        }
    }
}
