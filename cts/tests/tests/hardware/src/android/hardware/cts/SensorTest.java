/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.hardware.cts;

import com.android.cts.util.TimeoutReq;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SensorTest extends SensorTestCase {
    private SensorManager mSensorManager;
    private TriggerListener mTriggerListener;
    private SensorListener mSensorListener;
    private List<Sensor> mSensorList;
    private static final String TAG = "SensorTest";
    // Test only SDK defined sensors. Any sensors with type > 100 are ignored.
    private static final int MAX_OFFICIAL_ANDROID_SENSOR_TYPE = 100;
    private static final long TIMEOUT_TOLERANCE_US = TimeUnit.SECONDS.toMicros(5);
    private static final double MIN_SAMPLING_FREQUENCY_MULTIPLIER_TOLERANCE = 0.9;
    private PowerManager.WakeLock mWakeLock;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        mTriggerListener = new TriggerListener();
        mSensorListener = new SensorListener();
        mSensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
    }

    public void testSensorOperations() {
        // Because we can't know every sensors unit details, so we can't assert
        // get values with specified values.
        List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        assertNotNull(sensors);
        Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        boolean hasAccelerometer = getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_SENSOR_ACCELEROMETER);
        // accelerometer sensor is optional
        if (hasAccelerometer) {
            assertEquals(Sensor.TYPE_ACCELEROMETER, sensor.getType());
            assertSensorValues(sensor);
        } else {
            assertNull(sensor);
        }

        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        boolean hasStepCounter = getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_SENSOR_STEP_COUNTER);
        // stepcounter sensor is optional
        if (hasStepCounter) {
            assertEquals(Sensor.TYPE_STEP_COUNTER, sensor.getType());
            assertSensorValues(sensor);
        } else {
            assertNull(sensor);
        }

        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        boolean hasStepDetector = getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_SENSOR_STEP_DETECTOR);
        // stepdetector sensor is optional
        if (hasStepDetector) {
            assertEquals(Sensor.TYPE_STEP_DETECTOR, sensor.getType());
            assertSensorValues(sensor);
        } else {
            assertNull(sensor);
        }

        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        boolean hasHeartRate = getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_SENSOR_HEART_RATE);
        // heartrate sensor is optional
        if (hasHeartRate) {
            assertEquals(Sensor.TYPE_HEART_RATE, sensor.getType());
            assertSensorValues(sensor);
        } else {
            assertNull(sensor);
        }

        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        boolean hasCompass = getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_SENSOR_COMPASS);
        // compass sensor is optional
        if (hasCompass) {
            assertEquals(Sensor.TYPE_MAGNETIC_FIELD, sensor.getType());
            assertSensorValues(sensor);
        } else {
            assertNull(sensor);
        }

        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        // orientation sensor is required if the device can physically implement it
        if (hasCompass && hasAccelerometer) {
            assertEquals(Sensor.TYPE_ORIENTATION, sensor.getType());
            assertSensorValues(sensor);
        }

        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_TEMPERATURE);
        // temperature sensor is optional
        if (sensor != null) {
            assertEquals(Sensor.TYPE_TEMPERATURE, sensor.getType());
            assertSensorValues(sensor);
        }
    }

    public void testValuesForAllSensors() {
        for (Sensor sensor : mSensorList) {
            assertSensorValues(sensor);
        }
    }

    private void hasOnlyOneWakeUpSensorOrEmpty(List<Sensor> sensors) {
        if (sensors == null || sensors.isEmpty()) return;
        if (sensors.size() > 1) {
            fail("More than one " + sensors.get(0).getName() + " defined.");
            return;
        }
        assertTrue(sensors.get(0).getName() + " defined as non-wake-up sensor",
                sensors.get(0).isWakeUpSensor());
        return;
    }

    // Some sensors like proximity, significant motion etc. are defined as wake-up sensors by
    // default. Check if the wake-up flag is set correctly.
    public void testWakeUpFlags() {
        final int TYPE_WAKE_GESTURE = 23;
        final int TYPE_GLANCE_GESTURE = 24;
        final int TYPE_PICK_UP_GESTURE = 25;

        hasOnlyOneWakeUpSensorOrEmpty(mSensorManager.getSensorList(Sensor.TYPE_SIGNIFICANT_MOTION));
        hasOnlyOneWakeUpSensorOrEmpty(mSensorManager.getSensorList(TYPE_WAKE_GESTURE));
        hasOnlyOneWakeUpSensorOrEmpty(mSensorManager.getSensorList(TYPE_GLANCE_GESTURE));
        hasOnlyOneWakeUpSensorOrEmpty(mSensorManager.getSensorList(TYPE_PICK_UP_GESTURE));

        List<Sensor> proximity_sensors = mSensorManager.getSensorList(Sensor.TYPE_PROXIMITY);
        if (proximity_sensors.isEmpty()) return;
        boolean hasWakeUpProximitySensor = false;
        for (Sensor sensor : proximity_sensors) {
            if (sensor.isWakeUpSensor()) {
                hasWakeUpProximitySensor = true;
                break;
            }
        }
        assertTrue("No wake-up proximity sensors implemented", hasWakeUpProximitySensor);
    }

    public void testGetDefaultSensorWithWakeUpFlag() {
        // With wake-up flags set to false, the sensor returned should be a non wake-up sensor.
        for (Sensor sensor : mSensorList) {
            Sensor curr_sensor = mSensorManager.getDefaultSensor(sensor.getType(), false);
            if (curr_sensor != null) {
                assertFalse("getDefaultSensor wakeup=false returns a wake-up sensor" +
                        curr_sensor.getName(),
                        curr_sensor.isWakeUpSensor());
            }

            curr_sensor = mSensorManager.getDefaultSensor(sensor.getType(), true);
            if (curr_sensor != null) {
                assertTrue("getDefaultSensor wake-up returns non wake sensor" +
                        curr_sensor.getName(),
                        curr_sensor.isWakeUpSensor());
            }
        }
    }

    public void testSensorStringTypes() {
        for (Sensor sensor : mSensorList) {
            if (sensor.getType() < MAX_OFFICIAL_ANDROID_SENSOR_TYPE &&
                    !sensor.getStringType().startsWith("android.sensor.")) {
                fail("StringType not set correctly for android defined sensor " +
                        sensor.getName() + " " + sensor.getStringType());
            }
        }
    }

    public void testRequestTriggerWithNonTriggerSensor() {
        Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        boolean result;
        if (sensor != null) {
            result = mSensorManager.requestTriggerSensor(mTriggerListener, sensor);
            assertFalse(result);
        }
    }

    public void testCancelTriggerWithNonTriggerSensor() {
        Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        boolean result;
        if (sensor != null) {
            result = mSensorManager.cancelTriggerSensor(mTriggerListener, sensor);
            assertFalse(result);
        }
    }

    public void testRegisterWithTriggerSensor() {
        Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        boolean result;
        if (sensor != null) {
            result = mSensorManager.registerListener(mSensorListener, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
            assertFalse(result);
        }
    }

    public void testRegisterTwiceWithSameSensor() {
        Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        boolean result;
        if (sensor != null) {
            result = mSensorManager.registerListener(mSensorListener, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
            assertTrue(result);
            result = mSensorManager.registerListener(mSensorListener, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
            assertFalse(result);
        }
    }

    class SensorEventTimeStampListener implements SensorEventListener {
        SensorEventTimeStampListener(long eventReportLatencyNs, CountDownLatch latch) {
            mEventReportLatencyNs = eventReportLatencyNs;
            mPrevTimeStampNs = -1;
            mLatch = latch;
            numErrors = 0;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mPrevTimeStampNs == -1) {
                mPrevTimeStampNs = event.timestamp;
                return;
            }
            long currTimeStampNs = event.timestamp;
            if (currTimeStampNs <= mPrevTimeStampNs) {
                Log.w(TAG, "Timestamps not monotonically increasing curr_ts_ns=" +
                        event.timestamp + " prev_ts_ns=" + mPrevTimeStampNs);
                numErrors++;
                mPrevTimeStampNs = currTimeStampNs;
                return;
            }
            mLatch.countDown();

            final long elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();

            if (elapsedRealtimeNs <= currTimeStampNs) {
                Log.w(TAG, "Timestamps into the future curr elapsedRealTimeNs=" + elapsedRealtimeNs
                         + " current sensor ts_ns=" + currTimeStampNs);
                ++numErrors;
            } else if (elapsedRealtimeNs-currTimeStampNs > SYNC_TOLERANCE + mEventReportLatencyNs) {
                Log.w(TAG, "Timestamp sync error elapsedRealTimeNs=" + elapsedRealtimeNs +
                        " curr_ts_ns=" + currTimeStampNs +
                        " diff_ns=" + (elapsedRealtimeNs - currTimeStampNs) +
                        " SYNC_TOLERANCE_NS=" + SYNC_TOLERANCE +
                        " eventReportLatencyNs=" + mEventReportLatencyNs);
                ++numErrors;
            }
            mPrevTimeStampNs = currTimeStampNs;
        }

        public int getNumErrors() {
            return numErrors;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        private int numErrors;
        private long mEventReportLatencyNs;
        private long mPrevTimeStampNs;
        private final CountDownLatch mLatch;
        private final long SYNC_TOLERANCE = 500000000L; // 500 milli seconds approx.
    }

    // Register for each sensor and compare the timestamps of SensorEvents that you get with
    // elapsedRealTimeNano.
    @TimeoutReq(minutes=60)
    public void testSensorTimeStamps() throws Exception {
        final int numEvents = 2000;
        try {
            mWakeLock.acquire();
            int numErrors = 0;
            for (Sensor sensor : mSensorList) {
                // Skip OEM defined sensors and non continuous sensors.
                if (sensor.getReportingMode() != Sensor.REPORTING_MODE_CONTINUOUS) {
                    continue;
                }

                for (int iterations = 0; iterations < 2; ++iterations) {
                    // Test in both batch mode and non-batch mode for every sensor.
                    long maxBatchReportLatencyNs = 10000000000L; // 10 secs
                    if (iterations % 2 == 0) maxBatchReportLatencyNs = 0;

                    final long samplingPeriodNs = (long)(TimeUnit.MICROSECONDS.toNanos(
                            sensor.getMinDelay())/MIN_SAMPLING_FREQUENCY_MULTIPLIER_TOLERANCE);
                    // If there is a FIFO and a wake-lock is held, events will be reported when
                    // the batch timeout expires or when the FIFO is full which ever occurs
                    // earlier.
                    final long eventReportLatencyNs = Math.min(maxBatchReportLatencyNs,
                            sensor.getFifoMaxEventCount() * samplingPeriodNs);

                    final CountDownLatch eventsRemaining = new CountDownLatch(numEvents);
                    SensorEventTimeStampListener listener = new SensorEventTimeStampListener(
                            eventReportLatencyNs, eventsRemaining);

                    Log.i(TAG, "Running timeStamp test on " + sensor.getName());
                    boolean result = mSensorManager.registerListener(listener, sensor,
                            SensorManager.SENSOR_DELAY_FASTEST,
                            (int)maxBatchReportLatencyNs/1000);
                    assertTrue("Sensor registerListener failed ", result);

                    long timeToWaitUs = samplingPeriodNs/1000 + eventReportLatencyNs/1000 +
                            TIMEOUT_TOLERANCE_US;
                    long totalTimeWaitedUs = waitToCollectAllEvents(timeToWaitUs,
                            (int)(eventReportLatencyNs/1000), eventsRemaining);

                    mSensorManager.unregisterListener(listener);
                    if (eventsRemaining.getCount() > 0) {
                        failTimedOut(sensor.getName(), (double) totalTimeWaitedUs/1000,
                                numEvents, (double) sensor.getMinDelay()/1000,
                                eventsRemaining.getCount(),
                                numEvents - eventsRemaining.getCount());
                    }
                    if (listener.getNumErrors() > 5) {
                        fail("Check logcat. Timestamp test failed. numErrors=" +
                                listener.getNumErrors() + " " + sensor.getName() +
                                " maxBatchReportLatencyNs=" + maxBatchReportLatencyNs +
                                " samplingPeriodNs=" + sensor.getMinDelay());
                        numErrors += listener.getNumErrors();
                    } else {
                        Log.i(TAG, "TimeStamp test PASS'd on " + sensor.getName());
                    }
                }
            }
        } finally {
            mWakeLock.release();
        }
    }

    private void failTimedOut(String sensorName, double totalTimeWaitedMs, int numEvents,
            double minDelayMs, long eventsRemaining, long eventsReceived) {
        final String TIMED_OUT_FORMAT = "Timed out waiting for events from %s " +
                "waited for time=%.1fms to receive totalEvents=%d at samplingRate=%.1fHz" +
                " remainingEvents=%d  received events=%d";
        fail(String.format(TIMED_OUT_FORMAT, sensorName, totalTimeWaitedMs, numEvents,
                1000/minDelayMs, eventsRemaining, eventsReceived));
    }

    // Register for updates from each continuous mode sensor, wait for N events, call flush and
    // wait for flushCompleteEvent before unregistering for the sensor.
    @TimeoutReq(minutes=20)
    public void testBatchAndFlush() throws Exception {
        try {
            mWakeLock.acquire();
            for (Sensor sensor : mSensorList) {
                // Skip ONLY one-shot sensors.
                if (sensor.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) {
                    registerListenerCallFlush(sensor, null);
                }
            }
        } finally {
            mWakeLock.release();
        }
    }

    // Same as testBatchAndFlush but using Handler version of the API to register for sensors.
    // onSensorChanged is now called on a background thread.
    @TimeoutReq(minutes=10)
    public void testBatchAndFlushWithHandler() throws Exception {
        try {
            mWakeLock.acquire();
            HandlerThread handlerThread = new HandlerThread("sensorThread");
            handlerThread.start();
            Handler handler = new Handler(handlerThread.getLooper());
            for (Sensor sensor : mSensorList) {
                // Skip ONLY one-shot sensors.
                if (sensor.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) {
                    registerListenerCallFlush(sensor, handler);
                }
            }
        }  finally {
            mWakeLock.release();
        }
    }

    private void registerListenerCallFlush(Sensor sensor, Handler handler)
            throws InterruptedException {
        if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            return;
        }
        final int numEvents = 500;
        final int rateUs = 0; // DELAY_FASTEST
        final int maxBatchReportLatencyUs = 10000000;
        final CountDownLatch eventsRemaining = new CountDownLatch(numEvents);
        final CountDownLatch flushReceived = new CountDownLatch(1);
        SensorEventListener2 listener = new SensorEventListener2() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                eventsRemaining.countDown();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }

            @Override
            public void onFlushCompleted(Sensor sensor) {
                flushReceived.countDown();
            }
        };
        // Consider only continuous mode sensors for testing registerListener.
        // For on-change sensors, call registerListener() so that the listener is associated
        // with the sensor so that flush(listener) can be called on it.
        try {
            Log.i(TAG, "testBatch " + sensor.getName());
            boolean result = mSensorManager.registerListener(listener, sensor,
                    rateUs, maxBatchReportLatencyUs, handler);
            assertTrue("registerListener failed " + sensor.getName(), result);

            // Wait for 500 events or N seconds before the test times out.
            if (sensor.getReportingMode() == Sensor.REPORTING_MODE_CONTINUOUS) {
                // Wait for approximately the time required to generate these events + a tolerance
                // of 10 seconds.
                long timeToWaitUs =
                  numEvents*(long)(sensor.getMinDelay()/MIN_SAMPLING_FREQUENCY_MULTIPLIER_TOLERANCE)
                        + maxBatchReportLatencyUs + TIMEOUT_TOLERANCE_US;

                long totalTimeWaitedUs = waitToCollectAllEvents(timeToWaitUs,
                        maxBatchReportLatencyUs, eventsRemaining);
                if (eventsRemaining.getCount() > 0) {
                    failTimedOut(sensor.getName(), (double)totalTimeWaitedUs/1000, numEvents,
                            (double)sensor.getMinDelay()/1000,
                            eventsRemaining.getCount(), numEvents - eventsRemaining.getCount());
                }
            }
            Log.i(TAG, "testFlush " + sensor.getName());
            result = mSensorManager.flush(listener);
            assertTrue("flush failed " + sensor.getName(), result);
            boolean collectedAllEvents = flushReceived.await(TIMEOUT_TOLERANCE_US,
                    TimeUnit.MICROSECONDS);
            if (!collectedAllEvents) {
                fail("Timed out waiting for flushCompleteEvent from " + sensor.getName() +
                        " waitedFor="+ TIMEOUT_TOLERANCE_US/1000 + "ms");
            }
            Log.i(TAG, "testBatchAndFlush PASS " + sensor.getName());
        } finally {
            mSensorManager.unregisterListener(listener);
        }
    }

    // Wait till the CountDownLatch counts down to zero. If the events are not delivered within
    // timetoWaitUs, wait an additional maxReportLantencyUs and check if the sensor is streaming
    // data or not. If the sensor is not streaming at all, fail the test or else wait in increments
    // of maxReportLantencyUs to collect sensor events.
    private long waitToCollectAllEvents(long timeToWaitUs, int maxReportLatencyUs,
            CountDownLatch eventsRemaining)
            throws InterruptedException {
        boolean collectedAllEvents = false;
        long totalTimeWaitedUs = 0;
        long remainingEvents;
        final long INCREMENTAL_WAIT_US = maxReportLatencyUs + TimeUnit.SECONDS.toMicros(1);
        do {
            totalTimeWaitedUs += timeToWaitUs;
            remainingEvents = eventsRemaining.getCount();
            collectedAllEvents = eventsRemaining.await(timeToWaitUs, TimeUnit.MICROSECONDS);
            timeToWaitUs = INCREMENTAL_WAIT_US;
        } while (!collectedAllEvents &&
                (remainingEvents - eventsRemaining.getCount() >=(long)INCREMENTAL_WAIT_US/1000000));
        return totalTimeWaitedUs;
    }

    // Call registerListener for multiple sensors at a time and call flush.
    public void testBatchAndFlushWithMutipleSensors() throws Exception {
        final int MAX_SENSORS = 3;
        int numSensors = mSensorList.size() < MAX_SENSORS ? mSensorList.size() : MAX_SENSORS;
        if (numSensors == 0) {
            return;
        }
        final int numEvents = 500;
        int rateUs = 0; // DELAY_FASTEST
        final int maxBatchReportLatencyUs = 10000000;
        final CountDownLatch eventsRemaining = new CountDownLatch(numSensors * numEvents);
        final CountDownLatch flushReceived = new CountDownLatch(numSensors);
        SensorEventListener2 listener = new SensorEventListener2() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                eventsRemaining.countDown();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }

            @Override
            public void onFlushCompleted(Sensor sensor) {
                flushReceived.countDown();
            }
        };

        try {
            mWakeLock.acquire();
            StringBuilder registeredSensors = new StringBuilder(30);
            for (Sensor sensor : mSensorList) {
                // Skip all non-continuous sensors.
                if (sensor.getReportingMode() != Sensor.REPORTING_MODE_CONTINUOUS) {
                    continue;
                }
                rateUs = Math.max(sensor.getMinDelay(), rateUs);
                boolean result = mSensorManager.registerListener(listener, sensor,
                        SensorManager.SENSOR_DELAY_FASTEST, maxBatchReportLatencyUs);
                assertTrue("registerListener failed for " + sensor.getName(), result);
                registeredSensors.append(sensor.getName());
                registeredSensors.append(" ");
                if (--numSensors == 0) {
                    break;
                }
            }
            if (registeredSensors.toString().isEmpty()) {
                return;
            }

            Log.i(TAG, "testBatchAndFlushWithMutipleSensors " + registeredSensors);
            long timeToWaitUs =
                    numEvents*(long)(rateUs/MIN_SAMPLING_FREQUENCY_MULTIPLIER_TOLERANCE) +
                    maxBatchReportLatencyUs + TIMEOUT_TOLERANCE_US;
            long totalTimeWaitedUs = waitToCollectAllEvents(timeToWaitUs, maxBatchReportLatencyUs,
                    eventsRemaining);
            if (eventsRemaining.getCount() > 0) {
                failTimedOut(registeredSensors.toString(), (double)totalTimeWaitedUs/1000,
                        numEvents, (double)rateUs/1000, eventsRemaining.getCount(),
                        numEvents - eventsRemaining.getCount());
            }
            boolean result = mSensorManager.flush(listener);
            assertTrue("flush failed " + registeredSensors.toString(), result);
            boolean collectedFlushEvent =
                    flushReceived.await(TIMEOUT_TOLERANCE_US, TimeUnit.MICROSECONDS);
            if (!collectedFlushEvent) {
                fail("Timed out waiting for flushCompleteEvent from " +
                      registeredSensors.toString() + " waited for=" + timeToWaitUs/1000 + "ms");
            }
            Log.i(TAG, "testBatchAndFlushWithMutipleSensors PASS'd");
        } finally {
            mSensorManager.unregisterListener(listener);
            mWakeLock.release();
        }
    }

    private void assertSensorValues(Sensor sensor) {
        assertTrue("Max range must be positive. Range=" + sensor.getMaximumRange()
                + " " + sensor.getName(), sensor.getMaximumRange() >= 0);
        assertTrue("Max power must be positive. Power=" + sensor.getPower() + " " +
                sensor.getName(), sensor.getPower() >= 0);
        assertTrue("Max resolution must be positive. Resolution=" + sensor.getResolution() +
                " " + sensor.getName(), sensor.getResolution() >= 0);
        assertNotNull("Vendor name must not be null " + sensor.getName(), sensor.getVendor());
        assertTrue("Version must be positive version="  + sensor.getVersion() + " " +
                    sensor.getName(), sensor.getVersion() > 0);
        int fifoMaxEventCount = sensor.getFifoMaxEventCount();
        int fifoReservedEventCount = sensor.getFifoReservedEventCount();
        assertTrue(fifoMaxEventCount >= 0);
        assertTrue(fifoReservedEventCount >= 0);
        assertTrue(fifoReservedEventCount <= fifoMaxEventCount);
        if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            assertTrue("One shot sensors should have zero FIFO Size " + sensor.getName(),
                    sensor.getFifoMaxEventCount() == 0);
            assertTrue("One shot sensors should have zero FIFO Size "  + sensor.getName(),
                    sensor.getFifoReservedEventCount() == 0);
        }
    }

    @SuppressWarnings("deprecation")
    public void testLegacySensorOperations() {
        final SensorManager mSensorManager =
                (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);

        // We expect the set of sensors reported by the new and legacy APIs to be consistent.
        int sensors = 0;
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            sensors |= SensorManager.SENSOR_ACCELEROMETER;
        }
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) {
            sensors |= SensorManager.SENSOR_MAGNETIC_FIELD;
        }
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION) != null) {
            sensors |= SensorManager.SENSOR_ORIENTATION | SensorManager.SENSOR_ORIENTATION_RAW;
        }
        assertEquals(sensors, mSensorManager.getSensors());
    }

    class TriggerListener extends TriggerEventListener {
        @Override
        public void onTrigger(TriggerEvent event) {
        }
    }

    class SensorListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }
}
