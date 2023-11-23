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

package android.bluetooth.cts;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothStatusCodes.FEATURE_SUPPORTED;
import static android.bluetooth.le.DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI;
import static android.bluetooth.le.DistanceMeasurementParams.REPORT_FREQUENCY_HIGH;
import static android.bluetooth.le.DistanceMeasurementParams.REPORT_FREQUENCY_LOW;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.DistanceMeasurementParams;
import android.content.Context;
import android.os.Build;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiLevelUtil;
import com.android.compatibility.common.util.CddTest;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DistanceMeasurementParamsTest {
    private Context mContext;
    private BluetoothAdapter mAdapter;
    private BluetoothDevice mDevice;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        Assume.assumeTrue(ApiLevelUtil.isAtLeast(Build.VERSION_CODES.TIRAMISU));
        Assume.assumeTrue(TestUtils.isBleSupported(mContext));

        mAdapter = TestUtils.getBluetoothAdapterOrDie();
        assertTrue(BTAdapterUtils.enableAdapter(mAdapter, mContext));
        TestUtils.adoptPermissionAsShellUid(BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED);
        Assume.assumeTrue(mAdapter.isDistanceMeasurementSupported() == FEATURE_SUPPORTED);

        mDevice = mAdapter.getRemoteDevice("11:22:33:44:55:66");
    }

    @After
    public void tearDown() {
        TestUtils.dropPermissionAsShellUid();
        mAdapter = null;
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testCreateFromParcel() {
        final Parcel parcel = Parcel.obtain();
        try {
            DistanceMeasurementParams params = new DistanceMeasurementParams
                    .Builder(mDevice).build();
            params.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            DistanceMeasurementParams paramsFromParcel =
                    DistanceMeasurementParams.CREATOR.createFromParcel(parcel);
            assertParamsEquals(params, paramsFromParcel);
        } finally {
            parcel.recycle();
        }
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testDefaultParameters() {
        DistanceMeasurementParams params = new DistanceMeasurementParams.Builder(mDevice).build();
        assertEquals(DistanceMeasurementParams.getDefaultDurationSeconds(),
                params.getDurationSeconds());
        assertEquals(REPORT_FREQUENCY_LOW, params.getFrequency());
        assertEquals(DISTANCE_MEASUREMENT_METHOD_RSSI, params.getMethodId());
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetDevice() {
        DistanceMeasurementParams params = new DistanceMeasurementParams.Builder(mDevice).build();
        assertEquals(mDevice, params.getDevice());
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetDurationSeconds() {
        DistanceMeasurementParams params = new DistanceMeasurementParams.Builder(mDevice)
                .setDurationSeconds(120).build();
        assertEquals(120, params.getDurationSeconds());
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetFrequency() {
        DistanceMeasurementParams params = new DistanceMeasurementParams.Builder(mDevice)
                .setFrequency(REPORT_FREQUENCY_HIGH).build();
        assertEquals(REPORT_FREQUENCY_HIGH, params.getFrequency());
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetMethodId() {
        DistanceMeasurementParams params = new DistanceMeasurementParams.Builder(mDevice)
                .setMethodId(DISTANCE_MEASUREMENT_METHOD_RSSI).build();
        assertEquals(DISTANCE_MEASUREMENT_METHOD_RSSI, params.getMethodId());
    }

    private void assertParamsEquals(DistanceMeasurementParams p, DistanceMeasurementParams other) {
        if (p == null && other == null) {
            return;
        }

        if (p == null || other == null) {
            fail("Cannot compare null with non-null value: p=" + p + ", other=" + other);
        }

        assertEquals(p.getDevice(), other.getDevice());
        assertEquals(p.getDurationSeconds(), other.getDurationSeconds());
        assertEquals(p.getFrequency(), other.getFrequency());
        assertEquals(p.getMethodId(), other.getMethodId());
    }
}
