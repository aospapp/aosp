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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.encryptionrunner.EncryptionRunner;
import android.car.encryptionrunner.HandshakeException;
import android.car.encryptionrunner.Key;

import com.android.car.connecteddevice.BleStreamProtos.BleOperationProto.OperationType;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.SignatureException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Establishes a secure channel with {@link EncryptionRunner} over {@link BleDeviceMessageStream} as
 * server side, sends and receives messages securely after the secure channel has been established.
 */
abstract class SecureBleChannel {

    private static final String TAG = "SecureBleChannel";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CHANNEL_ERROR" },
            value = {
                    CHANNEL_ERROR_INVALID_HANDSHAKE,
                    CHANNEL_ERROR_INVALID_MSG,
                    CHANNEL_ERROR_INVALID_DEVICE_ID,
                    CHANNEL_ERROR_INVALID_VERIFICATION,
                    CHANNEL_ERROR_INVALID_STATE,
                    CHANNEL_ERROR_INVALID_ENCRYPTION_KEY,
                    CHANNEL_ERROR_STORAGE_ERROR
            }
    )
    @interface ChannelError { }

    /** Indicates an error during a Handshake of EncryptionRunner. */
    static final int CHANNEL_ERROR_INVALID_HANDSHAKE = 0;
    /** Received an invalid handshake message or has an invalid handshake message to send. */
    static final int CHANNEL_ERROR_INVALID_MSG = 1;
    /** Unable to retrieve a valid id. */
    static final int CHANNEL_ERROR_INVALID_DEVICE_ID = 2;
    /** Unable to get verification code or there's a error during pin verification. */
    static final int CHANNEL_ERROR_INVALID_VERIFICATION = 3;
    /** Encountered an unexpected handshake state. */
    static final int CHANNEL_ERROR_INVALID_STATE = 4;
    /** Failed to get a valid previous/new encryption key. */
    static final int CHANNEL_ERROR_INVALID_ENCRYPTION_KEY = 5;
    /** Failed to save or retrieve security keys. */
    static final int CHANNEL_ERROR_STORAGE_ERROR = 6;


    private final BleDeviceMessageStream mStream;

    private final EncryptionRunner mEncryptionRunner;

    private final AtomicReference<Key> mEncryptionKey = new AtomicReference<>();

    private Callback mCallback;

    SecureBleChannel(@NonNull BleDeviceMessageStream stream,
            @NonNull EncryptionRunner encryptionRunner) {
        mStream = stream;
        mEncryptionRunner = encryptionRunner;
        mStream.setMessageReceivedListener(this::onMessageReceived);
    }

    /** Logic for processing a handshake message from device. */
    abstract void processHandshake(byte[] message) throws HandshakeException;

    void sendHandshakeMessage(@Nullable byte[] message, boolean isEncrypted) {
        if (message == null) {
            loge(TAG, "Unable to send next handshake message, message is null.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_MSG);
            return;
        }

        logd(TAG, "Sending handshake message.");
        DeviceMessage deviceMessage = new DeviceMessage(/* recipient= */ null,
                isEncrypted, message);
        if (deviceMessage.isMessageEncrypted()) {
            encryptMessage(deviceMessage);
        }
        mStream.writeMessage(deviceMessage, OperationType.ENCRYPTION_HANDSHAKE);
    }

    /** Set the encryption key that secures this channel. */
    void setEncryptionKey(@Nullable Key encryptionKey) {
        mEncryptionKey.set(encryptionKey);
    }

    /**
     * Send a client message.
     * <p>Note: This should be called with an encrypted message only after the secure channel has
     * been established.</p>
     *
     * @param deviceMessage The {@link DeviceMessage} to send.
     */
    void sendClientMessage(@NonNull DeviceMessage deviceMessage)
            throws IllegalStateException {
        if (deviceMessage.isMessageEncrypted()) {
            encryptMessage(deviceMessage);
        }
        mStream.writeMessage(deviceMessage, OperationType.CLIENT_MESSAGE);
    }

    private void encryptMessage(@NonNull DeviceMessage deviceMessage) {
        Key key = mEncryptionKey.get();
        if (key == null) {
            throw new IllegalStateException("Secure channel has not been established.");
        }

        byte[] encryptedMessage = key.encryptData(deviceMessage.getMessage());
        deviceMessage.setMessage(encryptedMessage);
    }

    /** Get the BLE stream backing this channel. */
    @NonNull
    BleDeviceMessageStream getStream() {
        return mStream;
    }

    /** Register a callback that notifies secure channel events. */
    void registerCallback(Callback callback) {
        mCallback = callback;
    }

    /** Unregister a callback. */
    void unregisterCallback(Callback callback) {
        if (callback == mCallback) {
            mCallback = null;
        }
    }

    @VisibleForTesting
    @Nullable
    Callback getCallback() {
        return mCallback;
    }

    void notifyCallback(@NonNull Consumer<Callback> notification) {
        if (mCallback != null) {
            notification.accept(mCallback);
        }
    }

    /** Notify callbacks that an error has occurred. */
    void notifySecureChannelFailure(@ChannelError int error) {
        loge(TAG, "Secure channel error: " + error);
        notifyCallback(callback -> callback.onEstablishSecureChannelFailure(error));
    }

    /** Return the {@link EncryptionRunner} for this channel. */
    @NonNull
    EncryptionRunner getEncryptionRunner() {
        return mEncryptionRunner;
    }

    /**
     * Process the inner message and replace with decrypted value if necessary. If an error occurs
     * the inner message will be replaced with {@code null} and call
     * {@link Callback#onMessageReceivedError(Exception)} on the registered callback.
     *
     * @param deviceMessage The message to process.
     * @return {@code true} if message was successfully processed. {@code false} if an error
     * occurred.
     */
    @VisibleForTesting
    boolean processMessage(@NonNull DeviceMessage deviceMessage) {
        if (!deviceMessage.isMessageEncrypted()) {
            logd(TAG, "Message was not decrypted. No further action necessary.");
            return true;
        }
        Key key = mEncryptionKey.get();
        if (key == null) {
            loge(TAG, "Received encrypted message before secure channel has "
                    + "been established.");
            notifyCallback(callback -> callback.onMessageReceivedError(null));
            deviceMessage.setMessage(null);
            return false;
        }
        try {
            byte[] decryptedMessage = key.decryptData(deviceMessage.getMessage());
            deviceMessage.setMessage(decryptedMessage);
            logd(TAG, "Decrypted secure message.");
            return true;
        } catch (SignatureException e) {
            loge(TAG, "Could not decrypt client credentials.", e);
            notifyCallback(callback -> callback.onMessageReceivedError(e));
            deviceMessage.setMessage(null);

            return false;
        }
    }

    @VisibleForTesting
    void onMessageReceived(@NonNull DeviceMessage deviceMessage, OperationType operationType) {
        boolean success = processMessage(deviceMessage);
        switch(operationType) {
            case ENCRYPTION_HANDSHAKE:
                if (!success) {
                    notifyCallback(callback -> callback.onEstablishSecureChannelFailure(
                            CHANNEL_ERROR_INVALID_HANDSHAKE));
                    break;
                }
                logd(TAG, "Received handshake message.");
                try {
                    processHandshake(deviceMessage.getMessage());
                } catch (HandshakeException e) {
                    loge(TAG, "Handshake failed.", e);
                    notifyCallback(callback -> callback.onEstablishSecureChannelFailure(
                            CHANNEL_ERROR_INVALID_HANDSHAKE));
                }
                break;
            case CLIENT_MESSAGE:
                if (!success || deviceMessage.getMessage() == null) {
                    break;
                }
                logd(TAG, "Received client message.");
                notifyCallback(
                        callback -> callback.onMessageReceived(deviceMessage));
                break;
            default:
                loge(TAG, "Received unexpected operation type: " + operationType + ".");
        }
    }

    /**
     * Callbacks that will be invoked during establishing secure channel, sending and receiving
     * messages securely.
     */
    interface Callback {
        /**
         * Invoked when secure channel has been established successfully.
         */
        default void onSecureChannelEstablished() { }

        /**
         * Invoked when a {@link ChannelError} has been encountered in attempting to establish
         * a secure channel.
         *
         * @param error The failure indication.
         */
        default void onEstablishSecureChannelFailure(@SecureBleChannel.ChannelError int error) { }

        /**
         * Invoked when a complete message is received securely from the client and decrypted.
         *
         * @param deviceMessage The {@link DeviceMessage} with decrypted message.
         */
        default void onMessageReceived(@NonNull DeviceMessage deviceMessage) { }

        /**
         * Invoked when there was an error during a processing or decrypting of a client message.
         *
         * @param exception The error.
         */
        default void onMessageReceivedError(@Nullable Exception exception) { }

        /**
         * Invoked when the device id was received from the client.
         *
         * @param deviceId The unique device id of client.
         */
        default void onDeviceIdReceived(@NonNull String deviceId) { }
    }
}
