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

import java.util.LinkedList;
import java.util.List;

/**
 * A {@link ISensorOperation} that executes a set of children {@link ISensorOperation}s in a
 * sequence. The children are executed in the order they are added. This class can be combined to
 * compose complex {@link ISensorOperation}s.
 */
public class SequentialSensorOperation extends AbstractSensorOperation {
    public static final String STATS_TAG = "sequential";

    private final List<ISensorOperation> mOperations = new LinkedList<ISensorOperation>();

    /**
     * Add a set of {@link ISensorOperation}s.
     */
    public void add(ISensorOperation ... operations) {
        for (ISensorOperation operation : operations) {
            if (operation == null) {
                throw new IllegalArgumentException("Arguments cannot be null");
            }
            mOperations.add(operation);
        }
    }

    /**
     * Executes the {@link ISensorOperation}s in the order they were added. If an exception occurs
     * in one operation, it is thrown and all subsequent operations will not run.
     */
    @Override
    public void execute() throws InterruptedException {
        for (int i = 0; i < mOperations.size(); i++) {
            ISensorOperation operation = mOperations.get(i);
            try {
                operation.execute();
            } catch (AssertionError e) {
                String msg = String.format("Operation %d failed: \"%s\"", i, e.getMessage());
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
    public SequentialSensorOperation clone() {
        SequentialSensorOperation operation = new SequentialSensorOperation();
        for (ISensorOperation subOperation : mOperations) {
            operation.add(subOperation.clone());
        }
        return operation;
    }
}
