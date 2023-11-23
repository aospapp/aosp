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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class BluetoothClassicPairerTest {
    @Mock
    PasskeyConfirmationHandler mPasskeyConfirmationHandler;
    private static final BluetoothDevice BLUETOOTH_DEVICE = BluetoothAdapter.getDefaultAdapter()
            .getRemoteDevice("11:22:33:44:55:66");
    private  BluetoothClassicPairer mBluetoothClassicPairer;

    @Before
    public void setUp() {
        mBluetoothClassicPairer = new BluetoothClassicPairer(
                ApplicationProvider.getApplicationContext(),
                BLUETOOTH_DEVICE,
                Preferences.builder().build(),
                mPasskeyConfirmationHandler);
    }

    @Test
    public void pair() throws PairingException {
        PairingException exception =
                assertThrows(
                        PairingException.class,
                        () -> mBluetoothClassicPairer.pair());

        assertThat(exception)
                .hasMessageThat()
                .contains("BluetoothClassicPairer, createBond");
        assertThat(mBluetoothClassicPairer.isPaired()).isFalse();
    }
}
