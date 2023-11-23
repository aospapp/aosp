/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bedstead.nene.benchmarks;

import com.android.bedstead.harrier.FrameworkMethodWithParameter;

import org.junit.Test;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

import java.util.ArrayList;
import java.util.List;

/**
 * JUnit4 test runner which invokes each test method once for each {@link Benchmark} returned by
 * {@link BenchmarkDefinition#getAllBenchmarks()}, passing the benchmark as a parameter.
 */
public final class BenchmarkTestRunner extends BlockJUnit4ClassRunner {
    public BenchmarkTestRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    @Override
    protected List<FrameworkMethod> computeTestMethods() {
        List<FrameworkMethod> result = new ArrayList<>();
        for (FrameworkMethod method : getTestClass().getAnnotatedMethods(Test.class)) {
            for (Benchmark benchmark : BenchmarkDefinition.getAllBenchmarks()) {
                result.add(new FrameworkMethodWithParameter(method, benchmark));
            }
        }
        return result;
    }

    @Override
    protected void validateTestMethods(List<Throwable> errors) {}
}
