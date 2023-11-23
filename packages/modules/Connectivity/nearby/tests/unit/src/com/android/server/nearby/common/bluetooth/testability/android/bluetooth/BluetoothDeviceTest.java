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

import static org.mockito.Mockito.when;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.UUID;

/**
 * Unit tests for {@link BluetoothDevice}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothDeviceTest {
    private static final UUID UUID_CONST = UUID.randomUUID();
    private static final String ADDRESS = "ADDRESS";
    private static final String STRING = "STRING";

    @Mock private android.bluetooth.BluetoothDevice mBluetoothDevice;
    @Mock private android.bluetooth.BluetoothGatt mBluetoothGatt;
    @Mock private android.bluetooth.BluetoothSocket mBluetoothSocket;
    @Mock private android.bluetooth.BluetoothClass mBluetoothClass;

    BluetoothDevice mTestabilityBluetoothDevice;
    BluetoothGattCallback mTestBluetoothGattCallback;
    Context mContext;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTestabilityBluetoothDevice = BluetoothDevice.wrap(mBluetoothDevice);
        mTestBluetoothGattCallback = new TestBluetoothGattCallback();
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNonNullAdapter_isNotNull_unWrapSame() {
        assertThat(mTestabilityBluetoothDevice).isNotNull();
        assertThat(mTestabilityBluetoothDevice.unwrap()).isSameInstanceAs(mBluetoothDevice);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testEquality_asExpected() {
        assertThat(mTestabilityBluetoothDevice.equals(null)).isFalse();
        assertThat(mTestabilityBluetoothDevice.equals(mTestabilityBluetoothDevice)).isTrue();
        assertThat(mTestabilityBluetoothDevice.equals(BluetoothDevice.wrap(mBluetoothDevice)))
                .isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHashCode_asExpected() {
        assertThat(mTestabilityBluetoothDevice.hashCode())
                .isEqualTo(BluetoothDevice.wrap(mBluetoothDevice).hashCode());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConnectGattWithThreeParameters_callsWrapped() {
        when(mBluetoothDevice
                .connectGatt(mContext, true, mTestBluetoothGattCallback.unwrap()))
                .thenReturn(mBluetoothGatt);
        assertThat(mTestabilityBluetoothDevice
                .connectGatt(mContext, true, mTestBluetoothGattCallback)
                .unwrap())
                .isSameInstanceAs(mBluetoothGatt);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConnectGattWithFourParameters_callsWrapped() {
        when(mBluetoothDevice
                .connectGatt(mContext, true, mTestBluetoothGattCallback.unwrap(), 1))
                .thenReturn(mBluetoothGatt);
        assertThat(mTestabilityBluetoothDevice
                .connectGatt(mContext, true, mTestBluetoothGattCallback, 1)
                .unwrap())
                .isSameInstanceAs(mBluetoothGatt);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testCreateRfcommSocketToServiceRecord_callsWrapped() throws IOException {
        when(mBluetoothDevice.createRfcommSocketToServiceRecord(UUID_CONST))
                .thenReturn(mBluetoothSocket);
        assertThat(mTestabilityBluetoothDevice.createRfcommSocketToServiceRecord(UUID_CONST))
                .isSameInstanceAs(mBluetoothSocket);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testCreateInsecureRfcommSocketToServiceRecord_callsWrapped() throws IOException {
        when(mBluetoothDevice.createInsecureRfcommSocketToServiceRecord(UUID_CONST))
                .thenReturn(mBluetoothSocket);
        assertThat(mTestabilityBluetoothDevice
                .createInsecureRfcommSocketToServiceRecord(UUID_CONST))
                .isSameInstanceAs(mBluetoothSocket);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSetPairingConfirmation_callsWrapped() throws IOException {
        when(mBluetoothDevice.setPairingConfirmation(true)).thenReturn(true);
        assertThat(mTestabilityBluetoothDevice.setPairingConfirmation(true)).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testFetchUuidsWithSdp_callsWrapped() throws IOException {
        when(mBluetoothDevice.fetchUuidsWithSdp()).thenReturn(true);
        assertThat(mTestabilityBluetoothDevice.fetchUuidsWithSdp()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testCreateBond_callsWrapped() throws IOException {
        when(mBluetoothDevice.createBond()).thenReturn(true);
        assertThat(mTestabilityBluetoothDevice.createBond()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetUuids_callsWrapped() throws IOException {
        when(mBluetoothDevice.getUuids()).thenReturn(null);
        assertThat(mTestabilityBluetoothDevice.getUuids()).isNull();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetBondState_callsWrapped() throws IOException {
        when(mBluetoothDevice.getBondState()).thenReturn(1);
        assertThat(mTestabilityBluetoothDevice.getBondState()).isEqualTo(1);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetAddress_callsWrapped() throws IOException {
        when(mBluetoothDevice.getAddress()).thenReturn(ADDRESS);
        assertThat(mTestabilityBluetoothDevice.getAddress()).isSameInstanceAs(ADDRESS);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetBluetoothClass_callsWrapped() throws IOException {
        when(mBluetoothDevice.getBluetoothClass()).thenReturn(mBluetoothClass);
        assertThat(mTestabilityBluetoothDevice.getBluetoothClass())
                .isSameInstanceAs(mBluetoothClass);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetType_callsWrapped() throws IOException {
        when(mBluetoothDevice.getType()).thenReturn(1);
        assertThat(mTestabilityBluetoothDevice.getType()).isEqualTo(1);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetName_callsWrapped() throws IOException {
        when(mBluetoothDevice.getName()).thenReturn(STRING);
        assertThat(mTestabilityBluetoothDevice.getName()).isSameInstanceAs(STRING);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testToString_callsWrapped() {
        when(mBluetoothDevice.toString()).thenReturn(STRING);
        assertThat(mTestabilityBluetoothDevice.toString()).isSameInstanceAs(STRING);
    }

    private static class TestBluetoothGattCallback extends BluetoothGattCallback {}
}
