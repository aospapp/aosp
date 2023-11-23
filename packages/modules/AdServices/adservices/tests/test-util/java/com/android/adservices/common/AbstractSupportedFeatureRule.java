/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.adservices.common;

import android.annotation.Nullable;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

// TODO(b/284971005): move to module-utils
// TODO(b/284971005): add examples
// TODO(b/284971005): add unit tests
/**
 * Rule used to properly check a test behavior depending on whether the device supports a given
 * feature.
 *
 * <p>This rule is abstract so subclass can define what a "feature" means. It also doesn't have any
 * dependency on Android code, so it can be used both on device-side and host-side tests.
 */
public abstract class AbstractSupportedFeatureRule implements TestRule {

    /** Defines the rule behavior. */
    public enum Mode {
        /**
         * All tests are assumed to be running in a device that supports the feature, unless they
         * are annotated otherwise (by annotations defined by {@link
         * AbstractSupportedFeatureRule#isFeatureNotSupportedAnnotation(Annotation)}).
         */
        SUPPORTED_BY_DEFAULT,

        /**
         * All tests are assumed to be running in a device that does not support the platform,
         * unless they are annotated otherwise (by annotations defined by {@link
         * AbstractSupportedFeatureRule#isFeatureSupportedAnnotation(Annotation)}).
         */
        NOT_SUPPORTED_BY_DEFAULT,

        /**
         * The behavior of each test is defined by the annotations (defined by {@link
         * AbstractSupportedFeatureRule#isFeatureSupportedAnnotation(Annotation)} and {@link
         * AbstractSupportedFeatureRule#isFeatureNotSupportedAnnotation(Annotation)}).
         *
         * <p>The annotations could be defined in the test method itself, its class, or its
         * superclasses - the method annotations have higher priority, then the class, and so on...
         */
        ANNOTATION_ONLY
    }

    private final Mode mMode;

    /** Default constructor. */
    public AbstractSupportedFeatureRule(Mode mode) {
        mMode = Objects.requireNonNull(mode);
        logD("Constructor: mode=%s", mode);
    }

    // NOTE: ideally should be final and provide proper hooks for subclasses, but we might make it
    // non-final in the feature if needed
    @Override
    public final Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                boolean isFeatureSupported = isFeatureSupported();
                boolean isTestSupported = isTestSupported(description);
                logD(
                        "Evaluating %s when feature supported is %b and test supported is %b",
                        description, isFeatureSupported, isTestSupported);

