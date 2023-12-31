/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.bluetooth.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.CddTest;
import com.android.internal.util.ArrayUtils;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit test cases for {@link ScanRecord}.
 * <p>
 * To run this test, use adb shell am instrument -e class 'android.bluetooth.ScanRecordTest' -w
 * 'com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner'
 */
@RunWith(AndroidJUnit4.class)
public class ScanRecordTest {

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @SmallTest
    @Test
    public void testParser() {
        Assume.assumeTrue(TestUtils.isBleSupported(
                InstrumentationRegistry.getInstrumentation().getContext()));

        byte[] partialScanRecord = new byte[] {
                0x02, 0x01, 0x1a, // advertising flags
                0x05, 0x02, 0x0b, 0x11, 0x0a, 0x11, // 16 bit service uuids
                0x04, 0x09, 0x50, 0x65, 0x64, // name
                0x02, 0x0A, (byte) 0xec, // tx power level
                0x05, 0x16, 0x0b, 0x11, 0x50, 0x64, // service data
                0x05, (byte) 0xff, (byte) 0xe0, 0x00, 0x02, 0x15, // manufacturer specific data
                0x05, 0x14, 0x0c, 0x11, 0x0d, 0x11, // 16 bit service solicitation uuids
                0x03, 0x50, 0x01, 0x02, // an unknown data type won't cause trouble
        };

        final byte[] tdsData = new byte[] {
            ScanRecord.DATA_TYPE_TRANSPORT_DISCOVERY_DATA, 0x42, 0x43, 0x02 /* len */, 0x08, 0x09
        };
        final byte[] tdsDataLengh = new byte[] { (byte) tdsData.length };

        byte[] scanRecord = ArrayUtils.concat(partialScanRecord, tdsDataLengh, tdsData);

        ScanRecord data = TestUtils.parseScanRecord(scanRecord);
        assertEquals(0x1a, data.getAdvertiseFlags());
        ParcelUuid uuid1 = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid2 = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid3 = ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid4 = ParcelUuid.fromString("0000110D-0000-1000-8000-00805F9B34FB");
        assertTrue(data.getServiceUuids().contains(uuid1));
        assertTrue(data.getServiceUuids().contains(uuid2));
        assertFalse(data.getServiceUuids().contains(uuid3));
        assertFalse(data.getServiceUuids().contains(uuid4));
        assertFalse(data.getServiceSolicitationUuids().contains(uuid1));
        assertFalse(data.getServiceSolicitationUuids().contains(uuid2));
        assertTrue(data.getServiceSolicitationUuids().contains(uuid3));
        assertTrue(data.getServiceSolicitationUuids().contains(uuid4));

        TestUtils.assertArrayEquals(data.getTransportDiscoveryData().toByteArray(), tdsData);

        assertEquals("Ped", data.getDeviceName());
        assertEquals(-20, data.getTxPowerLevel());

        assertTrue(data.getManufacturerSpecificData().get(0x00E0) != null);

        final byte[] manufacturerData = new byte[] {
                0x02, 0x15 };
        TestUtils.assertArrayEquals(manufacturerData,
                data.getManufacturerSpecificData().get(0x00E0));
        TestUtils.assertArrayEquals(manufacturerData, data.getManufacturerSpecificData(0x00E0));

        assertTrue(data.getServiceData().containsKey(uuid2));
        final byte[] serviceData = new byte[] {
                0x50, 0x64 };
        TestUtils.assertArrayEquals(serviceData, data.getServiceData().get(uuid2));
        TestUtils.assertArrayEquals(serviceData, data.getServiceData(uuid2));

        final byte[] adData = new byte[] {0x01, 0x02};
        TestUtils.assertArrayEquals(adData, data.getAdvertisingDataMap().get(0x50));
    }
}
