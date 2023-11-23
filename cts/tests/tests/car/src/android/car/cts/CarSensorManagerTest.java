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

import static android.car.cts.utils.ShellPermissionUtils.runWithShellPermissionIdentity;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.car.Car;
import android.car.annotation.ApiRequirements;
import android.car.hardware.CarSensorManager;
import android.car.test.ApiCheckerRule.Builder;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.RequiresDevice;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CddTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.stream.IntStream;

@SmallTest
@RequiresDevice
@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "Instant apps cannot get car related permissions.")
public final class CarSensorManagerTest extends AbstractCarTestCase {

    private static final String TAG = CarSensorManagerTest.class.getSimpleName();

    private int[] mSupportedSensors;

    // TODO(b/242350638): add missing annotations, make sure @ApiRequirements have the right
    // supported versions, then remove this method (using a child bug of 242350638)
    @Override
    protected void configApiCheckerRule(Builder builder) {
        Log.w(TAG, "Disabling API requirements check");
        builder.disableAnnotationsCheck();
    }

    @Before
    public void setUp() throws Exception {
        runWithShellPermissionIdentity(() -> {
            CarSensorManager carSensorManager = (CarSensorManager) getCar().getCarManager(
                    Car.SENSOR_SERVICE);
            mSupportedSensors = carSensorManager.getSupportedSensors();
            assertNotNull(mSupportedSensors);
        });
    }

    @CddTest(requirements = {"2.5.1"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @Test
    public void testRequiredSensorsForDrivingState() throws Exception {
        boolean foundSpeed =
            isSupportSensor(CarSensorManager.SENSOR_TYPE_CAR_SPEED);
        boolean foundGear = isSupportSensor(CarSensorManager.SENSOR_TYPE_GEAR);
        assertTrue("Must support SENSOR_TYPE_CAR_SPEED", foundSpeed);
        assertTrue("Must support SENSOR_TYPE_GEAR", foundGear);
    }

    @CddTest(requirements = {"2.5.1"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @Test
    public void testMustSupportNightSensor() {
        boolean foundNightSensor =
            isSupportSensor(CarSensorManager.SENSOR_TYPE_NIGHT);
        assertTrue("Must support SENSOR_TYPE_NIGHT", foundNightSensor);
    }

    @CddTest(requirements = {"2.5.1"})
    @Test
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void testMustSupportParkingBrake() throws Exception {
        boolean foundParkingBrake =
            isSupportSensor(CarSensorManager.SENSOR_TYPE_PARKING_BRAKE);
        assertTrue("Must support SENSOR_TYPE_PARKING_BRAKE", foundParkingBrake);
    }

    private boolean isSupportSensor(int sensorType) {
        return IntStream.of(mSupportedSensors)
            .anyMatch(x -> x == sensorType);
    }
}
