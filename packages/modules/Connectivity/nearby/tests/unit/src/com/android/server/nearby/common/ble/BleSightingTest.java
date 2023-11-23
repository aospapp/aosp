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

package com.android.server.nearby.common.ble;

import static com.google.common.truth.Truth.assertThat;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Parcel;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/** Test for Bluetooth LE {@link BleSighting}. */
public class BleSightingTest {
    private static final String DEVICE_NAME = "device1";
    private static final String OTHER_DEVICE_NAME = "device2";
    private static final long TIME_EPOCH_MILLIS = 123456;
    private static final long OTHER_TIME_EPOCH_MILLIS = 456789;
    private static final int RSSI = 1;
    private static final int OTHER_RSSI = 2;

    private final BluetoothDevice mBluetoothDevice1 =
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
    private final BluetoothDevice mBluetoothDevice2 =
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:FF");


    @Test
    public void testEquals() {
        BleSighting sighting =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        BleSighting sighting2 =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertThat(sighting.equals(sighting2)).isTrue();
        assertThat(sighting2.equals(sighting)).isTrue();
        assertThat(sighting.hashCode()).isEqualTo(sighting2.hashCode());

        // Transitive property.
        BleSighting sighting3 =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertThat(sighting2.equals(sighting3)).isTrue();
        assertThat(sighting.equals(sighting3)).isTrue();

        // Set different values for each field, one at a time.
        sighting2 = buildBleSighting(mBluetoothDevice2, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertSightingsNotEquals(sighting, sighting2);

        sighting2 = buildBleSighting(mBluetoothDevice1, OTHER_DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertSightingsNotEquals(sighting, sighting2);

        sighting2 = buildBleSighting(mBluetoothDevice1, DEVICE_NAME, OTHER_TIME_EPOCH_MILLIS, RSSI);
        assertSightingsNotEquals(sighting, sighting2);

        sighting2 = buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, OTHER_RSSI);
        assertSightingsNotEquals(sighting, sighting2);
    }

    @Test
    public void getNormalizedRSSI_usingNearbyRssiOffset_getCorrectValue() {
        BleSighting sighting =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);

        int defaultRssiOffset = 3;
        assertThat(sighting.getNormalizedRSSI()).isEqualTo(RSSI + defaultRssiOffset);
    }

    @Test
    public void testFields() {
        BleSighting sighting =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertThat(byteString(sighting.getBleRecordBytes()))
                .isEqualTo("080964657669636531");
        assertThat(sighting.getRssi()).isEqualTo(RSSI);
        assertThat(sighting.getTimestampMillis()).isEqualTo(TIME_EPOCH_MILLIS);
        assertThat(sighting.getTimestampNanos())
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(TIME_EPOCH_MILLIS));
        assertThat(sighting.toString()).isEqualTo(
                "BleSighting{device=" + mBluetoothDevice1 + ","
                        + " bleRecord=BleRecord [advertiseFlags=-1,"
                        + " serviceUuids=[],"
                        + " manufacturerSpecificData={}, serviceData={},"
                        + " txPowerLevel=-2147483648,"
                        + " deviceName=device1],"
                        + " rssi=1,"
                        + " timestampNanos=123456000000}");
    }

    @Test
    public void testParcelable() {
        BleSighting sighting =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        Parcel dest = Parcel.obtain();
        sighting.writeToParcel(dest, 0);
        dest.setDataPosition(0);
        assertThat(sighting.getRssi()).isEqualTo(RSSI);
    }

    @Test
    public void testCreatorNewArray() {
        BleSighting[]  sightings =
                BleSighting.CREATOR.newArray(2);
        assertThat(sightings.length).isEqualTo(2);
    }

    private static String byteString(byte[] bytes) {
        if (bytes == null) {
            return "[null]";
        } else {
            final char[] hexArray = "0123456789ABCDEF".toCharArray();
            char[] hexChars = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                hexChars[i * 2] = hexArray[v >>> 4];
                hexChars[i * 2 + 1] = hexArray[v & 0x0F];
            }
            return new String(hexChars);
        }
    }

        /** Builds a BleSighting instance which will correctly match filters by device name. */
    private static BleSighting buildBleSighting(
            BluetoothDevice bluetoothDevice, String deviceName, long timeEpochMillis, int rssi) {
        byte[] nameBytes = deviceName.getBytes(UTF_8);
        byte[] bleRecordBytes = new byte[nameBytes.length + 2];
        bleRecordBytes[0] = (byte) (nameBytes.length + 1);
        bleRecordBytes[1] = 0x09; // Value of private BleRecord.DATA_TYPE_LOCAL_NAME_COMPLETE;
        System.arraycopy(nameBytes, 0, bleRecordBytes, 2, nameBytes.length);

        return new BleSighting(bluetoothDevice, bleRecordBytes,
                rssi, TimeUnit.MILLISECONDS.toNanos(timeEpochMillis));
    }

    private static void assertSightingsNotEquals(BleSighting sighting1, BleSighting sighting2) {
        assertThat(sighting1.equals(sighting2)).isFalse();
        assertThat(sighting1.hashCode()).isNotEqualTo(sighting2.hashCode());
    }
}
