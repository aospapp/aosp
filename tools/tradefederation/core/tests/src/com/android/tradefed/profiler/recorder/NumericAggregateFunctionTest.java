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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.function.BiFunction;

/** Unit tests for {@link NumericAggregateFunction}. */
@RunWith(JUnit4.class)
public class NumericAggregateFunctionTest {
    private static final double EPSILON = 1E-6;

    @Test
    public void testMergeFunctionAvg() throws Exception {
        BiFunction<Double, Double, Double> f =
                new NumericAggregateFunction(MetricType.AVG).getFunction();
        double foo = 0;
        foo = f.apply(foo, -2d);
        Assert.assertEquals(-2d, foo, EPSILON);
        foo = f.apply(foo, 5d);
        Assert.assertEquals(1.5d, foo, EPSILON);
        foo = f.apply(foo, 3d);
        Assert.assertEquals(2d, foo, EPSILON);
    }

    @Test
    public void testMergeFunctionAvgMultipleInstances() throws Exception {
        BiFunction<Double, Double, Double> f_foo =
                new NumericAggregateFunction(MetricType.AVG).getFunction();
        BiFunction<Double, Double, Double> f_bar =
                new NumericAggregateFunction(MetricType.AVG).getFunction();
        double foo = 0;
        foo = f_foo.apply(foo, -2d);
        Assert.assertEquals(-2d, foo, EPSILON);
        foo = f_foo.apply(foo, 5d);
        Assert.assertEquals(1.5d, foo, EPSILON);

        double bar = 0;
        bar = f_bar.apply(bar, 100d);
        Assert.assertEquals(100d, bar, EPSILON);

        foo = f_foo.apply(foo, 3d);
        Assert.assertEquals(2d, foo, EPSILON);

        bar = f_bar.apply(bar, 200d);
        Assert.assertEquals(150d, bar, EPSILON);
    }

    @Test
    public void testMergeFunctionAvgTime() throws Exception {
        BiFunction<Double, Double, Double> f =
                new NumericAggregateFunction(MetricType.AVGTIME).getFunction();
        double foo = 0;
        foo = f.apply(foo, -2d);
        Assert.assertEquals(-2d, foo, EPSILON);
        foo = f.apply(foo, 5d);
        Assert.assertEquals(1.5d, foo, EPSILON);
        foo = f.apply(foo, 3d);
        Assert.assertEquals(2d, foo, EPSILON);
    }

    @Test
    public void testMergeFunctionCount() throws Exception {
        BiFunction<Double, Double, Double> f =
                new NumericAggregateFunction(MetricType.COUNT).getFunction();
        double foo = 0;
        foo = f.apply(foo, -2d);
        Assert.assertEquals(1d, foo, EPSILON);
        foo = f.apply(foo, 5d);
        Assert.assertEquals(2d, foo, EPSILON);
    }

    @Test
    public void testMergeFunctionCountPositive() throws Exception {
        BiFunction<Double, Double, Double> f =
                new NumericAggregateFunction(MetricType.COUNTPOS).getFunction();
        double foo = 0;
        foo = f.apply(foo, -2d);
        Assert.assertEquals(0d, foo, EPSILON);
        foo = f.apply(foo, 5d);
        Assert.assertEquals(1d, foo, EPSILON);
    }

    @Test
    public void testMergeFunctionMax() throws Exception {
        BiFunction<Double, Double, Double> f =
                new NumericAggregateFunction(MetricType.MAX).getFunction();
        double foo = 0;
        foo = f.apply(foo, -2d);
        Assert.assertEquals(0, foo, EPSILON);
        foo = f.apply(foo, 5d);
        Assert.assertEquals(5d, foo, EPSILON);
    }

    @Test
    public void testMergeFunctionMin() throws Exception {
        BiFunction<Double, Double, Double> f =
                new NumericAggregateFunction(MetricType.MIN).getFunction();
        double foo = 0;
        foo = f.apply(foo, -2d);
        Assert.assertEquals(-2d, foo, EPSILON);
        foo = f.apply(foo, 5d);
        Assert.assertEquals(-2d, foo, EPSILON);
    }

    @Test
    public void testMergeFunctionSum() throws Exception {
        BiFunction<Double, Double, Double> f =
                new NumericAggregateFunction(MetricType.SUM).getFunction();
        double foo = 0;
        foo = f.apply(foo, -2d);
        Assert.assertEquals(-2d, foo, EPSILON);
        foo = f.apply(foo, 5d);
        Assert.assertEquals(3d, foo, EPSILON);
    }
}
