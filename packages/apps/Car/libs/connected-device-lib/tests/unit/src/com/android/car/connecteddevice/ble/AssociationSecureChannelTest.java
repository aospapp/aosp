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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.encryptionrunner.DummyEncryptionRunner;
import android.car.encryptionrunner.EncryptionRunner;
import android.car.encryptionrunner.EncryptionRunnerFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.BleStreamProtos.BleOperationProto.OperationType;
import com.android.car.connecteddevice.ble.BleDeviceMessageStream.MessageReceivedListener;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class AssociationSecureChannelTest {
    private static final UUID CLIENT_DEVICE_ID =
            UUID.fromString("a5645523-3280-410a-90c1-582a6c6f4969");
    private static final UUID SERVER_DEVICE_ID =
            UUID.fromString("a29f0c74-2014-4b14-ac02-be6ed15b545a");
    private static final byte[] CLIENT_SECRET = ByteUtils.randomBytes(32);

    @Mock
    private BleDeviceMessageStream mStreamMock;
    @Mock
    private ConnectedDeviceStorage mStorageMock;
    @Mock
    private AssociationSecureChannel.ShowVerificationCodeListener mShowVerificationCodeListenerMock;
    private MockitoSession mMockitoSession;

    private AssociationSecureChannel mChannel;
    private MessageReceivedListener mMessageReceivedListener;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
        when(mStorageMock.getUniqueId()).thenReturn(SERVER_DEVICE_ID);
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testEncryptionHandshake_Association() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        ChannelCallback callbackSpy = spy(new ChannelCallback(semaphore));
        setupAssociationSecureChannel(callbackSpy, EncryptionRunnerFactory::newDummyRunner);
        ArgumentCaptor<String> deviceIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DeviceMessage> messageCaptor =
                ArgumentCaptor.forClass(DeviceMessage.class);

        initHandshakeMessage();
        verify(mStreamMock).writeMessage(messageCaptor.capture(), any());
        byte[] response = messageCaptor.getValue().getMessage();
        assertThat(response).isEqualTo(DummyEncryptionRunner.INIT_RESPONSE.getBytes());

        respondToContinueMessage();
        verify(mShowVerificationCodeListenerMock).showVerificationCode(anyString());

        mChannel.notifyOutOfBandAccepted();
        sendDeviceId();
        assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue();
        verify(callbackSpy).onDeviceIdReceived(deviceIdCaptor.capture());
        verify(mStreamMock, times(2)).writeMessage(messageCaptor.capture(), any());
        byte[] deviceIdMessage = messageCaptor.getValue().getMessage();
        assertThat(deviceIdMessage).isEqualTo(ByteUtils.uuidToBytes(SERVER_DEVICE_ID));
        assertThat(deviceIdCaptor.getValue()).isEqualTo(CLIENT_DEVICE_ID.toString());
        verify(mStorageMock).saveEncryptionKey(eq(CLIENT_DEVICE_ID.toString()), any());
        verify(mStorageMock).saveChallengeSecret(CLIENT_DEVICE_ID.toString(), CLIENT_SECRET);

        assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue();
        verify(callbackSpy).onSecureChannelEstablished();
    }

    @Test
    public void testEncryptionHandshake_Association_wrongInitHandshakeMessage()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        ChannelCallback callbackSpy = spy(new ChannelCallback(semaphore));
        setupAssociationSecureChannel(callbackSpy, EncryptionRunnerFactory::newDummyRunner);

        // Wrong init handshake message
        respondToContinueMessage();
        assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue();
        verify(callbackSpy).onEstablishSecureChannelFailure(
                eq(SecureBleChannel.CHANNEL_ERROR_INVALID_HANDSHAKE)
        );
    }

    @Test
    public void testEncryptionHandshake_Association_wrongRespondToContinueMessage()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        ChannelCallback callbackSpy = spy(new ChannelCallback(semaphore));
        setupAssociationSecureChannel(callbackSpy, EncryptionRunnerFactory::newDummyRunner);

        initHandshakeMessage();

        // Wrong respond to continue message
        initHandshakeMessage();
        assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue();
        verify(callbackSpy).onEstablishSecureChannelFailure(
                eq(SecureBleChannel.CHANNEL_ERROR_INVALID_HANDSHAKE)
        );
    }

    private void setupAssociationSecureChannel(ChannelCallback callback,
            EncryptionRunnerProvider encryptionRunnerProvider) {
        mChannel = new AssociationSecureChannel(mStreamMock, mStorageMock,
                encryptionRunnerProvider.getEncryptionRunner());
        mChannel.registerCallback(callback);
        mChannel.setShowVerificationCodeListener(mShowVerificationCodeListenerMock);
        ArgumentCaptor<MessageReceivedListener> listenerCaptor =
                ArgumentCaptor.forClass(MessageReceivedListener.class);
        verify(mStreamMock).setMessageReceivedListener(listenerCaptor.capture());
        mMessageReceivedListener = listenerCaptor.getValue();
    }

    private void sendDeviceId() {
        DeviceMessage message = new DeviceMessage(
                /* recipient= */ null,
                /* isMessageEncrypted= */ true,
                ByteUtils.concatByteArrays(ByteUtils.uuidToBytes(CLIENT_DEVICE_ID), CLIENT_SECRET)
        );
        mMessageReceivedListener.onMessageReceived(message, OperationType.ENCRYPTION_HANDSHAKE);
    }

    private void initHandshakeMessage() {
        DeviceMessage message = new DeviceMessage(
                /* recipient= */ null,
                /* isMessageEncrypted= */ false,
                DummyEncryptionRunner.INIT.getBytes()
        );
        mMessageReceivedListener.onMessageReceived(message, OperationType.ENCRYPTION_HANDSHAKE);
    }

    private void respondToContinueMessage() {
        DeviceMessage message = new DeviceMessage(
                /* recipient= */ null,
                /* isMessageEncrypted= */ false,
                DummyEncryptionRunner.CLIENT_RESPONSE.getBytes()
        );
        mMessageReceivedListener.onMessageReceived(message, OperationType.ENCRYPTION_HANDSHAKE);
    }

    /**
     * Add the thread control logic into {@link SecureBleChannel.Callback} only for spy purpose.
     *
     * <p>The callback will release the semaphore which hold by one test when this callback
     * is called, telling the test that it can verify certain behaviors which will only occurred
     * after the callback is notified. This is needed mainly because of the callback is notified
     * in a different thread.
     */
    private static class ChannelCallback implements SecureBleChannel.Callback {
        private final Semaphore mSemaphore;

        ChannelCallback(Semaphore semaphore) {
            mSemaphore = semaphore;
        }

        @Override
        public void onSecureChannelEstablished() {
            mSemaphore.release();
        }

        @Override
        public void onEstablishSecureChannelFailure(int error) {
            mSemaphore.release();
        }

        @Override
        public void onMessageReceived(DeviceMessage deviceMessage) {
            mSemaphore.release();
        }

        @Override
        public void onMessageReceivedError(Exception exception) {
            mSemaphore.release();
        }

        @Override
        public void onDeviceIdReceived(String deviceId) {
            mSemaphore.release();
        }
    }

    interface EncryptionRunnerProvider {
        EncryptionRunner getEncryptionRunner();
    }
}
