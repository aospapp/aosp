/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.nearby.common.bluetooth.testability.android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

/**
 * Unit tests for {@link BluetoothGattServer}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothGattServerTest {
    private static final UUID UUID_CONST = UUID.randomUUID();
    private static final byte[] BYTES = new byte[]{1, 2, 3};

    @Mock private android.bluetooth.BluetoothDevice mBluetoothDevice;
    @Mock private android.bluetooth.BluetoothGattServer mBluetoothGattServer;
    @Mock private android.bluetooth.BluetoothGattService mBluetoothGattService;
    @Mock private android.bluetooth.BluetoothGattCharacteristic mBluetoothGattCharacteristic;

    BluetoothGattServer mTestabilityBluetoothGattServer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTestabilityBluetoothGattServer = BluetoothGattServer.wrap(mBluetoothGattServer);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNonNullAdapter_isNotNull_unWrapSame() {
        assertThat(mTestabilityBluetoothGattServer).isNotNull();
        assertThat(mTestabilityBluetoothGattServer.unwrap()).isSameInstanceAs(mBluetoothGattServer);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConnect_callsWrapped() {
        when(mBluetoothGattServer
                .connect(mBluetoothDevice, true))
                .thenReturn(true);
        assertThat(mTestabilityBluetoothGattServer
                .connect(BluetoothDevice.wrap(mBluetoothDevice), true))
                .isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testAddService_callsWrapped() {
        when(mBluetoothGattServer
                .addService(mBluetoothGattService))
                .thenReturn(true);
        assertThat(mTestabilityBluetoothGattServer
                .addService(mBluetoothGattService))
                .isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testClearServices_callsWrapped() {
        doNothing().when(mBluetoothGattServer).clearServices();
        mTestabilityBluetoothGattServer.clearServices();
        verify(mBluetoothGattServer).clearServices();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testClose_callsWrapped() {
        doNothing().when(mBluetoothGattServer).close();
        mTestabilityBluetoothGattServer.close();
        verify(mBluetoothGattServer).close();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testNotifyCharacteristicChanged_callsWrapped() {
        when(mBluetoothGattServer
                .notifyCharacteristicChanged(
                        mBluetoothDevice,
                        mBluetoothGattCharacteristic,
                        true))
                .thenReturn(true);
        assertThat(mTestabilityBluetoothGattServer
                .notifyCharacteristicChanged(
                        BluetoothDevice.wrap(mBluetoothDevice),
                        mBluetoothGattCharacteristic,
                        true))
                .isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSendResponse_callsWrapped() {
        when(mBluetoothGattServer.sendResponse(
                mBluetoothDevice, 1, 1, 1, BYTES)).thenReturn(true);
        mTestabilityBluetoothGattServer.sendResponse(
                BluetoothDevice.wrap(mBluetoothDevice), 1, 1, 1, BYTES);
        verify(mBluetoothGattServer).sendResponse(
                mBluetoothDevice, 1, 1, 1, BYTES);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testCancelConnection_callsWrapped() {
        doNothing().when(mBluetoothGattServer).cancelConnection(mBluetoothDevice);
        mTestabilityBluetoothGattServer.cancelConnection(BluetoothDevice.wrap(mBluetoothDevice));
        verify(mBluetoothGattServer).cancelConnection(mBluetoothDevice);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetService_callsWrapped() {
        when(mBluetoothGattServer.getService(UUID_CONST)).thenReturn(null);
        assertThat(mTestabilityBluetoothGattServer.getService(UUID_CONST)).isNull();
    }
}
