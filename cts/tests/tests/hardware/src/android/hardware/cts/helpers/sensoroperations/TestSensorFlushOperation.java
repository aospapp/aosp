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

package android.hardware.cts.helpers.sensoroperations;

import android.hardware.cts.helpers.TestSensorEnvironment;
import android.hardware.cts.helpers.TestSensorEventListener;

import java.util.concurrent.TimeUnit;

/**
 * A {@link ISensorOperation} used to verify that sensor events and sensor values are correct.
 * <p>
 * Provides methods to set test expectations as well as providing a set of default expectations
 * depending on sensor type.  When {{@link #execute()} is called, the sensor will collect the
 * events, call flush, and then run all the tests.
 * </p>
 */
public class TestSensorFlushOperation extends VerifiableSensorOperation {
    private final Long mDuration;
    private final TimeUnit mTimeUnit;

    /**
     * Create a {@link TestSensorOperation}.
     *
     * @param environment the test environment
     * @param duration the duration to gather events before calling {@code SensorManager.flush()}
     * @param timeUnit the time unit of the duration
     */
    public TestSensorFlushOperation(
            TestSensorEnvironment environment,
            long duration,
            TimeUnit timeUnit) {
        super(environment);
        mDuration = duration;
        mTimeUnit = timeUnit;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doExecute(TestSensorEventListener listener) throws InterruptedException {
        mSensorManager.runSensorAndFlush(listener, mDuration, mTimeUnit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected VerifiableSensorOperation doClone() {
        return new TestSensorFlushOperation(mEnvironment, mDuration,mTimeUnit);
    }
}
