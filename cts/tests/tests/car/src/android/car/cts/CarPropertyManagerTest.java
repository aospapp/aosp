/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static java.lang.Integer.toHexString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.platform.test.annotations.RequiresDevice;

import androidx.test.runner.AndroidJUnit4;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertTrue;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RequiresDevice
@RunWith(AndroidJUnit4.class)
public class CarPropertyManagerTest extends CarApiTestBase {

    private static final String TAG = CarPropertyManagerTest.class.getSimpleName();
    private static final long WAIT_CALLBACK = 1500L;
    private CarPropertyManager mCarPropertyManager;
    /** contains property Ids for the properties required by CDD*/
    private ArraySet<Integer> mPropertyIds = new ArraySet<>();


    private static class CarPropertyEventCounter implements CarPropertyEventCallback {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private SparseArray<Integer> mEventCounter = new SparseArray<>();

        @GuardedBy("mLock")
        private SparseArray<Integer> mErrorCounter = new SparseArray<>();

        public int receivedEvent(int propId) {
            int val;
            synchronized (mLock) {
                val = mEventCounter.get(propId, 0);
            }
            return val;
        }

        public int receivedError(int propId) {
            int val;
            synchronized (mLock) {
                val = mErrorCounter.get(propId, 0);
            }
            return val;
        }

        @Override
        public void onChangeEvent(CarPropertyValue value) {
            synchronized (mLock) {
                int val = mEventCounter.get(value.getPropertyId(), 0) + 1;
                mEventCounter.put(value.getPropertyId(), val);
            }
        }

