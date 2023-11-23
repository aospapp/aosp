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

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/**
 * Unit tests for {@link BluetoothGattServerCallback}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothGattServerCallbackTest {
    @Mock
    private android.bluetooth.BluetoothDevice mBluetoothDevice;
    @Mock
    private android.bluetooth.BluetoothGattService mBluetoothGattService;
    @Mock
    private android.bluetooth.BluetoothGattCharacteristic mBluetoothGattCharacteristic;
    @Mock
    private android.bluetooth.BluetoothGattDescriptor mBluetoothGattDescriptor;

    TestBluetoothGattServerCallback
            mTestBluetoothGattServerCallback = new TestBluetoothGattServerCallback();

    @Test
    public void testOnCharacteristicReadRequest_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onCharacteristicReadRequest(
                mBluetoothDevice, 1, 1, mBluetoothGattCharacteristic);
    }

    @Test
    public void testOnCharacteristicWriteRequest_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onCharacteristicWriteRequest(
                mBluetoothDevice,
                1,
                mBluetoothGattCharacteristic,
                false,
                true,
                1,
                new byte[]{1});
    }

    @Test
    public void testOnConnectionStateChange_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onConnectionStateChange(
                mBluetoothDevice,
                1,
                2);
    }

    @Test
    public void testOnDescriptorReadRequest_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onDescriptorReadRequest(
                mBluetoothDevice,
                1,
                2, mBluetoothGattDescriptor);
    }

    @Test
    public void testOnDescriptorWriteRequest_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onDescriptorWriteRequest(
                mBluetoothDevice,
                1,
                mBluetoothGattDescriptor,
                false,
                true,
                2,
                new byte[]{1});
    }

    @Test
    public void testOnExecuteWrite_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onExecuteWrite(
                mBluetoothDevice,
                1,
                false);
    }

    @Test
    public void testOnMtuChanged_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onMtuChanged(
                mBluetoothDevice,
                1);
    }

    @Test
    public void testOnNotificationSent_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onNotificationSent(
                mBluetoothDevice,
                1);
    }

    @Test
    public void testOnServiceAdded_notCrash() {
        mTestBluetoothGattServerCallback.unwrap().onServiceAdded(1, mBluetoothGattService);
    }

    private static class TestBluetoothGattServerCallback extends BluetoothGattServerCallback { }
}
