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

package com.android.server.nearby.common.ble.decode;

import static com.android.server.nearby.common.ble.BleRecord.parseFromBytes;
import static com.android.server.nearby.common.ble.testing.FastPairTestData.DEVICE_ADDRESS;
import static com.android.server.nearby.common.ble.testing.FastPairTestData.FAST_PAIR_MODEL_ID;
import static com.android.server.nearby.common.ble.testing.FastPairTestData.FAST_PAIR_SHARED_ACCOUNT_KEY_RECORD;
import static com.android.server.nearby.common.ble.testing.FastPairTestData.RSSI;
import static com.android.server.nearby.common.ble.testing.FastPairTestData.getFastPairRecord;
import static com.android.server.nearby.common.ble.testing.FastPairTestData.newFastPairRecord;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.nearby.common.ble.BleRecord;
import com.android.server.nearby.common.ble.BleSighting;
import com.android.server.nearby.common.ble.testing.FastPairTestData;
import com.android.server.nearby.util.Hex;

import com.google.common.primitives.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class FastPairDecoderTest {
    private static final String LONG_MODEL_ID = "1122334455667788";
    // Bits 3-6 are model ID length bits = 0b1000 = 8
    private static final byte LONG_MODEL_ID_HEADER = 0b00010000;
    private static final String PADDED_LONG_MODEL_ID = "00001111";
    // Bits 3-6 are model ID length bits = 0b0100 = 4
    private static final byte PADDED_LONG_MODEL_ID_HEADER = 0b00001000;
    private static final String TRIMMED_LONG_MODEL_ID = "001111";
    private static final byte MODEL_ID_HEADER = 0b00000110;
    private static final String MODEL_ID = "112233";
    private static final byte BLOOM_FILTER_HEADER = 0b01100000;
    private static final byte BLOOM_FILTER_NO_NOTIFICATION_HEADER = 0b01100010;
    private static final String BLOOM_FILTER = "112233445566";
    private static final byte LONG_BLOOM_FILTER_HEADER = (byte) 0b10100000;
    private static final String LONG_BLOOM_FILTER = "00112233445566778899";
    private static final byte BLOOM_FILTER_SALT_HEADER = 0b00010001;
    private static final String BLOOM_FILTER_SALT = "01";
    private static final byte BATTERY_HEADER = 0b00110011;
    private static final byte BATTERY_NO_NOTIFICATION_HEADER = 0b00110100;
    private static final String BATTERY = "01048F";
    private static final byte RANDOM_RESOLVABLE_DATA_HEADER = 0b01000110;
    private static final String RANDOM_RESOLVABLE_DATA = "11223344";

    private final FastPairDecoder mDecoder = new FastPairDecoder();
    private final BluetoothDevice mBluetoothDevice =
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice(DEVICE_ADDRESS);

    @Test
    public void filter() {
        assertThat(FastPairDecoder.FILTER.matches(bleSighting(getFastPairRecord()))).isTrue();
        assertThat(FastPairDecoder.FILTER.matches(bleSighting(FAST_PAIR_SHARED_ACCOUNT_KEY_RECORD)))
                .isTrue();

        // Any ID is a valid frame.
        assertThat(FastPairDecoder.FILTER.matches(
                bleSighting(newFastPairRecord(Hex.stringToBytes("000001"))))).isTrue();
        assertThat(FastPairDecoder.FILTER.matches(
                bleSighting(newFastPairRecord(Hex.stringToBytes("098FEC"))))).isTrue();
        assertThat(FastPairDecoder.FILTER.matches(
                bleSighting(FastPairTestData.newFastPairRecord(
                        LONG_MODEL_ID_HEADER, Hex.stringToBytes(LONG_MODEL_ID))))).isTrue();
    }

    @Test
    public void getModelId() {
        assertThat(mDecoder.getBeaconIdBytes(parseFromBytes(getFastPairRecord())))
                .isEqualTo(FAST_PAIR_MODEL_ID);
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(LONG_MODEL_ID_HEADER, LONG_MODEL_ID);
        assertThat(
                mDecoder.getBeaconIdBytes(
                        newBleRecord(fastPairServiceData.createServiceData())))
                .isEqualTo(Hex.stringToBytes(LONG_MODEL_ID));

        FastPairServiceData fastPairServiceData1 =
                new FastPairServiceData(PADDED_LONG_MODEL_ID_HEADER, PADDED_LONG_MODEL_ID);
        assertThat(
                mDecoder.getBeaconIdBytes(
                        newBleRecord(fastPairServiceData1.createServiceData())))
                .isEqualTo(Hex.stringToBytes(TRIMMED_LONG_MODEL_ID));
    }

    @Test
    public void getBeaconIdType() {
        assertThat(mDecoder.getBeaconIdType()).isEqualTo(1);
    }

    @Test
    public void getCalibratedBeaconTxPower() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(LONG_MODEL_ID_HEADER, LONG_MODEL_ID);
        assertThat(
                mDecoder.getCalibratedBeaconTxPower(
                        newBleRecord(fastPairServiceData.createServiceData())))
                .isNull();
    }

    @Test
    public void getServiceDataArray() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(LONG_MODEL_ID_HEADER, LONG_MODEL_ID);
        assertThat(
                mDecoder.getServiceDataArray(
                        newBleRecord(fastPairServiceData.createServiceData())))
                .isEqualTo(Hex.stringToBytes("101122334455667788"));
    }

    @Test
    public void hasBloomFilter() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(LONG_MODEL_ID_HEADER, LONG_MODEL_ID);
        assertThat(
                mDecoder.hasBloomFilter(
                        newBleRecord(fastPairServiceData.createServiceData())))
                .isFalse();
    }

    @Test
    public void hasModelId_allCases() {
        // One type of the format is just the 3-byte model ID. This format has no header byte (all 3
        // service data bytes are the model ID in little endian).
        assertThat(hasModelId("112233", mDecoder)).isTrue();

        // If the model ID is shorter than 3 bytes, then return null.
        assertThat(hasModelId("11", mDecoder)).isFalse();

        // If the data is longer than 3 bytes,
        // byte 0 must be 0bVVVLLLLR (version, ID length, reserved).
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData((byte) 0b00001000, "11223344");
        assertThat(
                FastPairDecoder.hasBeaconIdBytes(
                        newBleRecord(fastPairServiceData.createServiceData()))).isTrue();

        FastPairServiceData fastPairServiceData1 =
                new FastPairServiceData((byte) 0b00001010, "1122334455");
        assertThat(
                FastPairDecoder.hasBeaconIdBytes(
                        newBleRecord(fastPairServiceData1.createServiceData()))).isTrue();

        // Length bits correct, but version bits != version 0 (only supported version).
        FastPairServiceData fastPairServiceData2 =
                new FastPairServiceData((byte) 0b00101000, "11223344");
        assertThat(
                FastPairDecoder.hasBeaconIdBytes(
                        newBleRecord(fastPairServiceData2.createServiceData()))).isFalse();

        // Version bits correct, but length bits incorrect (too big, too small).
        FastPairServiceData fastPairServiceData3 =
                new FastPairServiceData((byte) 0b00001010, "11223344");
        assertThat(
                FastPairDecoder.hasBeaconIdBytes(
                        newBleRecord(fastPairServiceData3.createServiceData()))).isFalse();

        FastPairServiceData fastPairServiceData4 =
                new FastPairServiceData((byte) 0b00000010, "11223344");
        assertThat(
                FastPairDecoder.hasBeaconIdBytes(
                        newBleRecord(fastPairServiceData4.createServiceData()))).isFalse();
    }

    @Test
    public void getBatteryLevel() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BATTERY_HEADER);
        fastPairServiceData.mExtraFields.add(BATTERY);
        assertThat(
                FastPairDecoder.getBatteryLevel(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BATTERY));
    }

    @Test
    public void getBatteryLevel_notIncludedInPacket() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBatteryLevel(fastPairServiceData.createServiceData())).isNull();
    }

    @Test
    public void getBatteryLevel_noModelId() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData((byte) 0b00000000, null);
        fastPairServiceData.mExtraFieldHeaders.add(BATTERY_HEADER);
        fastPairServiceData.mExtraFields.add(BATTERY);
        assertThat(
                FastPairDecoder.getBatteryLevel(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BATTERY));
    }

    @Test
    public void getBatteryLevel_multipelExtraField() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BATTERY_HEADER);
        fastPairServiceData.mExtraFields.add(BATTERY);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBatteryLevel(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BATTERY));
    }

    @Test
    public void getBatteryLevelNoNotification() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BATTERY_NO_NOTIFICATION_HEADER);
        fastPairServiceData.mExtraFields.add(BATTERY);
        assertThat(
                FastPairDecoder.getBatteryLevelNoNotification(
                        fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BATTERY));
    }

    @Test
    public void getBatteryLevelNoNotification_notIncludedInPacket() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBatteryLevelNoNotification(
                        fastPairServiceData.createServiceData())).isNull();
    }

    @Test
    public void getBatteryLevelNoNotification_noModelId() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData((byte) 0b00000000, null);
        fastPairServiceData.mExtraFieldHeaders.add(BATTERY_NO_NOTIFICATION_HEADER);
        fastPairServiceData.mExtraFields.add(BATTERY);
        assertThat(
                FastPairDecoder.getBatteryLevelNoNotification(
                        fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BATTERY));
    }

    @Test
    public void getBatteryLevelNoNotification_multipleExtraField() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BATTERY_NO_NOTIFICATION_HEADER);
        fastPairServiceData.mExtraFields.add(BATTERY);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBatteryLevelNoNotification(
                        fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BATTERY));
    }

    @Test
    public void getBloomFilter() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER));
    }

    @Test
    public void getBloomFilterNoNotification() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_NO_NOTIFICATION_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBloomFilterNoNotification(
                        fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER));
    }

    @Test
    public void getBloomFilter_smallModelId() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(null, MODEL_ID);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isNull();
    }

    @Test
    public void getBloomFilter_headerVersionBitsNotZero() {
        // Header bits are defined as 0bVVVLLLLR (V=version, L=length, R=reserved), must be zero.
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData((byte) 0b00100000, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isNull();
    }

    @Test
    public void getBloomFilter_noExtraFieldBytesIncluded() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(null);
        fastPairServiceData.mExtraFields.add(null);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isNull();
    }

    @Test
    public void getBloomFilter_extraFieldLengthIsZero() {
        // The extra field header is formatted as 0bLLLLTTTT (L=length, T=type).
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b00000000);
        fastPairServiceData.mExtraFields.add(null);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .hasLength(0);
    }

    @Test
    public void getBloomFilter_extraFieldLengthLongerThanPacket() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b11110000);
        fastPairServiceData.mExtraFields.add("1122");
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isNull();
    }

    @Test
    public void getBloomFilter_secondExtraFieldLengthLongerThanPacket() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b00100000);
        fastPairServiceData.mExtraFields.add("1122");
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b11110001);
        fastPairServiceData.mExtraFields.add("3344");
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData())).isNull();
    }

    @Test
    public void getBloomFilter_typeIsWrong() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b01100001);
        fastPairServiceData.mExtraFields.add("112233445566");
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isNull();
    }

    @Test
    public void getBloomFilter_noModelId() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData((byte) 0b00000000, null);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER));
    }

    @Test
    public void getBloomFilter_noModelIdAndMultipleExtraFields() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData((byte) 0b00000000, null);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b00010001);
        fastPairServiceData.mExtraFields.add("00");
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER));
    }

    @Test
    public void getBloomFilter_modelIdAndMultipleExtraFields() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_SALT_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER_SALT);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER));
    }

    @Test
    public void getBloomFilterSalt_modelIdAndMultipleExtraFields() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_SALT_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER_SALT);
        assertThat(
                FastPairDecoder.getBloomFilterSalt(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER_SALT));
    }

    @Test
    public void getBloomFilter_modelIdAndMultipleExtraFieldsWithBloomFilterLast() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b00010001);
        fastPairServiceData.mExtraFields.add("1A");
        fastPairServiceData.mExtraFieldHeaders.add((byte) 0b00100010);
        fastPairServiceData.mExtraFields.add("2CFE");
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER));
    }

    @Test
    public void getBloomFilter_modelIdAndMultipleExtraFieldsWithSameType() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(BLOOM_FILTER);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add("000000000000");
        assertThat(
                FastPairDecoder.getBloomFilter(fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(BLOOM_FILTER));
    }

    @Test
    public void getBloomFilter_longExtraField() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(LONG_BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add(LONG_BLOOM_FILTER);
        fastPairServiceData.mExtraFieldHeaders.add(BLOOM_FILTER_HEADER);
        fastPairServiceData.mExtraFields.add("000000000000");
        assertThat(
                FastPairDecoder.getBloomFilter(
                        fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(LONG_BLOOM_FILTER));
    }

    @Test
    public void getRandomResolvableData_whenNoConnectionState() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        assertThat(
                FastPairDecoder.getRandomResolvableData(
                        fastPairServiceData.createServiceData()))
                .isEqualTo(null);
    }

    @Test
    public void getRandomResolvableData_whenContainConnectionState() {
        FastPairServiceData fastPairServiceData =
                new FastPairServiceData(MODEL_ID_HEADER, MODEL_ID);
        fastPairServiceData.mExtraFieldHeaders.add(RANDOM_RESOLVABLE_DATA_HEADER);
        fastPairServiceData.mExtraFields.add(RANDOM_RESOLVABLE_DATA);
        assertThat(
                FastPairDecoder.getRandomResolvableData(
                        fastPairServiceData.createServiceData()))
                .isEqualTo(Hex.stringToBytes(RANDOM_RESOLVABLE_DATA));
    }

    private static BleRecord newBleRecord(byte[] serviceDataBytes) {
        return parseFromBytes(newFastPairRecord(serviceDataBytes));
    }

    private static boolean hasModelId(String modelId, FastPairDecoder decoder) {
        byte[] modelIdBytes = Hex.stringToBytes(modelId);
        BleRecord bleRecord =
                parseFromBytes(FastPairTestData.newFastPairRecord((byte) 0, modelIdBytes));
        return FastPairDecoder.hasBeaconIdBytes(bleRecord)
                && Arrays.equals(decoder.getBeaconIdBytes(bleRecord), modelIdBytes);
    }

    private BleSighting bleSighting(byte[] frame) {
        return new BleSighting(mBluetoothDevice, frame, RSSI,
                TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()));
    }

    static class FastPairServiceData {
        private Byte mHeader;
        private String mModelId;
        List<Byte> mExtraFieldHeaders = new ArrayList<>();
        List<String> mExtraFields = new ArrayList<>();

        FastPairServiceData(Byte header, String modelId) {
            this.mHeader = header;
            this.mModelId = modelId;
        }
        private byte[] createServiceData() {
            if (mExtraFieldHeaders.size() != mExtraFields.size()) {
                throw new RuntimeException("Number of headers and extra fields must match.");
            }
            byte[] serviceData =
                    Bytes.concat(
                            mHeader == null ? new byte[0] : new byte[] {mHeader},
                            mModelId == null ? new byte[0] : Hex.stringToBytes(mModelId));
            for (int i = 0; i < mExtraFieldHeaders.size(); i++) {
                serviceData =
                        Bytes.concat(
                                serviceData,
                                mExtraFieldHeaders.get(i) != null
                                        ? new byte[] {mExtraFieldHeaders.get(i)}
                                        : new byte[0],
                                mExtraFields.get(i) != null
                                        ? Hex.stringToBytes(mExtraFields.get(i))
                                        : new byte[0]);
            }
            return serviceData;
        }
    }
}
