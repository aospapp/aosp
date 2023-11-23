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

package com.android.server.nearby.common.bluetooth.testability.android.bluetooth.le;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
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
 * Unit tests for {@link BluetoothLeAdvertiser}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothAdvertiserTest {
    @Mock android.bluetooth.le.BluetoothLeAdvertiser mWrappedBluetoothLeAdvertiser;
    @Mock AdvertiseSettings mAdvertiseSettings;
    @Mock AdvertiseData mAdvertiseData;
    @Mock AdvertiseCallback mAdvertiseCallback;

    BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mBluetoothLeAdvertiser = BluetoothLeAdvertiser.wrap(mWrappedBluetoothLeAdvertiser);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNullAdapter_isNull() {
        assertThat(BluetoothLeAdvertiser.wrap(null)).isNull();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNonNullAdapter_isNotNull_unWrapSame() {
        assertThat(mWrappedBluetoothLeAdvertiser).isNotNull();
        assertThat(mBluetoothLeAdvertiser.unwrap()).isSameInstanceAs(mWrappedBluetoothLeAdvertiser);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStartAdvertisingThreeParameters_callsWrapped() {
        doNothing().when(mWrappedBluetoothLeAdvertiser)
                .startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback);
        mBluetoothLeAdvertiser
                .startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback);
        verify(mWrappedBluetoothLeAdvertiser).startAdvertising(
                mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStartAdvertisingFourParameters_callsWrapped() {
        doNothing().when(mWrappedBluetoothLeAdvertiser).startAdvertising(
                mAdvertiseSettings, mAdvertiseData, mAdvertiseData, mAdvertiseCallback);
        mBluetoothLeAdvertiser.startAdvertising(
                mAdvertiseSettings, mAdvertiseData, mAdvertiseData, mAdvertiseCallback);
        verify(mWrappedBluetoothLeAdvertiser).startAdvertising(
                mAdvertiseSettings, mAdvertiseData, mAdvertiseData, mAdvertiseCallback);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStopAdvertising_callsWrapped() {
        doNothing().when(mWrappedBluetoothLeAdvertiser).stopAdvertising(mAdvertiseCallback);
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
        verify(mWrappedBluetoothLeAdvertiser).stopAdvertising(mAdvertiseCallback);
    }
}
