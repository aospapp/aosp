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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.DistanceMeasurementResult;
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
public class DistanceMeasurementResultTest {
    private Context mContext;
    private BluetoothAdapter mAdapter;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        Assume.assumeTrue(ApiLevelUtil.isAtLeast(Build.VERSION_CODES.TIRAMISU));
        Assume.assumeTrue(TestUtils.isBleSupported(mContext));

        mAdapter = TestUtils.getBluetoothAdapterOrDie();
        assertTrue(BTAdapterUtils.enableAdapter(mAdapter, mContext));
        TestUtils.adoptPermissionAsShellUid(BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED);
        Assume.assumeTrue(mAdapter.isDistanceMeasurementSupported() == FEATURE_SUPPORTED);
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
            DistanceMeasurementResult result = new DistanceMeasurementResult
                    .Builder(121.0, 120.0)
                    .setAzimuthAngle(90).setErrorAzimuthAngle(45)
                    .setAltitudeAngle(60).setErrorAltitudeAngle(30).build();
            result.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            DistanceMeasurementResult resultFromParcel =
                    DistanceMeasurementResult.CREATOR.createFromParcel(parcel);
            assertResultEquals(result, resultFromParcel);
        } finally {
            parcel.recycle();
        }
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetResultMeters() {
        DistanceMeasurementResult result = new DistanceMeasurementResult.Builder(121.0, 120.0)
                .build();
        assertEquals(121.0, result.getResultMeters(), 0.0);
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetErrorMeters() {
        DistanceMeasurementResult result = new DistanceMeasurementResult.Builder(121.0, 120.0)
                .build();
        assertEquals(120.0, result.getErrorMeters(), 0.0);
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetAzimuthAngle() {
        DistanceMeasurementResult result = new DistanceMeasurementResult.Builder(121.0, 120.0)
                .setAzimuthAngle(60).build();
        assertEquals(60.0, result.getAzimuthAngle(), 0.0);
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetErrorAzimuthAngle() {
        DistanceMeasurementResult result = new DistanceMeasurementResult.Builder(121.0, 120.0)
                .setErrorAzimuthAngle(60).build();
        assertEquals(60.0, result.getErrorAzimuthAngle(), 0.0);
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetAltitudeAngle() {
        DistanceMeasurementResult result = new DistanceMeasurementResult.Builder(121.0, 120.0)
                .setAltitudeAngle(60).build();
        assertEquals(60.0, result.getAltitudeAngle(), 0.0);
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testSetGetErrorAltitudeAngle() {
        DistanceMeasurementResult result = new DistanceMeasurementResult.Builder(121.0, 120.0)
                .setErrorAltitudeAngle(60).build();
        assertEquals(60.0, result.getErrorAltitudeAngle(), 0.0);
    }

    @CddTest(requirements = {"7.4.3/C-2-1"})
    @Test
    public void testIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new DistanceMeasurementResult.Builder(-1.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new DistanceMeasurementResult.Builder(10.0, -1.0));
        DistanceMeasurementResult.Builder result = new DistanceMeasurementResult.Builder(10.0, 5.0);
        assertThrows(IllegalArgumentException.class, () -> result.setAzimuthAngle(380));
        assertThrows(IllegalArgumentException.class, () -> result.setErrorAzimuthAngle(380));
        assertThrows(IllegalArgumentException.class, () -> result.setAltitudeAngle(180));
        assertThrows(IllegalArgumentException.class, () -> result.setErrorAltitudeAngle(181));
    }

    private void assertResultEquals(DistanceMeasurementResult p, DistanceMeasurementResult other) {
        if (p == null && other == null) {
            return;
        }

        if (p == null || other == null) {
            fail("Cannot compare null with non-null value: p=" + p + ", other=" + other);
        }

        assertEquals(p.getResultMeters(), other.getResultMeters(), 0.0);
        assertEquals(p.getErrorMeters(), other.getErrorMeters(), 0.0);
        assertEquals(p.getAzimuthAngle(), other.getAzimuthAngle(), 0.0);
        assertEquals(p.getErrorAzimuthAngle(), other.getErrorAzimuthAngle(), 0.0);
        assertEquals(p.getAltitudeAngle(), other.getAltitudeAngle(), 0.0);
        assertEquals(p.getErrorAltitudeAngle(), other.getErrorAltitudeAngle(), 0.0);
    }
}
