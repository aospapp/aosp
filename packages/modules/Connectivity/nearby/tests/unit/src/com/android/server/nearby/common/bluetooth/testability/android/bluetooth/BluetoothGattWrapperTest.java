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
 * Unit tests for {@link BluetoothGattWrapper}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothGattWrapperTest {
    private static final UUID UUID_CONST = UUID.randomUUID();
    private static final byte[] BYTES = new byte[]{1, 2, 3};

    @Mock private android.bluetooth.BluetoothDevice mBluetoothDevice;
    @Mock private android.bluetooth.BluetoothGatt mBluetoothGatt;
    @Mock private android.bluetooth.BluetoothGattService mBluetoothGattService;
    @Mock private android.bluetooth.BluetoothGattCharacteristic mBluetoothGattCharacteristic;
    @Mock private android.bluetooth.BluetoothGattDescriptor mBluetoothGattDescriptor;

    BluetoothGattWrapper mBluetoothGattWrapper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mBluetoothGattWrapper = BluetoothGattWrapper.wrap(mBluetoothGatt);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNonNullAdapter_isNotNull_unWrapSame() {
        assertThat(mBluetoothGattWrapper).isNotNull();
        assertThat(mBluetoothGattWrapper.unwrap()).isSameInstanceAs(mBluetoothGatt);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testEquality_asExpected() {
        assertThat(mBluetoothGattWrapper.equals(null)).isFalse();
        assertThat(mBluetoothGattWrapper.equals(mBluetoothGattWrapper)).isTrue();
        assertThat(mBluetoothGattWrapper.equals(BluetoothGattWrapper.wrap(mBluetoothGatt)))
                .isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetDevice_callsWrapped() {
        when(mBluetoothGatt.getDevice()).thenReturn(mBluetoothDevice);
        assertThat(mBluetoothGattWrapper.getDevice().unwrap()).isSameInstanceAs(mBluetoothDevice);
    }

    @Test
    public void testHashCode_asExpected() {
        assertThat(mBluetoothGattWrapper.hashCode())
                .isEqualTo(BluetoothGattWrapper.wrap(mBluetoothGatt).hashCode());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetServices_callsWrapped() {
        when(mBluetoothGatt.getServices()).thenReturn(null);
        assertThat(mBluetoothGattWrapper.getServices()).isNull();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetService_callsWrapped() {
        when(mBluetoothGatt.getService(UUID_CONST)).thenReturn(mBluetoothGattService);
        assertThat(mBluetoothGattWrapper.getService(UUID_CONST))
                .isSameInstanceAs(mBluetoothGattService);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testDiscoverServices_callsWrapped() {
        when(mBluetoothGatt.discoverServices()).thenReturn(true);
        assertThat(mBluetoothGattWrapper.discoverServices()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testReadCharacteristic_callsWrapped() {
        when(mBluetoothGatt.readCharacteristic(mBluetoothGattCharacteristic)).thenReturn(true);
        assertThat(mBluetoothGattWrapper.readCharacteristic(mBluetoothGattCharacteristic)).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWriteCharacteristic_callsWrapped() {
        when(mBluetoothGatt.writeCharacteristic(mBluetoothGattCharacteristic, BYTES, 1))
                .thenReturn(1);
        assertThat(mBluetoothGattWrapper.writeCharacteristic(
                mBluetoothGattCharacteristic, BYTES, 1)).isEqualTo(1);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testReadDescriptor_callsWrapped() {
        when(mBluetoothGatt.readDescriptor(mBluetoothGattDescriptor)).thenReturn(false);
        assertThat(mBluetoothGattWrapper.readDescriptor(mBluetoothGattDescriptor)).isFalse();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWriteDescriptor_callsWrapped() {
        when(mBluetoothGatt.writeDescriptor(mBluetoothGattDescriptor, BYTES)).thenReturn(5);
        assertThat(mBluetoothGattWrapper.writeDescriptor(mBluetoothGattDescriptor, BYTES))
                .isEqualTo(5);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testReadRemoteRssi_callsWrapped() {
        when(mBluetoothGatt.readRemoteRssi()).thenReturn(false);
        assertThat(mBluetoothGattWrapper.readRemoteRssi()).isFalse();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testRequestConnectionPriority_callsWrapped() {
        when(mBluetoothGatt.requestConnectionPriority(5)).thenReturn(false);
        assertThat(mBluetoothGattWrapper.requestConnectionPriority(5)).isFalse();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testRequestMtu_callsWrapped() {
        when(mBluetoothGatt.requestMtu(5)).thenReturn(false);
        assertThat(mBluetoothGattWrapper.requestMtu(5)).isFalse();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSetCharacteristicNotification_callsWrapped() {
        when(mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristic, true))
                .thenReturn(false);
        assertThat(mBluetoothGattWrapper
                .setCharacteristicNotification(mBluetoothGattCharacteristic, true)).isFalse();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testDisconnect_callsWrapped() {
        doNothing().when(mBluetoothGatt).disconnect();
        mBluetoothGattWrapper.disconnect();
        verify(mBluetoothGatt).disconnect();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testClose_callsWrapped() {
        doNothing().when(mBluetoothGatt).close();
        mBluetoothGattWrapper.close();
        verify(mBluetoothGatt).close();
    }
}
