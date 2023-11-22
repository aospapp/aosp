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

import android.hardware.cts.helpers.SensorStats;
import android.hardware.cts.helpers.TestSensorEnvironment;
import android.hardware.cts.helpers.TestSensorEvent;

/**
 * Interface describing the sensor verification. This class was designed for to handle streaming
 * events. The methods {@link #addSensorEvent(TestSensorEvent)} and
 * {@link #addSensorEvents(TestSensorEvent...)} should be called in the order that the events are
 * received. The method {@link #verify(TestSensorEnvironment, SensorStats)} should be called after
 * all events are added.
 */
public interface ISensorVerification {

    /**
     * Add a single {@link TestSensorEvent} to be evaluated.
     */
    public void addSensorEvent(TestSensorEvent event);

    /**
     * Add multiple {@link TestSensorEvent}s to be evaluated.
     */
    public void addSensorEvents(TestSensorEvent ... events);

    /**
     * Evaluate all added {@link TestSensorEvent}s and update stats.
     *
     * @param stats a {@link SensorStats} object used to keep track of the stats.
     * @throws AssertionError if the verification fails.
     */
    public void verify(TestSensorEnvironment environment, SensorStats stats);

    /**
     * Clones the {@link ISensorVerification}
     */
    public ISensorVerification clone();
}
