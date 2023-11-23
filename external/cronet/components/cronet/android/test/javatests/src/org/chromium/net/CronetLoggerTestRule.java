// Copyright 2022 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import androidx.annotation.NonNull;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import org.chromium.net.impl.CronetLogger;
import org.chromium.net.impl.CronetLoggerFactory;

/**
 * Custom TestRule that instantiates a new fake CronetLogger for each test.
 * @param <T> The actual type of the class extending CronetLogger.
 */
public class CronetLoggerTestRule<T extends CronetLogger> implements TestRule {
    private static final String TAG = CronetLoggerTestRule.class.getSimpleName();

    // Expose the fake logger to the test.
    public T mTestLogger;

    public CronetLoggerTestRule(@NonNull T testLogger) {
        if (testLogger == null) {
            throw new NullPointerException("TestLogger is required.");
        }

        mTestLogger = testLogger;
    }

    @Override
    public Statement apply(final Statement base, final Description desc) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try (SwapLoggerForTesting swapper = new SwapLoggerForTesting(mTestLogger)) {
                    base.evaluate();
                }
            }
        };
    }

    /**
     * Utility class to safely use a custom CronetLogger for the duration of a test.
     * To be used within a try-with-resources statement within the test.
     */
    public static class SwapLoggerForTesting implements AutoCloseable {
        /**
         * Forces {@code CronetLoggerFactory#createLogger} to return {@code testLogger} instead of
         * what it would have normally returned.
         */
        public SwapLoggerForTesting(CronetLogger testLogger) {
            CronetLoggerFactory.setLoggerForTesting(testLogger);
        }

        /**
         * Restores CronetLoggerFactory to its original state.
         */
        @Override
        public void close() {
            CronetLoggerFactory.setLoggerForTesting(null);
        }
    }
}