        @Override
        public void onErrorEvent(int propId, int zone) {
            synchronized (mLock) {
                int val = mErrorCounter.get(propId, 0) + 1;
                mErrorCounter.put(propId, val);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mCarPropertyManager = (CarPropertyManager) getCar().getCarManager(Car.PROPERTY_SERVICE);
        mPropertyIds.add(VehiclePropertyIds.PERF_VEHICLE_SPEED);
        mPropertyIds.add(VehiclePropertyIds.GEAR_SELECTION);
        mPropertyIds.add(VehiclePropertyIds.NIGHT_MODE);
    }

    @Test
    public void testGetPropertyList() {
        List<CarPropertyConfig> allConfigs = mCarPropertyManager.getPropertyList();
        assertNotNull(allConfigs);
        List<CarPropertyConfig> requiredConfigs = mCarPropertyManager.getPropertyList(mPropertyIds);
        // Vehicles need to implement all of those properties
        assertEquals(mPropertyIds.size(), requiredConfigs.size());

    }

    @SuppressWarnings("unchecked")
    @Test
    public void testGetProperty() {
        List<CarPropertyConfig> configs = mCarPropertyManager.getPropertyList(mPropertyIds);
        for (CarPropertyConfig cfg : configs) {
            if (cfg.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ) {
                int[] areaIds = getAreaIdsHelper(cfg);
                int propId = cfg.getPropertyId();
                // no guarantee if we can get values, just call and check if it throws exception.
                if (cfg.getPropertyType() == Boolean.class) {
                    for (int areaId : areaIds) {
                        mCarPropertyManager.getBooleanProperty(propId, areaId);
                    }
                } else if (cfg.getPropertyType() == Integer.class) {
                    for (int areaId : areaIds) {
                        mCarPropertyManager.getIntProperty(propId, areaId);
                    }
                } else if (cfg.getPropertyType() == Float.class) {
                    for (int areaId : areaIds) {
                        mCarPropertyManager.getFloatProperty(propId, areaId);
                    }
                } else if (cfg.getPropertyType() == Integer[].class) {
                    for (int areId : areaIds) {
                        mCarPropertyManager.getIntArrayProperty(propId, areId);
                    }
                } else {
                    for (int areaId : areaIds) {
                        mCarPropertyManager.getProperty(
                                cfg.getPropertyType(), propId, areaId);;
                    }
                }
            }
        }
    }

    @Test
    public void testIsPropertyAvailable() {
        List<CarPropertyConfig> configs = mCarPropertyManager.getPropertyList(mPropertyIds);

        for (CarPropertyConfig cfg : configs) {
            int[] areaIds = getAreaIdsHelper(cfg);
            for (int areaId : areaIds) {
                assertTrue(mCarPropertyManager.isPropertyAvailable(cfg.getPropertyId(), areaId));
            }
        }
    }

    @Test
    public void testSetProperty() {
        List<CarPropertyConfig> configs = mCarPropertyManager.getPropertyList();
        for (CarPropertyConfig cfg : configs) {
            if (cfg.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE
                    && cfg.getPropertyType() == Boolean.class) {
                // Get the current value, and set a different value to the property and verify it.
                for (int areaId : getAreaIdsHelper(cfg)) {
                    assertTrue(setBooleanPropertyHelper(cfg.getPropertyId(), areaId));
                }
            }
        }
    }


    @Test
    public void testRegisterCallback() throws Exception {
        //Test on registering a invalid property
        int invalidPropertyId = -1;
        boolean isRegistered = mCarPropertyManager.registerCallback(
            new CarPropertyEventCounter(), invalidPropertyId, 0);
        assertFalse(isRegistered);

        // Test for continuous properties
        int vehicleSpeed = VehiclePropertyIds.PERF_VEHICLE_SPEED;
        CarPropertyEventCounter speedListenerNormal = new CarPropertyEventCounter();
        CarPropertyEventCounter speedListenerUI = new CarPropertyEventCounter();

        assertEquals(0, speedListenerNormal.receivedEvent(vehicleSpeed));
        assertEquals(0, speedListenerNormal.receivedError(vehicleSpeed));
        assertEquals(0, speedListenerUI.receivedEvent(vehicleSpeed));
        assertEquals(0, speedListenerUI.receivedError(vehicleSpeed));

        mCarPropertyManager.registerCallback(speedListenerNormal, vehicleSpeed,
                CarPropertyManager.SENSOR_RATE_NORMAL);
        mCarPropertyManager.registerCallback(speedListenerUI, vehicleSpeed,
                CarPropertyManager.SENSOR_RATE_FASTEST);

        Thread.sleep(WAIT_CALLBACK);
        assertNotEquals(0, speedListenerNormal.receivedEvent(vehicleSpeed));
        assertNotEquals(0, speedListenerUI.receivedEvent(vehicleSpeed));
        assertTrue(speedListenerUI.receivedEvent(vehicleSpeed) >
                speedListenerNormal.receivedEvent(vehicleSpeed));

        mCarPropertyManager.unregisterCallback(speedListenerUI);
        mCarPropertyManager.unregisterCallback(speedListenerNormal);

        // Test for on_change properties
        int nightMode = VehiclePropertyIds.NIGHT_MODE;
        CarPropertyEventCounter nightModeListener = new CarPropertyEventCounter();
        mCarPropertyManager.registerCallback(nightModeListener, nightMode, 0);
        Thread.sleep(WAIT_CALLBACK);
        assertEquals(1, nightModeListener.receivedEvent(nightMode));

        mCarPropertyManager.unregisterCallback(nightModeListener);

    }

    @Test
    public void testUnregisterCallback() throws Exception {

        int vehicleSpeed = VehiclePropertyIds.PERF_VEHICLE_SPEED;
        CarPropertyEventCounter speedListenerNormal = new CarPropertyEventCounter();
        CarPropertyEventCounter speedListenerUI = new CarPropertyEventCounter();

        mCarPropertyManager.registerCallback(speedListenerNormal, vehicleSpeed,
            CarPropertyManager.SENSOR_RATE_NORMAL);

        // test on unregistering a callback that was never registered
        try {
            mCarPropertyManager.unregisterCallback(speedListenerUI);
        } catch (Exception e) {
            Assert.fail();
        }

        mCarPropertyManager.registerCallback(speedListenerUI, vehicleSpeed,
            CarPropertyManager.SENSOR_RATE_UI);
        Thread.sleep(WAIT_CALLBACK);

        mCarPropertyManager.unregisterCallback(speedListenerNormal, vehicleSpeed);
        Thread.sleep(WAIT_CALLBACK);

        int currentEventNormal = speedListenerNormal.receivedEvent(vehicleSpeed);
        int currentEventUI = speedListenerUI.receivedEvent(vehicleSpeed);
        Thread.sleep(WAIT_CALLBACK);

        assertEquals(currentEventNormal, speedListenerNormal.receivedEvent(vehicleSpeed));
        assertNotEquals(currentEventUI, speedListenerUI.receivedEvent(vehicleSpeed));

        mCarPropertyManager.unregisterCallback(speedListenerUI);
        Thread.sleep(WAIT_CALLBACK);

        currentEventUI = speedListenerUI.receivedEvent(vehicleSpeed);
        Thread.sleep(WAIT_CALLBACK);
        assertEquals(currentEventUI, speedListenerUI.receivedEvent(vehicleSpeed));
    }

    /**
     * Returns true if set boolean value successfully.
     * @param propId
     * @param areaId
     * @return
     */
    private boolean setBooleanPropertyHelper(int propId, int areaId) {
        boolean currentValue = mCarPropertyManager.getBooleanProperty(propId, areaId);
        boolean expectedValue = !currentValue;
        try {
            mCarPropertyManager.setBooleanProperty(propId, areaId, expectedValue);
            Thread.sleep(WAIT_CALLBACK);
            currentValue = mCarPropertyManager.getBooleanProperty(propId, areaId);
            return expectedValue == currentValue;
        } catch (Exception e) {
            Log.e(TAG, new StringBuilder()
                        .append("Failed to verify Property Id: 0x")
                        .append(toHexString(propId))
                        .append(", in areaId: 0x")
                        .append(toHexString(areaId))
                        .toString());
        }
        return false;
    }

    private int[] getAreaIdsHelper(CarPropertyConfig config) {
        if (config.isGlobalProperty()) {
            int[] areaIds = {0};
            return areaIds;
        } else {
            return config.getAreaIds();
        }
    }

}
