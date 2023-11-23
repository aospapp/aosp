/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.car.cts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.content.pm.CarPackageManager;
import android.platform.test.annotations.RequiresDevice;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RequiresDevice
@RunWith(AndroidJUnit4.class)
public class CarPackageManagerTest extends CarApiTestBase {

    private CarPackageManager mCarPm;
    private static String TAG = CarPackageManagerTest.class.getSimpleName();

    /** Name of the meta-data attribute for the automotive application XML resource */
    private static final String METADATA_ATTRIBUTE = "android.car.application";

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mCarPm = (CarPackageManager) getCar().getCarManager(Car.PACKAGE_SERVICE);
    }

    @Test
    public void testActivityDistractionOptimized() throws Exception {
       assertFalse(mCarPm.isActivityDistractionOptimized("com.basic.package", "DummyActivity"));
       // Real system activity is not allowed as well.
       assertFalse(mCarPm.isActivityDistractionOptimized("com.android.phone", "CallActivity"));

       try {
           mCarPm.isActivityDistractionOptimized("com.android.settings", null);
           fail();
       } catch (IllegalArgumentException expected) {
           // Expected.
       }
       try {
           mCarPm.isActivityDistractionOptimized(null, "Any");
           fail();
       } catch (IllegalArgumentException expected) {
           // Expected.
       }
       try {
           mCarPm.isActivityDistractionOptimized(null, null);
           fail();
       } catch (IllegalArgumentException expected) {
           // Expected.
       }
    }

    @Test
    public void testDistractionOptimizedActivityIsAllowed() throws CarNotConnectedException {
        // This test relies on test activity in installed apk, and AndroidManifest declaration.
        assertTrue(mCarPm.isActivityDistractionOptimized("android.car.cts",
                "android.car.cts.drivingstate.DistractionOptimizedActivity"));
    }

    @Test
    public void testNonDistractionOptimizedActivityNotAllowed() throws CarNotConnectedException {
        // This test relies on test activity in installed apk, and AndroidManifest declaration.
        assertFalse(mCarPm.isActivityDistractionOptimized("android.car.cts",
                "android.car.cts.drivingstate.NonDistractionOptimizedActivity"));
    }
}
