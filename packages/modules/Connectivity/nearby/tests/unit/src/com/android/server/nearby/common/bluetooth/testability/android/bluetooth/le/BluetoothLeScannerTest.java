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
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link BluetoothLeScanner}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothLeScannerTest {
    @Mock android.bluetooth.le.BluetoothLeScanner mWrappedBluetoothLeScanner;
    @Mock PendingIntent mPendingIntent;
    @Mock ScanSettings mScanSettings;
    @Mock ScanFilter mScanFilter;

    TestScanCallback mTestScanCallback = new TestScanCallback();
    BluetoothLeScanner mBluetoothLeScanner;
    ImmutableList<ScanFilter> mImmutableScanFilterList;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mBluetoothLeScanner = BluetoothLeScanner.wrap(mWrappedBluetoothLeScanner);
        mImmutableScanFilterList = ImmutableList.of(mScanFilter);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNullAdapter_isNull() {
        assertThat(BluetoothLeAdvertiser.wrap(null)).isNull();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testWrapNonNullAdapter_isNotNull_unWrapSame() {
        assertThat(mWrappedBluetoothLeScanner).isNotNull();
        assertThat(mBluetoothLeScanner.unwrap()).isSameInstanceAs(mWrappedBluetoothLeScanner);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStartScan_callsWrapped() {
        doNothing().when(mWrappedBluetoothLeScanner).startScan(mTestScanCallback.unwrap());
        mBluetoothLeScanner.startScan(mTestScanCallback);
        verify(mWrappedBluetoothLeScanner).startScan(mTestScanCallback.unwrap());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStartScanWithFiltersCallback_callsWrapped() {
        doNothing().when(mWrappedBluetoothLeScanner)
                .startScan(mImmutableScanFilterList, mScanSettings, mTestScanCallback.unwrap());
        mBluetoothLeScanner.startScan(mImmutableScanFilterList, mScanSettings, mTestScanCallback);
        verify(mWrappedBluetoothLeScanner)
                .startScan(mImmutableScanFilterList, mScanSettings, mTestScanCallback.unwrap());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStartScanWithFiltersCallbackIntent_callsWrapped() {
        when(mWrappedBluetoothLeScanner.startScan(
                mImmutableScanFilterList, mScanSettings, mPendingIntent)).thenReturn(1);
        mBluetoothLeScanner.startScan(mImmutableScanFilterList, mScanSettings, mPendingIntent);
        verify(mWrappedBluetoothLeScanner)
                .startScan(mImmutableScanFilterList, mScanSettings, mPendingIntent);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStopScan_callsWrapped() {
        doNothing().when(mWrappedBluetoothLeScanner).stopScan(mTestScanCallback.unwrap());
        mBluetoothLeScanner.stopScan(mTestScanCallback);
        verify(mWrappedBluetoothLeScanner).stopScan(mTestScanCallback.unwrap());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStopScanPendingIntent_callsWrapped() {
        doNothing().when(mWrappedBluetoothLeScanner).stopScan(mPendingIntent);
        mBluetoothLeScanner.stopScan(mPendingIntent);
        verify(mWrappedBluetoothLeScanner).stopScan(mPendingIntent);
    }

    private static class TestScanCallback extends ScanCallback {};
}
