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

package android.platform.test.rule;

import android.os.Build;

import androidx.test.InstrumentationRegistry;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/**
 * Rule that runs tests marked with @Platinum on Platinum test runs, only on devices listed in the
 * comma-separated string passed as an argument to the @Platinum annotation. The test will be
 * skipped, for Platinum runs, on devices not in the list.
 */
public class PlatinumRule implements TestRule {
    @Override
    public Statement apply(Statement base, Description description) {
        // If the test is not annotated with @Platinum, this rule is not applicable.
        final Platinum annotation = description.getTestClass().getAnnotation(Platinum.class);
        if (annotation == null) return base;

        // If the test suite isn't running with
        // "exclude-annotation": "androidx.test.filters.FlakyTest", then this is not a platinum
        // test, and the rule is not applicable.
        final String nonAnnotationArgument =
                InstrumentationRegistry.getArguments().getString("notAnnotation", "");
        if (!Arrays.stream(nonAnnotationArgument.split(","))
                .anyMatch("androidx.test.filters.FlakyTest"::equals)) {
            return base;
        }

        // If the target IS listed in the annotation's parameter, this rule is not applicable.
        final boolean match = Arrays.asList(annotation.value().split(",")).contains(Build.PRODUCT);
        if (match) return base;

        // The test will be skipped upon start.
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                throw new AssumptionViolatedException(
                        "Skipping the test on target "
                                + Build.PRODUCT
                                + " which in not in "
                                + annotation.value());
            }
        };
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    public @interface Platinum {
        String value();
    }
}
