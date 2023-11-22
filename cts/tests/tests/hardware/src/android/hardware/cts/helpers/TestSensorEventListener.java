/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.cts.helpers;

import junit.framework.Assert;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener2;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A {@link SensorEventListener2} which performs operations such as waiting for a specific number of
 * events or for a specific time, or waiting for a flush to complete. This class performs
 * verifications and will throw {@link AssertionError}s if there are any errors. It may also wrap
 * another {@link SensorEventListener2}.
 */
public class TestSensorEventListener implements SensorEventListener2 {
    public static final String LOG_TAG = "TestSensorEventListener";
    private static final long EVENT_TIMEOUT_US = TimeUnit.MICROSECONDS.convert(5, TimeUnit.SECONDS);
    private static final long FLUSH_TIMEOUT_US = TimeUnit.MICROSECONDS.convert(5, TimeUnit.SECONDS);

    private final SensorEventListener2 mListener;

    private volatile CountDownLatch mEventLatch;
    private volatile CountDownLatch mFlushLatch = new CountDownLatch(1);
    private volatile TestSensorEnvironment mEnvironment;
    private volatile boolean mLogEvents;

    /**
     * Construct a {@link TestSensorEventListener}.
     */
    public TestSensorEventListener() {
        this(null);
    }

    /**
     * Construct a {@link TestSensorEventListener} that wraps a {@link SensorEventListener2}.
     */
    public TestSensorEventListener(SensorEventListener2 listener) {
        if (listener != null) {
            mListener = listener;
        } else {
            // use a Null Object to simplify handling the listener
            mListener = new SensorEventListener2() {
                public void onFlushCompleted(Sensor sensor) {}
                public void onSensorChanged(SensorEvent sensorEvent) {}
                public void onAccuracyChanged(Sensor sensor, int i) {}
            };
        }
    }

    /**
     * Set the sensor, rate, and batch report latency used for the assertions.
     */
    public void setEnvironment(TestSensorEnvironment environment) {
        mEnvironment = environment;
    }

    /**
     * Set whether or not to log events
     */
    public void setLogEvents(boolean log) {
        mLogEvents = log;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        mListener.onSensorChanged(event);
        if (mLogEvents) {
            Log.v(LOG_TAG, String.format(
                    "Sensor %d: sensor_timestamp=%dns, received_timestamp=%dns, values=%s",
                    mEnvironment.getSensor().getType(),
                    event.timestamp,
                    SystemClock.elapsedRealtimeNanos(),
                    Arrays.toString(event.values)));
        }

        CountDownLatch eventLatch = mEventLatch;
        if(eventLatch != null) {
            eventLatch.countDown();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        mListener.onAccuracyChanged(sensor, accuracy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onFlushCompleted(Sensor sensor) {
        CountDownLatch latch = mFlushLatch;
        mFlushLatch = new CountDownLatch(1);
        if(latch != null) {
            latch.countDown();
        }
        mListener.onFlushCompleted(sensor);
    }

    /**
     * Wait for {@link #onFlushCompleted(Sensor)} to be called.
     *
     * @throws AssertionError if there was a timeout after {@link #FLUSH_TIMEOUT_US} &micro;s
     */
    public void waitForFlushComplete() throws InterruptedException {
        CountDownLatch latch = mFlushLatch;
        if(latch == null) {
            return;
        }
        Assert.assertTrue(
                SensorCtsHelper.formatAssertionMessage("WaitForFlush", mEnvironment),
                latch.await(FLUSH_TIMEOUT_US, TimeUnit.MICROSECONDS));
    }

    /**
     * Collect a specific number of {@link TestSensorEvent}s.
     *
     * @throws AssertionError if there was a timeout after {@link #FLUSH_TIMEOUT_US} &micro;s
     */
    public void waitForEvents(int eventCount) throws InterruptedException {
        mEventLatch = new CountDownLatch(eventCount);
        try {
            int rateUs = mEnvironment.getExpectedSamplingPeriodUs();
            // Timeout is 2 * event count * expected period + batch timeout + default wait
            long timeoutUs = (2 * eventCount * rateUs)
                    + mEnvironment.getMaxReportLatencyUs()
                    + EVENT_TIMEOUT_US;
            String message = SensorCtsHelper.formatAssertionMessage(
                    "WaitForEvents",
                    mEnvironment,
                    "requested:%d, received:%d",
                    eventCount,
                    eventCount - mEventLatch.getCount());
            Assert.assertTrue(message, mEventLatch.await(timeoutUs, TimeUnit.MICROSECONDS));
        } finally {
            mEventLatch = null;
        }
    }

    /**
     * Collect {@link TestSensorEvent} for a specific duration.
     */
    public void waitForEvents(long duration, TimeUnit timeUnit) throws InterruptedException {
        SensorCtsHelper.sleep(duration, timeUnit);
    }
}
