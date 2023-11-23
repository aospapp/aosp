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

package com.android.server.nearby.common.ble.util;

import static com.google.common.truth.Truth.assertThat;

import android.os.ParcelUuid;
import android.util.SparseArray;

import com.android.server.nearby.common.ble.BleRecord;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class StringUtilsTest {
    // iBeacon (Apple) Packet 1
    private static final byte[] BEACON = {
            // Flags
            (byte) 0x02,
            (byte) 0x01,
            (byte) 0x06,
            // Manufacturer-specific data header
            (byte) 0x1a,
            (byte) 0xff,
            (byte) 0x4c,
            (byte) 0x00,
            // iBeacon Type
            (byte) 0x02,
            // Frame length
            (byte) 0x15,
            // iBeacon Proximity UUID
            (byte) 0xf7,
            (byte) 0x82,
            (byte) 0x6d,
            (byte) 0xa6,
            (byte) 0x4f,
            (byte) 0xa2,
            (byte) 0x4e,
            (byte) 0x98,
            (byte) 0x80,
            (byte) 0x24,
            (byte) 0xbc,
            (byte) 0x5b,
            (byte) 0x71,
            (byte) 0xe0,
            (byte) 0x89,
            (byte) 0x3e,
            // iBeacon Instance ID (Major/Minor)
            (byte) 0x44,
            (byte) 0xd0,
            (byte) 0x25,
            (byte) 0x22,
            // Tx Power
            (byte) 0xb3,
            // RSP
            (byte) 0x08,
            (byte) 0x09,
            (byte) 0x4b,
            (byte) 0x6f,
            (byte) 0x6e,
            (byte) 0x74,
            (byte) 0x61,
            (byte) 0x6b,
            (byte) 0x74,
            (byte) 0x02,
            (byte) 0x0a,
            (byte) 0xf4,
            (byte) 0x0a,
            (byte) 0x16,
            (byte) 0x0d,
            (byte) 0xd0,
            (byte) 0x74,
            (byte) 0x6d,
            (byte) 0x4d,
            (byte) 0x6b,
            (byte) 0x32,
            (byte) 0x36,
            (byte) 0x64,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00
    };

    @Test
    public void testToString() {
        BleRecord record = BleRecord.parseFromBytes(BEACON);
        assertThat(StringUtils.toString((SparseArray<byte[]>) null)).isEqualTo("null");
        assertThat(StringUtils.toString(new SparseArray<byte[]>())).isEqualTo("{}");
        assertThat(StringUtils
                .toString((Map<ParcelUuid, byte[]>) null)).isEqualTo("null");
        assertThat(StringUtils
                .toString(new HashMap<ParcelUuid, byte[]>())).isEqualTo("{}");
        assertThat(StringUtils.toString(record.getManufacturerSpecificData()))
                .isEqualTo("{76=[2, 21, -9, -126, 109, -90, 79, -94, 78, -104, -128,"
                        + " 36, -68, 91, 113, -32, -119, 62, 68, -48, 37, 34, -77]}");
        assertThat(StringUtils.toString(record.getServiceData()))
                .isEqualTo("{0000d00d-0000-1000-8000-00805f9b34fb="
                        + "[116, 109, 77, 107, 50, 54, 100]}");
    }
}
