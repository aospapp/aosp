/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.platform.test.microbenchmark;

import android.os.Bundle;
import android.platform.test.composer.Iterate;
import android.platform.test.rule.TracePointRule;
import androidx.annotation.VisibleForTesting;
import androidx.test.InstrumentationRegistry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.rules.TestRule;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

/**
 * The {@code Microbenchmark} runner allows you to run test methods repeatedly and with {@link
 * TightMethodRule}s in order to reliably measure a specific test method in isolation. Samples are
 * soon to follow.
 */
public class Microbenchmark extends BlockJUnit4ClassRunner {
    private Bundle mArguments;

    /**
     * Called reflectively on classes annotated with {@code @RunWith(Microbenchmark.class)}.
     */
    public Microbenchmark(Class<?> klass) throws InitializationError {
        this(klass, InstrumentationRegistry.getArguments());
    }

    /**
     * Do not call. Called explicitly from tests to provide an arguments.
     */
    @VisibleForTesting
    Microbenchmark(Class<?> klass, Bundle arguments) throws InitializationError {
        super(klass);
        mArguments = arguments;
    }

    /**
     * Returns a {@link Statement} that invokes {@code method} on {@code test}, surrounded by any
     * explicit or command-line-supplied {@link TightMethodRule}s. This allows for tighter {@link
     * TestRule}s that live inside {@link Before} and {@link After} statements.
     */
    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        Statement start = super.methodInvoker(method, test);
        // Wrap the inner-most test method with trace points.
        start = getTracePointRule().apply(start, describeChild(method));
        // Invoke special @TightMethodRules that wrap @Test methods.
        List<TestRule> tightMethodRules =
                getTestClass().getAnnotatedFieldValues(test, TightMethodRule.class, TestRule.class);
        for (TestRule tightMethodRule : tightMethodRules) {
            start = tightMethodRule.apply(start, describeChild(method));
        }
        return start;
    }

    @VisibleForTesting
    protected TracePointRule getTracePointRule() {
        return new TracePointRule();
    }

    /**
     * Returns a list of repeated {@link FrameworkMethod}s to execute.
     */
    @Override
    protected List<FrameworkMethod> getChildren() {
       return new Iterate<FrameworkMethod>().apply(mArguments, super.getChildren());
    }

    /**
     * An annotation for the corresponding tight rules above. These rules are ordered differently
     * from standard JUnit {@link Rule}s because they live between {@link Before} and {@link After}
     * methods, instead of wrapping those methods.
     *
     * <p>In particular, these serve as a proxy for tight metric collection in microbenchmark-style
     * tests, where collection is isolated to just the method under test. This is important for when
     * {@link Before} and {@link After} methods will obscure signal reliability.
     *
     * <p> Currently these are only registered from inside a test class as follows, but should soon
     * be extended for command-line support.
     *
     * ```
     * @RunWith(Microbenchmark.class)
     * public class TestClass {
     *     @TightMethodRule
     *     public ExampleRule exampleRule = new ExampleRule();
     *
     *     @Test
     *     ...
     * }
     * ```
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD})
    public @interface TightMethodRule { }
}
