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

package android.hardware.cts.helpers.sensorverification;

import android.hardware.cts.helpers.TestSensorEvent;

/**
 * Abstract class that deals with the synchronization of the sensor verifications.
 */
public abstract class AbstractSensorVerification implements ISensorVerification {

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addSensorEvents(TestSensorEvent ... events) {
        for (TestSensorEvent event : events) {
            addSensorEventInternal(event);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addSensorEvent(TestSensorEvent event) {
        addSensorEventInternal(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract ISensorVerification clone();

    /**
     * Used by implementing classes to add a sensor event.
     */
    protected abstract void addSensorEventInternal(TestSensorEvent event);

    /**
     * Helper class to store the index, previous event, and current event.
     */
    protected class IndexedEventPair {
        public final int index;
        public final TestSensorEvent event;
        public final TestSensorEvent previousEvent;

        public IndexedEventPair(int index, TestSensorEvent event,
                TestSensorEvent previousEvent) {
            this.index = index;
            this.event = event;
            this.previousEvent = previousEvent;
        }
    }
}
