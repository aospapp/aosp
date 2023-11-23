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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.car.encryptionrunner.EncryptionRunnerFactory;
import android.car.encryptionrunner.Key;
import android.os.ParcelUuid;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.AssociationCallback;
import com.android.car.connecteddevice.model.AssociatedDevice;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CarBlePeripheralManagerTest {
    private static final UUID ASSOCIATION_SERVICE_UUID = UUID.randomUUID();
    private static final UUID RECONNECT_SERVICE_UUID = UUID.randomUUID();
    private static final UUID RECONNECT_DATA_UUID = UUID.randomUUID();
    private static final UUID WRITE_UUID = UUID.randomUUID();
    private static final UUID READ_UUID = UUID.randomUUID();
    private static final int DEVICE_NAME_LENGTH_LIMIT = 8;
    private static final String TEST_REMOTE_DEVICE_ADDRESS = "00:11:22:33:AA:BB";
    private static final UUID TEST_REMOTE_DEVICE_ID = UUID.randomUUID();
    private static final String TEST_VERIFICATION_CODE = "000000";
    private static final byte[] TEST_KEY = "Key".getBytes();
    private static final Duration RECONNECT_ADVERTISEMENT_DURATION = Duration.ofSeconds(2);
    private static final int DEFAULT_MTU_SIZE = 23;

    private static String sAdapterName;

    @Mock
    private BlePeripheralManager mMockPeripheralManager;
    @Mock
    private ConnectedDeviceStorage mMockStorage;

    private CarBlePeripheralManager mCarBlePeripheralManager;

    private MockitoSession mMockitoSession;

    @BeforeClass
    public static void beforeSetUp() {
        sAdapterName = BluetoothAdapter.getDefaultAdapter().getName();
    }

    @Before
    public void setUp() throws Exception {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
        mCarBlePeripheralManager = new CarBlePeripheralManager(mMockPeripheralManager, mMockStorage,
                ASSOCIATION_SERVICE_UUID, RECONNECT_SERVICE_UUID, RECONNECT_DATA_UUID,
                WRITE_UUID, READ_UUID, RECONNECT_ADVERTISEMENT_DURATION, DEFAULT_MTU_SIZE);
    }

    @After
    public void tearDown() {
        if (mCarBlePeripheralManager != null) {
            mCarBlePeripheralManager.stop();
        }
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @AfterClass
    public static void afterTearDown() {
        BluetoothAdapter.getDefaultAdapter().setName(sAdapterName);
    }

    @Test
    public void testStartAssociationAdvertisingSuccess() {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        String testDeviceName = getNameForAssociation();
        startAssociation(callback, testDeviceName);
        ArgumentCaptor<AdvertiseData> dataCaptor = ArgumentCaptor.forClass(AdvertiseData.class);
        verify(mMockPeripheralManager, timeout(3000)).startAdvertising(any(),
                dataCaptor.capture(), any());
        AdvertiseData data = dataCaptor.getValue();
        assertThat(data.getIncludeDeviceName()).isTrue();
        ParcelUuid expected = new ParcelUuid(ASSOCIATION_SERVICE_UUID);
        assertThat(data.getServiceUuids().get(0)).isEqualTo(expected);
        assertThat(BluetoothAdapter.getDefaultAdapter().getName()).isEqualTo(testDeviceName);
    }

    @Test
    public void testStartAssociationAdvertisingFailure() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        startAssociation(callback, getNameForAssociation());
        ArgumentCaptor<AdvertiseCallback> callbackCaptor =
                ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(mMockPeripheralManager, timeout(3000))
                .startAdvertising(any(), any(), callbackCaptor.capture());
        AdvertiseCallback advertiseCallback = callbackCaptor.getValue();
        int testErrorCode = 2;
        advertiseCallback.onStartFailure(testErrorCode);
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onAssociationStartFailure();
    }

    @Test
    public void testNotifyAssociationSuccess() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        String testDeviceName = getNameForAssociation();
        startAssociation(callback, testDeviceName);
        ArgumentCaptor<AdvertiseCallback> callbackCaptor =
                ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(mMockPeripheralManager, timeout(3000))
                .startAdvertising(any(), any(), callbackCaptor.capture());
        AdvertiseCallback advertiseCallback = callbackCaptor.getValue();
        AdvertiseSettings settings = new AdvertiseSettings.Builder().build();
        advertiseCallback.onStartSuccess(settings);
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onAssociationStartSuccess(eq(testDeviceName));
    }

    @Test
    public void testShowVerificationCode() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        AssociationSecureChannel channel = getChannelForAssociation(callback);
        channel.getShowVerificationCodeListener().showVerificationCode(TEST_VERIFICATION_CODE);
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onVerificationCodeAvailable(eq(TEST_VERIFICATION_CODE));
    }

    @Test
    public void testAssociationSuccess() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        SecureBleChannel channel = getChannelForAssociation(callback);
        SecureBleChannel.Callback channelCallback = channel.getCallback();
        assertThat(channelCallback).isNotNull();
        channelCallback.onDeviceIdReceived(TEST_REMOTE_DEVICE_ID.toString());
        Key key = EncryptionRunnerFactory.newDummyRunner().keyOf(TEST_KEY);
        channelCallback.onSecureChannelEstablished();
        ArgumentCaptor<AssociatedDevice> deviceCaptor =
                ArgumentCaptor.forClass(AssociatedDevice.class);
        verify(mMockStorage).addAssociatedDeviceForActiveUser(deviceCaptor.capture());
        AssociatedDevice device = deviceCaptor.getValue();
        assertThat(device.getDeviceId()).isEqualTo(TEST_REMOTE_DEVICE_ID.toString());
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onAssociationCompleted(eq(TEST_REMOTE_DEVICE_ID.toString()));
    }

    @Test
    public void testAssociationFailure_channelError() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        SecureBleChannel channel = getChannelForAssociation(callback);
        SecureBleChannel.Callback channelCallback = channel.getCallback();
        int testErrorCode = 1;
        assertThat(channelCallback).isNotNull();
        channelCallback.onDeviceIdReceived(TEST_REMOTE_DEVICE_ID.toString());
        channelCallback.onEstablishSecureChannelFailure(testErrorCode);
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onAssociationError(eq(testErrorCode));
    }

    @Test
    public void connectToDevice_stopsAdvertisingAfterTimeout() {
        when(mMockStorage.hashWithChallengeSecret(any(), any()))
                .thenReturn(ByteUtils.randomBytes(32));
        mCarBlePeripheralManager.connectToDevice(UUID.randomUUID());
        ArgumentCaptor<AdvertiseCallback> callbackCaptor =
                ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(mMockPeripheralManager).startAdvertising(any(), any(), callbackCaptor.capture());
        callbackCaptor.getValue().onStartSuccess(null);
        verify(mMockPeripheralManager,
                timeout(RECONNECT_ADVERTISEMENT_DURATION.plusSeconds(1).toMillis()))
                .stopAdvertising(any(AdvertiseCallback.class));
    }

    private BlePeripheralManager.Callback startAssociation(AssociationCallback callback,
            String deviceName) {
        ArgumentCaptor<BlePeripheralManager.Callback> callbackCaptor =
                ArgumentCaptor.forClass(BlePeripheralManager.Callback.class);
        mCarBlePeripheralManager.startAssociation(deviceName, callback);
        verify(mMockPeripheralManager, timeout(3000)).registerCallback(callbackCaptor.capture());
        return callbackCaptor.getValue();
    }

    private AssociationSecureChannel getChannelForAssociation(AssociationCallback callback) {
        BlePeripheralManager.Callback bleManagerCallback = startAssociation(callback,
                getNameForAssociation());
        BluetoothDevice bluetoothDevice = BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice(TEST_REMOTE_DEVICE_ADDRESS);
        bleManagerCallback.onRemoteDeviceConnected(bluetoothDevice);
        return (AssociationSecureChannel) mCarBlePeripheralManager.getConnectedDeviceChannel();
    }

    private boolean tryAcquire(Semaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
    }

    private String getNameForAssociation() {
        return ByteUtils.generateRandomNumberString(DEVICE_NAME_LENGTH_LIMIT);

    }

    @NonNull
    private AssociationCallback createAssociationCallback(@NonNull final Semaphore semaphore) {
        return spy(new AssociationCallback() {
            @Override
            public void onAssociationStartSuccess(String deviceName) {
                semaphore.release();
            }

            @Override
            public void onAssociationStartFailure() {
                semaphore.release();
            }

            @Override
            public void onAssociationError(int error) {
                semaphore.release();
            }

            @Override
            public void onVerificationCodeAvailable(String code) {
                semaphore.release();
            }

            @Override
            public void onAssociationCompleted(String deviceId) {
                semaphore.release();
            }
        });
    }
}
