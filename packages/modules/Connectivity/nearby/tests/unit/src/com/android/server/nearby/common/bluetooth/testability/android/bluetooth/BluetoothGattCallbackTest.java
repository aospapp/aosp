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
 * Unit tests for {@link BluetoothGattCallback}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothGattCallbackTest {
    @Mock private android.bluetooth.BluetoothGatt mBluetoothGatt;
    @Mock private android.bluetooth.BluetoothGattCharacteristic mBluetoothGattCharacteristic;
    @Mock private android.bluetooth.BluetoothGattDescriptor mBluetoothGattDescriptor;

    TestBluetoothGattCallback mTestBluetoothGattCallback = new TestBluetoothGattCallback();

    @Test
    public void testOnConnectionStateChange_notCrash() {
        mTestBluetoothGattCallback.unwrap()
                .onConnectionStateChange(mBluetoothGatt, 1, 1);
    }

    @Test
    public void testOnServiceDiscovered_notCrash() {
        mTestBluetoothGattCallback.unwrap().onServicesDiscovered(mBluetoothGatt, 1);
    }

    @Test
    public void testOnCharacteristicRead_notCrash() {
        mTestBluetoothGattCallback.unwrap().onCharacteristicRead(mBluetoothGatt,
                mBluetoothGattCharacteristic, 1);
    }

    @Test
    public void testOnCharacteristicWrite_notCrash() {
        mTestBluetoothGattCallback.unwrap().onCharacteristicWrite(mBluetoothGatt,
                mBluetoothGattCharacteristic, 1);
    }

    @Test
    public void testOnDescriptionRead_notCrash() {
        mTestBluetoothGattCallback.unwrap().onDescriptorRead(mBluetoothGatt,
                mBluetoothGattDescriptor, 1);
    }

    @Test
    public void testOnDescriptionWrite_notCrash() {
        mTestBluetoothGattCallback.unwrap().onDescriptorWrite(mBluetoothGatt,
                mBluetoothGattDescriptor, 1);
    }

    @Test
    public void testOnReadRemoteRssi_notCrash() {
        mTestBluetoothGattCallback.unwrap().onReadRemoteRssi(mBluetoothGatt, 1, 1);
    }

    @Test
    public void testOnReliableWriteCompleted_notCrash() {
        mTestBluetoothGattCallback.unwrap().onReliableWriteCompleted(mBluetoothGatt, 1);
    }

    @Test
    public void testOnMtuChanged_notCrash() {
        mTestBluetoothGattCallback.unwrap().onMtuChanged(mBluetoothGatt, 1, 1);
    }

    @Test
    public void testOnCharacteristicChanged_notCrash() {
        mTestBluetoothGattCallback.unwrap()
                .onCharacteristicChanged(mBluetoothGatt, mBluetoothGattCharacteristic);
    }

    private static class TestBluetoothGattCallback extends BluetoothGattCallback { }
}
