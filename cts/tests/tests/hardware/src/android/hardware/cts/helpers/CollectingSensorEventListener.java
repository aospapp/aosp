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

import android.hardware.SensorEvent;
import android.hardware.SensorEventListener2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link TestSensorEventListener} which collects events to be processed after the test is run.
 * This should only be used for short tests.
 */
public class CollectingSensorEventListener extends TestSensorEventListener {
    private final ArrayList<TestSensorEvent> mSensorEventsList = new ArrayList<TestSensorEvent>();

    /**
     * Constructs a {@link CollectingSensorEventListener} with an additional
     * {@link SensorEventListener2}.
     */
    public CollectingSensorEventListener(SensorEventListener2 listener) {
        super(listener);
    }

    /**
     * Constructs a {@link CollectingSensorEventListener}.
     */
    public CollectingSensorEventListener() {
        this(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        super.onSensorChanged(event);
        synchronized (mSensorEventsList) {
            mSensorEventsList.add(new TestSensorEvent(event));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Clears the event queue before starting.
     * </p>
     */
    @Override
    public void waitForEvents(int eventCount) throws InterruptedException {
        clearEvents();
        super.waitForEvents(eventCount);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Clears the event queue before starting.
     * </p>
     */
    @Override
    public void waitForEvents(long duration, TimeUnit timeUnit) throws InterruptedException {
        clearEvents();
        super.waitForEvents(duration, timeUnit);
    }

    /**
     * Get the {@link TestSensorEvent} array from the event queue.
     */
    public List<TestSensorEvent> getEvents() {
        synchronized (mSensorEventsList) {
            return Collections.unmodifiableList(mSensorEventsList);
        }
    }

    /**
     * Clear the event queue.
     */
    public void clearEvents() {
        synchronized (mSensorEventsList) {
            mSensorEventsList.clear();
        }
    }
}
