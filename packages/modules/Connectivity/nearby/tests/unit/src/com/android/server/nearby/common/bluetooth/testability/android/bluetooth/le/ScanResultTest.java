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

import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ScanResult}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScanResultTest {

    @Mock android.bluetooth.le.ScanResult mWrappedScanResult;
    @Mock android.bluetooth.le.ScanRecord mScanRecord;
    @Mock android.bluetooth.BluetoothDevice mBluetoothDevice;
    ScanResult mScanResult;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mScanResult = ScanResult.wrap(mWrappedScanResult);
    }

    @Test
    public void testGetScanRecord_calledWrapped() {
        when(mWrappedScanResult.getScanRecord()).thenReturn(mScanRecord);
        assertThat(mScanResult.getScanRecord()).isSameInstanceAs(mScanRecord);
    }

    @Test
    public void testGetRssi_calledWrapped() {
        when(mWrappedScanResult.getRssi()).thenReturn(3);
        assertThat(mScanResult.getRssi()).isEqualTo(3);
    }

    @Test
    public void testGetTimestampNanos_calledWrapped() {
        when(mWrappedScanResult.getTimestampNanos()).thenReturn(4L);
        assertThat(mScanResult.getTimestampNanos()).isEqualTo(4L);
    }

    @Test
    public void testGetDevice_calledWrapped() {
        when(mWrappedScanResult.getDevice()).thenReturn(mBluetoothDevice);
        assertThat(mScanResult.getDevice().unwrap()).isSameInstanceAs(mBluetoothDevice);
    }
}
