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

import static android.car.encryptionrunner.EncryptionRunnerFactory.EncryptionRunnerType;
import static android.car.encryptionrunner.EncryptionRunnerFactory.newRunner;
import static android.car.encryptionrunner.HandshakeMessage.HandshakeState;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.encryptionrunner.EncryptionRunner;
import android.car.encryptionrunner.HandshakeException;
import android.car.encryptionrunner.HandshakeMessage;
import android.car.encryptionrunner.Key;

import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;
import com.android.internal.annotations.VisibleForTesting;

import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.UUID;

/**
 * A secure channel established with the association flow.
 */
class AssociationSecureChannel extends SecureBleChannel {

    private static final String TAG = "AssociationSecureChannel";

    private static final int DEVICE_ID_BYTES = 16;

    private final ConnectedDeviceStorage mStorage;

    private ShowVerificationCodeListener mShowVerificationCodeListener;

    @HandshakeState
    private int mState = HandshakeState.UNKNOWN;

    private Key mPendingKey;

    private String mDeviceId;

    AssociationSecureChannel(BleDeviceMessageStream stream, ConnectedDeviceStorage storage) {
        this(stream, storage, newRunner(EncryptionRunnerType.UKEY2));
    }

    AssociationSecureChannel(BleDeviceMessageStream stream, ConnectedDeviceStorage storage,
            EncryptionRunner encryptionRunner) {
        super(stream, encryptionRunner);
        encryptionRunner.setIsReconnect(false);
        mStorage = storage;
    }

    @Override
    void processHandshake(@NonNull byte[] message) throws HandshakeException {
        switch (mState) {
            case HandshakeState.UNKNOWN:
                processHandshakeUnknown(message);
                break;
            case HandshakeState.IN_PROGRESS:
                processHandshakeInProgress(message);
                break;
            case HandshakeState.FINISHED:
                processHandshakeDeviceIdAndSecret(message);
                break;
            default:
                loge(TAG, "Encountered unexpected handshake state: " + mState + ".");
                notifySecureChannelFailure(CHANNEL_ERROR_INVALID_STATE);
        }
    }

    private void processHandshakeUnknown(@NonNull byte[] message) throws HandshakeException {
        logd(TAG, "Responding to handshake init request.");
        HandshakeMessage handshakeMessage = getEncryptionRunner().respondToInitRequest(message);
        mState = handshakeMessage.getHandshakeState();
        sendHandshakeMessage(handshakeMessage.getNextMessage(), /* isEncrypted= */ false);
    }

    private void processHandshakeInProgress(@NonNull byte[] message) throws HandshakeException {
        logd(TAG, "Continuing handshake.");
        HandshakeMessage handshakeMessage = getEncryptionRunner().continueHandshake(message);
        mState = handshakeMessage.getHandshakeState();

        if (mState != HandshakeState.VERIFICATION_NEEDED) {
            loge(TAG, "processHandshakeInProgress: Encountered unexpected handshake state: "
                    + mState + ".");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_STATE);
            return;
        }

        String code = handshakeMessage.getVerificationCode();
        if (code == null) {
            loge(TAG, "Unable to get verification code.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_VERIFICATION);
            return;
        }

        if (mShowVerificationCodeListener == null) {
            loge(TAG, "No verification code listener has been set. Unable to display verification "
                    + "code to user.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_STATE);
            return;
        }

        logd(TAG, "Showing pairing code: " + code);
        mShowVerificationCodeListener.showVerificationCode(code);
    }

    private void processHandshakeDeviceIdAndSecret(@NonNull byte[] message) {
        UUID deviceId = ByteUtils.bytesToUUID(Arrays.copyOf(message, DEVICE_ID_BYTES));
        if (deviceId == null) {
            loge(TAG, "Received invalid device id. Aborting.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_DEVICE_ID);
            return;
        }
        mDeviceId = deviceId.toString();
        notifyCallback(callback -> callback.onDeviceIdReceived(mDeviceId));

        mStorage.saveEncryptionKey(mDeviceId, mPendingKey.asBytes());
        mPendingKey = null;
        try {
            mStorage.saveChallengeSecret(mDeviceId,
                    Arrays.copyOfRange(message, DEVICE_ID_BYTES, message.length));
        } catch (InvalidParameterException e) {
            loge(TAG, "Error saving challenge secret.", e);
            notifySecureChannelFailure(CHANNEL_ERROR_STORAGE_ERROR);
            return;
        }

        notifyCallback(Callback::onSecureChannelEstablished);
    }

    /** Set the listener that notifies to show verification code. {@code null} to clear. */
    void setShowVerificationCodeListener(@Nullable ShowVerificationCodeListener listener) {
        mShowVerificationCodeListener = listener;
    }

    @VisibleForTesting
    @Nullable
    ShowVerificationCodeListener getShowVerificationCodeListener() {
        return mShowVerificationCodeListener;
    }

    /**
     * Called by the client to notify that the user has accepted a pairing code or any out-of-band
     * confirmation, and send confirmation signals to remote bluetooth device.
     */
    void notifyOutOfBandAccepted() {
        HandshakeMessage message;
        try {
            message = getEncryptionRunner().verifyPin();
        } catch (HandshakeException e) {
            loge(TAG, "Error during PIN verification", e);
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_VERIFICATION);
            return;
        }
        if (message.getHandshakeState() != HandshakeState.FINISHED) {
            loge(TAG, "Handshake not finished after calling verify PIN. Instead got "
                    + "state: " + message.getHandshakeState() + ".");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_STATE);
            return;
        }

        Key localKey = message.getKey();
        if (localKey == null) {
            loge(TAG, "Unable to finish association, generated key is null.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_ENCRYPTION_KEY);
            return;
        }
        mState = message.getHandshakeState();
        setEncryptionKey(localKey);
        mPendingKey = localKey;
        logd(TAG, "Pairing code successfully verified.");
        sendUniqueIdToClient();
    }

    private void sendUniqueIdToClient() {
        UUID uniqueId = mStorage.getUniqueId();
        DeviceMessage deviceMessage = new DeviceMessage(/* recipient= */ null,
                /* isMessageEncrypted= */ true, ByteUtils.uuidToBytes(uniqueId));
        logd(TAG, "Sending car's device id of " + uniqueId + " to device.");
        sendHandshakeMessage(ByteUtils.uuidToBytes(uniqueId), /* isEncrypted= */ true);
    }

    /** Listener that will be invoked to display verification code. */
    interface ShowVerificationCodeListener {
        /**
         * Invoke when a verification need to be displayed during device association.
         *
         * @param code The verification code to show.
         */
        void showVerificationCode(@NonNull String code);
    }
}
