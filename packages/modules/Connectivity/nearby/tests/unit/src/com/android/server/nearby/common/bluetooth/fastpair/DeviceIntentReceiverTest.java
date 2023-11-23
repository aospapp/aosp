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

package com.android.server.nearby.common.bluetooth.fastpair;

import static org.mockito.MockitoAnnotations.initMocks;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

import org.mockito.Mock;

/**
 * Unit tests for {@link DeviceIntentReceiver}.
 */
public class DeviceIntentReceiverTest extends TestCase {
    @Mock Preferences mPreferences;
    @Mock BluetoothDevice mBluetoothDevice;

    private DeviceIntentReceiver mDeviceIntentReceiver;
    private Intent mIntent;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        initMocks(this);

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mDeviceIntentReceiver = DeviceIntentReceiver.oneShotReceiver(
                context, mPreferences, mBluetoothDevice);

        mIntent = new Intent().putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);
    }

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void test_onReceive_notCrash() throws Exception {
        mDeviceIntentReceiver.onReceive(mIntent);
    }
}
