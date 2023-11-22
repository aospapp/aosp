/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.hardware.cts.helpers.SensorStats;

/**
 * A {@link ISensorOperation} that executes a single {@link ISensorOperation} a given number of
 * times. This class can be combined to compose complex {@link ISensorOperation}s.
 */
public class RepeatingSensorOperation extends AbstractSensorOperation {
    public static final String STATS_TAG = "repeating";

    private final ISensorOperation mOperation;
    private final int mIterations;

    /**
     * Constructor for {@link RepeatingSensorOperation}.
     *
     * @param operation the {@link ISensorOperation} to run.
     * @param iterations the number of iterations to run the operation for.
     */
    public RepeatingSensorOperation(ISensorOperation operation, int iterations) {
        if (operation == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        mOperation = operation;
        mIterations = iterations;

    }

    /**
     * Executes the {@link ISensorOperation}s the given number of times. If an exception occurs
     * in one iterations, it is thrown and all subsequent iterations will not run.
     */
    @Override
    public void execute() throws InterruptedException {
        for(int i = 0; i < mIterations; ++i) {
            ISensorOperation operation = mOperation.clone();
            try {
                operation.execute();
            } catch (AssertionError e) {
                String msg = String.format("Iteration %d failed: \"%s\"", i, e.getMessage());
                getStats().addValue(SensorStats.ERROR, msg);
                throw new AssertionError(msg, e);
            } finally {
                addSensorStats(STATS_TAG, i, operation.getStats());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RepeatingSensorOperation clone() {
        return new RepeatingSensorOperation(mOperation.clone(), mIterations);
    }
}
