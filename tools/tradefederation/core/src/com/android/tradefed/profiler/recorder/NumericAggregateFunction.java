/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tradefed.profiler.recorder;

import java.util.function.BiFunction;

/** A wrapper of BiFunction that aggregates numeric values. */
public class NumericAggregateFunction {

    private double mCount = 0;
    private BiFunction<Double, Double, Double> f;

    /** Creates an aggregate function for the given {@link MetricType}. */
    public NumericAggregateFunction(MetricType metricType) {
        switch (metricType) {
            case AVG:
            case AVGTIME:
                f = (avg, value) -> avg + ((value - avg) / ++mCount);
                return;
            case COUNT:
                f = (count, value) -> count + 1;
                return;
            case COUNTPOS:
                f = (count, value) -> (value > 0 ? count + 1 : count);
                return;
            case MAX:
                f = (max, value) -> Math.max(max, value);
                return;
            case MIN:
                f = (min, value) -> Math.min(min, value);
                return;
            case SUM:
                f = (sum, value) -> sum + value;
                return;
            default:
                throw new IllegalArgumentException("Unknown metric type " + metricType.toString());
        }
    }

    /** Returns the stored aggregate function. */
    public BiFunction<Double, Double, Double> getFunction() {
        return f;
    }
}
