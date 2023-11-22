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
 * Interface used by all sensor operations. This allows for complex operations such as chaining
 * operations together or running operations in parallel.
 * <p>
 * Certain restrictions exist for {@link ISensorOperation}s:
 * <p><ul>
 * <li>{@link #execute()} should only be called once and behavior is undefined for subsequent calls.
 * Once {@link #execute()} is called, the class should not be modified. Generally, there is no
 * synchronization for operations.</li>
 * <li>{@link #getStats()} should only be called after {@link #execute()}. If it is called before,
 * the returned value is undefined.</li>
 * <li>{@link #clone()} may be called any time and should return an operation with the same
 * parameters as the original.</li>
 * </ul>
 */
public interface ISensorOperation {

    /**
     * Executes the sensor operation.  This may throw {@link RuntimeException}s such as
     * {@link AssertionError}s.
     *
     * NOTE: the operation is expected to handle interruption by:
     * - cleaning up on {@link InterruptedException}
     * - propagating the exception down the stack
     */
    public void execute() throws InterruptedException;

    /**
     * Get the stats for the operation.
     *
     * @return The {@link SensorStats} for the operation.
     */
    public SensorStats getStats();

    /**
     * Clones the {@link ISensorOperation}. The implementation should also clone all child
     * operations, so that a cloned operation will run with the exact same parameters as the
     * original. The stats should not be cloned.
     *
     * @return The cloned {@link ISensorOperation}
     */
    public ISensorOperation clone();
}
