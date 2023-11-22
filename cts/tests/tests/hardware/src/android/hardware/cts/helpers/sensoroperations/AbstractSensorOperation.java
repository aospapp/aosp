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

import android.hardware.cts.helpers.SensorStats;

/**
 * A {@link ISensorOperation} which contains a common implementation for gathering
 * {@link SensorStats}.
 */
public abstract class AbstractSensorOperation implements ISensorOperation {

    private final SensorStats mStats = new SensorStats();

    /**
     * Wrapper around {@link SensorStats#addSensorStats(String, SensorStats)}
     */
    protected void addSensorStats(String key, SensorStats stats) {
        mStats.addSensorStats(key, stats);
    }

    /**
     * Wrapper around {@link SensorStats#addSensorStats(String, SensorStats)} that allows an index
     * to be added. This is useful for {@link ISensorOperation}s that have many iterations or child
     * operations. The key added is in the form {@code key + "_" + index} where index may be zero
     * padded.
     */
    protected void addSensorStats(String key, int index, SensorStats stats) {
        addSensorStats(String.format("%s_%03d", key, index), stats);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SensorStats getStats() {
        return mStats;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract ISensorOperation clone();

}
