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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.encryptionrunner.EncryptionRunner;
import android.car.encryptionrunner.EncryptionRunnerFactory;
import android.car.encryptionrunner.HandshakeException;
import android.car.encryptionrunner.HandshakeMessage;
import android.car.encryptionrunner.HandshakeMessage.HandshakeState;
import android.car.encryptionrunner.Key;

import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A secure channel established with the reconnection flow.
 */
class ReconnectSecureChannel extends SecureBleChannel {

    private static final String TAG = "ReconnectSecureChannel";

    private final ConnectedDeviceStorage mStorage;

    private final String mDeviceId;

    private final byte[] mExpectedChallengeResponse;

    @HandshakeState
    private int mState = HandshakeState.UNKNOWN;

    private AtomicBoolean mHasVerifiedDevice = new AtomicBoolean(false);

    /**
     * Create a new secure reconnection channel.
     *
     * @param stream The {@link BleDeviceMessageStream} for communication with the device.
     * @param storage {@link ConnectedDeviceStorage} for secure storage.
     * @param deviceId Id of the device being reconnected.
     * @param expectedChallengeResponse Expected response to challenge issued in reconnect.
     */
    ReconnectSecureChannel(@NonNull BleDeviceMessageStream stream,
            @NonNull ConnectedDeviceStorage storage, @NonNull String deviceId,
            @NonNull byte[] expectedChallengeResponse) {
        super(stream, newReconnectRunner());
        mStorage = storage;
        mDeviceId = deviceId;
        mExpectedChallengeResponse = expectedChallengeResponse;
    }

    private static EncryptionRunner newReconnectRunner() {
        EncryptionRunner encryptionRunner = EncryptionRunnerFactory.newRunner();
        encryptionRunner.setIsReconnect(true);
        return encryptionRunner;
    }

    @Override
    void processHandshake(byte[] message) throws HandshakeException {
        switch (mState) {
            case HandshakeState.UNKNOWN:
                if (!mHasVerifiedDevice.get()) {
                    processHandshakeDeviceVerification(message);
                } else {
                    processHandshakeInitialization(message);
                }
                break;
            case HandshakeState.IN_PROGRESS:
                processHandshakeInProgress(message);
                break;
            case HandshakeState.RESUMING_SESSION:
                processHandshakeResumingSession(message);
                break;
            default:
                loge(TAG, "Encountered unexpected handshake state: " + mState + ".");
                notifySecureChannelFailure(CHANNEL_ERROR_INVALID_STATE);
        }
    }

    private void processHandshakeDeviceVerification(byte[] message) {
        byte[] challengeResponse = Arrays.copyOf(message,
                mExpectedChallengeResponse.length);
        byte[] deviceChallenge = Arrays.copyOfRange(message,
                mExpectedChallengeResponse.length, message.length);
        if (!Arrays.equals(mExpectedChallengeResponse, challengeResponse)) {
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_ENCRYPTION_KEY);
            return;
        }
        logd(TAG, "Responding to challenge " + ByteUtils.byteArrayToHexString(deviceChallenge)
                + ".");
        byte[] deviceChallengeResponse = mStorage.hashWithChallengeSecret(mDeviceId,
                deviceChallenge);
        if (deviceChallengeResponse == null) {
            notifySecureChannelFailure(CHANNEL_ERROR_STORAGE_ERROR);
        }
        sendHandshakeMessage(deviceChallengeResponse, /* isEncrypted= */ false);
        mHasVerifiedDevice.set(true);
    }

    private void processHandshakeInitialization(byte[] message) throws HandshakeException {
        logd(TAG, "Responding to handshake init request.");
        HandshakeMessage handshakeMessage = getEncryptionRunner().respondToInitRequest(message);
        mState = handshakeMessage.getHandshakeState();
        sendHandshakeMessage(handshakeMessage.getNextMessage(), /* isEncrypted= */ false);
    }

    private void processHandshakeInProgress(@NonNull byte[] message) throws HandshakeException {
        logd(TAG, "Continuing handshake.");
        HandshakeMessage handshakeMessage = getEncryptionRunner().continueHandshake(message);
        mState = handshakeMessage.getHandshakeState();
    }

    private void processHandshakeResumingSession(@NonNull byte[] message)
            throws HandshakeException {
        logd(TAG, "Start reconnection authentication.");

        byte[] previousKey = mStorage.getEncryptionKey(mDeviceId);
        if (previousKey == null) {
            loge(TAG, "Unable to resume session, previous key is null.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_ENCRYPTION_KEY);
            return;
        }

        HandshakeMessage handshakeMessage = getEncryptionRunner().authenticateReconnection(message,
                previousKey);
        mState = handshakeMessage.getHandshakeState();
        if (mState != HandshakeState.FINISHED) {
            loge(TAG, "Unable to resume session, unexpected next handshake state: " + mState + ".");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_STATE);
            return;
        }

        Key newKey = handshakeMessage.getKey();
        if (newKey == null) {
            loge(TAG, "Unable to resume session, new key is null.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_ENCRYPTION_KEY);
            return;
        }

        logd(TAG, "Saved new key for reconnection.");
        mStorage.saveEncryptionKey(mDeviceId, newKey.asBytes());
        setEncryptionKey(newKey);
        sendServerAuthToClient(handshakeMessage.getNextMessage());
        notifyCallback(Callback::onSecureChannelEstablished);
    }

    private void sendServerAuthToClient(@Nullable byte[] message) {
        if (message == null) {
            loge(TAG, "Unable to send server authentication message to client, message is null.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_MSG);
            return;
        }

        sendHandshakeMessage(message, /* isEncrypted= */ false);
    }
}
