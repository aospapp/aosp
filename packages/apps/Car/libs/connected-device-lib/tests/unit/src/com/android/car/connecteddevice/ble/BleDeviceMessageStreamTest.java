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

import static com.android.car.connecteddevice.BleStreamProtos.BleDeviceMessageProto.BleDeviceMessage;
import static com.android.car.connecteddevice.BleStreamProtos.BleOperationProto.OperationType;
import static com.android.car.connecteddevice.BleStreamProtos.BlePacketProto.BlePacket;
import static com.android.car.connecteddevice.ble.BleDeviceMessageStream.MessageReceivedErrorListener;
import static com.android.car.connecteddevice.ble.BleDeviceMessageStream.MessageReceivedListener;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.util.ByteUtils;
import com.android.car.protobuf.ByteString;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class BleDeviceMessageStreamTest {

    private static final int PACKET_SIZE = 500;

    private BleDeviceMessageStream mStream;

    @Mock
    private BlePeripheralManager mMockBlePeripheralManager;

    @Mock
    private BluetoothDevice mMockBluetoothDevice;

    @Mock
    private BluetoothGattCharacteristic mMockWriteCharacteristic;

    @Mock
    private BluetoothGattCharacteristic mMockReadCharacteristic;

    private MockitoSession mMockingSession;

    @Before
    public void setup() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mStream = new BleDeviceMessageStream(mMockBlePeripheralManager, mMockBluetoothDevice,
                mMockWriteCharacteristic, mMockReadCharacteristic, PACKET_SIZE);
    }

    @After
    public void cleanup() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void processPacket_notifiesWithEntireMessageForSinglePacketMessage()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        MessageReceivedListener listener = createMessageReceivedListener(semaphore);
        mStream.setMessageReceivedListener(listener);
        byte[] data = ByteUtils.randomBytes(5);
        processMessage(data);
        assertThat(tryAcquire(semaphore)).isTrue();
        ArgumentCaptor<DeviceMessage> messageCaptor = ArgumentCaptor.forClass(DeviceMessage.class);
        verify(listener).onMessageReceived(messageCaptor.capture(), any());
    }

    @Test
    public void processPacket_notifiesWithEntireMessageForMultiPacketMessage()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        MessageReceivedListener listener = createMessageReceivedListener(semaphore);
        mStream.setMessageReceivedListener(listener);
        byte[] data = ByteUtils.randomBytes(750);
        processMessage(data);
        assertThat(tryAcquire(semaphore)).isTrue();
        ArgumentCaptor<DeviceMessage> messageCaptor = ArgumentCaptor.forClass(DeviceMessage.class);
        verify(listener).onMessageReceived(messageCaptor.capture(), any());
        assertThat(Arrays.equals(data, messageCaptor.getValue().getMessage())).isTrue();
    }

    @Test
    public void processPacket_receivingMultipleMessagesInParallelParsesSuccessfully()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        MessageReceivedListener listener = createMessageReceivedListener(semaphore);
        mStream.setMessageReceivedListener(listener);
        byte[] data = ByteUtils.randomBytes((int) (PACKET_SIZE * 1.5));
        List<BlePacket> packets1 = createPackets(data);
        List<BlePacket> packets2 = createPackets(data);

        for (int i = 0; i < packets1.size(); i++) {
            mStream.processPacket(packets1.get(i));
            if (i == packets1.size() - 1) {
                break;
            }
            mStream.processPacket(packets2.get(i));
        }
        assertThat(tryAcquire(semaphore)).isTrue();
        ArgumentCaptor<DeviceMessage> messageCaptor = ArgumentCaptor.forClass(DeviceMessage.class);
        verify(listener).onMessageReceived(messageCaptor.capture(), any());
        assertThat(Arrays.equals(data, messageCaptor.getValue().getMessage())).isTrue();

        semaphore = new Semaphore(0);
        listener = createMessageReceivedListener(semaphore);
        mStream.setMessageReceivedListener(listener);
        mStream.processPacket(packets2.get(packets2.size() - 1));
        verify(listener).onMessageReceived(messageCaptor.capture(), any());
        assertThat(Arrays.equals(data, messageCaptor.getValue().getMessage())).isTrue();
    }

    @Test
    public void processPacket_doesNotNotifyOfNewMessageIfNotAllPacketsReceived()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        mStream.setMessageReceivedListener(createMessageReceivedListener(semaphore));
        mStream.setMessageReceivedErrorListener(createMessageReceivedErrorListener(semaphore));
        byte[] data = ByteUtils.randomBytes((int) (PACKET_SIZE * 1.5));
        List<BlePacket> packets = createPackets(data);
        for (int i = 0; i < packets.size() - 1; i++) {
            mStream.processPacket(packets.get(i));
        }
        assertThat(tryAcquire(semaphore)).isFalse();
    }

    @Test
    public void processPacket_ignoresDuplicatePacket() {
        Semaphore semaphore = new Semaphore(0);
        byte[] data = ByteUtils.randomBytes((int) (PACKET_SIZE * 2.5));
        MessageReceivedListener listener = createMessageReceivedListener(semaphore);
        mStream.setMessageReceivedListener(listener);
        ArgumentCaptor<DeviceMessage> messageCaptor = ArgumentCaptor.forClass(DeviceMessage.class);
        List<BlePacket> packets = createPackets(data);
        for (int i = 0; i < packets.size(); i++) {
            mStream.processPacket(packets.get(i));
            mStream.processPacket(packets.get(i)); // Process each packet twice.
        }
        verify(listener).onMessageReceived(messageCaptor.capture(), any());
        assertThat(Arrays.equals(data, messageCaptor.getValue().getMessage())).isTrue();
    }

    @Test
    public void processPacket_packetBeforeExpectedRangeNotifiesMessageError()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        mStream.setMessageReceivedErrorListener(createMessageReceivedErrorListener(semaphore));
        List<BlePacket> packets = createPackets(ByteUtils.randomBytes((int) (PACKET_SIZE * 2.5)));
        mStream.processPacket(packets.get(0));
        mStream.processPacket(packets.get(1));
        mStream.processPacket(packets.get(0));
        assertThat(tryAcquire(semaphore)).isTrue();
    }

    @Test
    public void processPacket_packetAfterExpectedNotifiesMessageError()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        mStream.setMessageReceivedErrorListener(createMessageReceivedErrorListener(semaphore));
        List<BlePacket> packets = createPackets(ByteUtils.randomBytes((int) (PACKET_SIZE * 1.5)));
        mStream.processPacket(packets.get(1));
        assertThat(tryAcquire(semaphore)).isTrue();
    }

    @NonNull
    private List<BlePacket> createPackets(byte[] data) {
        try {
            BleDeviceMessage message = BleDeviceMessage.newBuilder()
                    .setPayload(ByteString.copyFrom(data))
                    .setOperation(OperationType.CLIENT_MESSAGE)
                    .build();
            return BlePacketFactory.makeBlePackets(message.toByteArray(),
                    ThreadLocalRandom.current().nextInt(), PACKET_SIZE);
        } catch (Exception e) {
            assertWithMessage("Uncaught exception while making packets.").fail();
            return new ArrayList<>();
        }
    }

    private void processMessage(byte[] data) {
        List<BlePacket> packets = createPackets(data);
        for (BlePacket packet : packets) {
            mStream.processPacket(packet);
        }
    }

    private boolean tryAcquire(@NonNull Semaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
    }

    @NonNull
    private MessageReceivedListener createMessageReceivedListener(@NonNull Semaphore semaphore) {
        return spy((deviceMessage, operationType) -> semaphore.release());
    }

    @NonNull
    private MessageReceivedErrorListener createMessageReceivedErrorListener(
            @NonNull Semaphore semaphore) {
        return exception -> semaphore.release();
    }
}
