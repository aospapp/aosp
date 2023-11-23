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

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link BluetoothAdapter}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothAdapterTest {

    private static final byte[] BYTES = new byte[]{0, 1, 2, 3, 4, 5};
    private static final String ADDRESS = "00:11:22:33:AA:BB";

    @Mock private android.bluetooth.BluetoothAdapter mBluetoothAdapter;
    @Mock private android.bluetooth.BluetoothDevice mBluetoothDevice;
    @Mock private android.bluetooth.le.BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    @Mock private android.bluetooth.le.BluetoothLeScanner mBluetoothLeScanner;

    BluetoothAdapter mTestabilityBluetoothAdapter;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTestabilityBluetoothAdapter = BluetoothAdapter.wrap(mBluetoothAdapter);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNullAdapter_isNull() {
        assertThat(BluetoothAdapter.wrap(null)).isNull();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNonNullAdapter_isNotNull_unWrapSame() {
        assertThat(mTestabilityBluetoothAdapter).isNotNull();
        assertThat(mTestabilityBluetoothAdapter.unwrap()).isSameInstanceAs(mBluetoothAdapter);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testDisable_callsWrapped() {
        when(mBluetoothAdapter.disable()).thenReturn(true);
        assertThat(mTestabilityBluetoothAdapter.disable()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testEnable_callsWrapped() {
        when(mBluetoothAdapter.enable()).thenReturn(true);
        assertThat(mTestabilityBluetoothAdapter.enable()).isTrue();
        when(mBluetoothAdapter.isEnabled()).thenReturn(true);
        assertThat(mTestabilityBluetoothAdapter.isEnabled()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetBluetoothLeAdvertiser_callsWrapped() {
        when(mBluetoothAdapter.getBluetoothLeAdvertiser()).thenReturn(mBluetoothLeAdvertiser);
        assertThat(mTestabilityBluetoothAdapter.getBluetoothLeAdvertiser().unwrap())
                .isSameInstanceAs(mBluetoothLeAdvertiser);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetBluetoothLeScanner_callsWrapped() {
        when(mBluetoothAdapter.getBluetoothLeScanner()).thenReturn(mBluetoothLeScanner);
        assertThat(mTestabilityBluetoothAdapter.getBluetoothLeScanner().unwrap())
                .isSameInstanceAs(mBluetoothLeScanner);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetBondedDevices_callsWrapped() {
        when(mBluetoothAdapter.getBondedDevices()).thenReturn(null);
        assertThat(mTestabilityBluetoothAdapter.getBondedDevices()).isNull();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testIsDiscovering_pcallsWrapped() {
        when(mBluetoothAdapter.isDiscovering()).thenReturn(true);
        assertThat(mTestabilityBluetoothAdapter.isDiscovering()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStartDiscovery_callsWrapped() {
        when(mBluetoothAdapter.startDiscovery()).thenReturn(true);
        assertThat(mTestabilityBluetoothAdapter.startDiscovery()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testCancelDiscovery_callsWrapped() {
        when(mBluetoothAdapter.cancelDiscovery()).thenReturn(true);
        assertThat(mTestabilityBluetoothAdapter.cancelDiscovery()).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetRemoteDeviceBytes_callsWrapped() {
        when(mBluetoothAdapter.getRemoteDevice(BYTES)).thenReturn(mBluetoothDevice);
        assertThat(mTestabilityBluetoothAdapter.getRemoteDevice(BYTES).unwrap())
                .isSameInstanceAs(mBluetoothDevice);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetRemoteDeviceString_callsWrapped() {
        when(mBluetoothAdapter.getRemoteDevice(ADDRESS)).thenReturn(mBluetoothDevice);
        assertThat(mTestabilityBluetoothAdapter.getRemoteDevice(ADDRESS).unwrap())
                .isSameInstanceAs(mBluetoothDevice);

    }
}