                if (isTestSupported) {
                    if (!isFeatureSupported) {
                        throwFeatureNotSupportedAVE();
                    }
                } else {
                    if (isFeatureSupported) {
                        throwFeatureSupportedAVE();
                    }
                }
                Throwable thrown = null;
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    thrown = t;
                }
                logD("Base evaluated: thrown=%s", thrown);
                if (isTestSupported) {
                    if (thrown != null) {
                        throw thrown;
                    }
                } else {
                    if (thrown == null) {
                        throwUnsupporteTestDidntThrowExpectedExceptionError();
                    }
                    assertUnsupportedTestThrewRightException(thrown);
                }
            }
        };
    }

    private boolean isTestSupported(Description description) {
        // First, check the annotations in the test itself
        Boolean supported =
                isTestSupportedByAnnotations(
                        description.getMethodName(), description.getAnnotations());
        if (supported != null) {
            return supported;
        }

        // Then in the test class...
        Class<?> clazz = description.getTestClass();
        do {
            supported =
                    isTestSupportedByAnnotations(
                            clazz.getName(), Arrays.asList(clazz.getAnnotations()));
            if (supported != null) {
                return supported;
            }
            // ...and its superclasses
            clazz = clazz.getSuperclass();
        } while (clazz != null);

        // Finally, check the mode
        switch (mMode) {
            case SUPPORTED_BY_DEFAULT:
                logV("isTestSupported(): no annotation found, returning true by default");
                return true;
            case NOT_SUPPORTED_BY_DEFAULT:
                logV("isTestSupported(): no annotation found, returning false by default");
                return false;
            case ANNOTATION_ONLY:
                throw new IllegalStateException(
                        "No annotation found on "
                                + description
                                + ", its class, or its superclasses");
        }

        return true;
    }

    @Nullable
    private Boolean isTestSupportedByAnnotations(String where, Collection<Annotation> annotations) {
        // TODO(b/284971005): should scan all annotations (instead of returning when one is found)
        // to make sure it doesn't have both supported and unsupported (but would unit unit tests
        // to do so)
        for (Annotation annotation : annotations) {
            if (isFeatureSupportedAnnotation(annotation)) {
                logV(
                        "isTestSupported(%s, %s): found 'supported' annotation %s, returning true",
                        where, annotations, annotation);
                return true;
            }
            if (isFeatureNotSupportedAnnotation(annotation)) {
                logV(
                        "isTestSupported(%s, %s): found 'unsupported' annotation %s, returning"
                                + " false",
                        where, annotations, annotation);
                return false;
            }
        }
        logV("isTestSupported(%s, %s): found no annotation returning null", where, annotations);
        return null;
    }

    /**
     * Returns whether the given annotation indicates that the test should run in a device that
     * supports the feature.
     */
    protected boolean isFeatureSupportedAnnotation(Annotation annotation) {
        logW("%s didn't override isFeatureSupportedAnnotation(); returning false", getClass());
        return false;
    }

    /**
     * Returns whether the given annotation indicates that the test should run in a device that does
     * not support the feature.
     */
    protected boolean isFeatureNotSupportedAnnotation(Annotation annotation) {
        logW("%s didn't override isFeatureNotSupportedAnnotation(); returning false", getClass());
        return false;
    }

    /**
     * Called before the test is run, when the device doesn't support the feature and the test
     * requires it.
     *
     * <p>By the default throws a {@link AssumptionViolatedException} with a generic message.
     */
    protected void throwFeatureNotSupportedAVE() {
        throw new AssumptionViolatedException("Device doesn't support the feature");
    }

    /**
     * Called before the test is run, when the device supports the feature and the test requires it
     * to not be supported.
     *
     * <p>By the default throws a {@link AssumptionViolatedException} with a generic message.
     */
    protected void throwFeatureSupportedAVE() {
        throw new AssumptionViolatedException("Device supports the feature");
    }

    /**
     * Called after the test is run, when the code under test was expected to throw an exception
     * because the device doesn't support the feature, but the test didn't thrown any exception.
     *
     * <p>By the default throws a {@link AssertionError} with a generic message.
     */
    protected void throwUnsupporteTestDidntThrowExpectedExceptionError() {
        throw new AssertionError(
                "test should have thrown an UnsupportedOperationException, but didn't throw any");
    }

    /**
     * Called after the test threw an exception when running in a device that doesn't support the
     * feature - it must verify that the exception is the expected one (for example, right type) and
     * throw an {@link AssertionError} if it's not.
     *
     * <p>By the default it checks that the exception is a {@link UnsupportedOperationException}.
     */
    protected void assertUnsupportedTestThrewRightException(Throwable thrown) {
        if (thrown instanceof UnsupportedOperationException) {
            logD("test threw UnsupportedOperationException as expected: %s", thrown);
            return;
        }
        throw new AssertionError(
                "test should have thrown an UnsupportedOperationException, but instead threw "
                        + thrown,
                thrown);
    }

    /** Convenience method to log an error message. */
    @FormatMethod
    protected final void logE(@FormatString String msgFmt, @Nullable Object... msgArgs) {
        log(LogLevel.WARNING, msgFmt, msgArgs);
    }

    /** Convenience method to log a warning message. */
    @FormatMethod
    protected final void logW(@FormatString String msgFmt, @Nullable Object... msgArgs) {
        log(LogLevel.WARNING, msgFmt, msgArgs);
    }

    /** Convenience method to log a info message. */
    @FormatMethod
    protected final void logI(@FormatString String msgFmt, @Nullable Object... msgArgs) {
        log(LogLevel.INFO, msgFmt, msgArgs);
    }

    /** Convenience method to log a debug message. */
    @FormatMethod
    protected final void logD(@FormatString String msgFmt, @Nullable Object... msgArgs) {
        log(LogLevel.DEBUG, msgFmt, msgArgs);
    }

    /** Convenience method to log a verbose message. */
    @FormatMethod
    protected final void logV(@FormatString String msgFmt, @Nullable Object... msgArgs) {
        log(LogLevel.VERBOSE, msgFmt, msgArgs);
    }

    /** Logs a message in the given level. */
    @FormatMethod
    protected abstract void log(
            LogLevel level, @FormatString String msgFmt, @Nullable Object... msgArgs);

    /** Checks if the device supports the feature. */
    abstract boolean isFeatureSupported();

    /**
     * Defines the log level used on {@link AbstractSupportedFeatureRule#log(LogLevel, String,
     * Object...)}
     */
    protected enum LogLevel {
        ERROR,
        WARNING,
        INFO,
        DEBUG,
        VERBOSE
    }
}
