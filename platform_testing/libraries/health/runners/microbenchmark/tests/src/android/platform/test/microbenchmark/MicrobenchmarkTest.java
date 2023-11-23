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

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.platform.test.rule.TracePointRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for the {@link Microbenchmark} runner.
 */
@RunWith(JUnit4.class)
public final class MicrobenchmarkTest {
    /**
     * Tests that iterations are respected for microbenchmark tests.
     */
    @Test
    public void testIterationCount() throws InitializationError {
        Bundle args = new Bundle();
        args.putString("iterations", "10");
        Microbenchmark microbench = new Microbenchmark(BasicTest.class, args);
        assertThat(microbench.testCount()).isEqualTo(10);
    }

    public static class BasicTest {
        @Test
        public void doNothingTest() { }
    }

    /**
     * Tests that {@link TracePointRule} and {@link TightMethodRule}s are properly ordered.
     *
     * Before --> TightBefore --> Trace (begin) --> Test --> Trace(end) --> TightAfter --> After
     */
    @Test
    public void testFeatureExecutionOrder() throws InitializationError {
        LoggingMicrobenchmark loggingRunner = new LoggingMicrobenchmark(LoggingTest.class);
        loggingRunner.setOperationLog(new ArrayList<String>());
        Result result = new JUnitCore().run(loggingRunner);
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(loggingRunner.getOperationLog()).containsExactly(
                "before",
                "tight before",
                "begin: testMethod("
                    + "android.platform.test.microbenchmark.MicrobenchmarkTest$LoggingTest)",
                "test",
                "end",
                "tight after",
                "after")
            .inOrder();
    }

    /**
     * Test iterations number are added to the test name with default suffix.
     *
     * Before --> TightBefore --> Trace (begin) with suffix @1 --> Test --> Trace(end)
     *  --> TightAfter --> After --> Before --> TightBefore --> Trace (begin) with suffix @2
     *  --> Test --> Trace(end) --> TightAfter --> After
     */
    @Test
    public void testMultipleIterationsWithRename() throws InitializationError {
        Bundle args = new Bundle();
        args.putString("iterations", "2");
        args.putString("rename-iterations", "true");
        LoggingMicrobenchmark loggingRunner = new LoggingMicrobenchmark(LoggingTest.class, args);
        loggingRunner.setOperationLog(new ArrayList<String>());
        Result result = new JUnitCore().run(loggingRunner);
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(loggingRunner.getOperationLog()).containsExactly(
                "before",
                "tight before",
                "begin: testMethod("
                    + "android.platform.test.microbenchmark.MicrobenchmarkTest$LoggingTest$1)",
                "test",
                "end",
                "tight after",
                "after",
                "before",
                "tight before",
                "begin: testMethod("
                    + "android.platform.test.microbenchmark.MicrobenchmarkTest$LoggingTest$2)",
                "test",
                "end",
                "tight after",
                "after")
            .inOrder();
    }

    /**
     * Test iterations number are added to the test name with custom suffix.
     *
     * Before --> TightBefore --> Trace (begin) with suffix --1 --> Test --> Trace(end)
     *  --> TightAfter --> After --> Before --> TightBefore --> Trace (begin) with suffix --2
     *   --> Test --> Trace(end) --> TightAfter --> After
     */
    @Test
    public void testMultipleIterationsWithDifferentSuffix() throws InitializationError {
        Bundle args = new Bundle();
        args.putString("iterations", "2");
        args.putString("rename-iterations", "true");
        args.putString("iteration-separator", "--");
        LoggingMicrobenchmark loggingRunner = new LoggingMicrobenchmark(LoggingTest.class, args);
        loggingRunner.setOperationLog(new ArrayList<String>());
        Result result = new JUnitCore().run(loggingRunner);
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(loggingRunner.getOperationLog()).containsExactly(
                "before",
                "tight before",
                "begin: testMethod("
                    + "android.platform.test.microbenchmark.MicrobenchmarkTest$LoggingTest--1)",
                "test",
                "end",
                "tight after",
                "after",
                "before",
                "tight before",
                "begin: testMethod("
                    + "android.platform.test.microbenchmark.MicrobenchmarkTest$LoggingTest--2)",
                "test",
                "end",
                "tight after",
                "after")
            .inOrder();
    }

    /**
     * Test iteration number are not added to the test name when explictly disabled.
     *
     * Before --> TightBefore --> Trace (begin) --> Test --> Trace(end) --> TightAfter
     *  --> After
     */
    @Test
    public void testMultipleIterationsWithoutRename() throws InitializationError {
        Bundle args = new Bundle();
        args.putString("iterations", "1");
        args.putString("rename-iterations", "false");
        LoggingMicrobenchmark loggingRunner = new LoggingMicrobenchmark(LoggingTest.class, args);
        loggingRunner.setOperationLog(new ArrayList<String>());
        Result result = new JUnitCore().run(loggingRunner);
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(loggingRunner.getOperationLog())
                .containsExactly(
                        "before",
                        "tight before",
                        "begin: testMethod("
                                + "android.platform.test.microbenchmark.MicrobenchmarkTest"
                                + "$LoggingTest)",
                        "test",
                        "end",
                        "tight after",
                        "after")
                .inOrder();
    }

    /**
     * Test method iteration will iterate the inner-most test method N times.
     *
     * <p>Before --> TightBefore --> Trace (begin) --> Test x N --> Trace(end) --> TightAfter -->
     * After
     */
    @Test
    public void testMultipleMethodIterations() throws InitializationError {
        Bundle args = new Bundle();
        args.putString("iterations", "1");
        args.putString("method-iterations", "10");
        args.putString("rename-iterations", "false");
        LoggingMicrobenchmark loggingRunner = new LoggingMicrobenchmark(LoggingTest.class, args);
        loggingRunner.setOperationLog(new ArrayList<String>());
        Result result = new JUnitCore().run(loggingRunner);
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(loggingRunner.getOperationLog())
                .containsExactly(
                        "before",
                        "tight before",
                        "begin: testMethod("
                                + "android.platform.test.microbenchmark.MicrobenchmarkTest"
                                + "$LoggingTest)",
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        "end",
                        "tight after",
                        "after")
                .inOrder();
    }

    /**
     * An extensions of the {@link Microbenchmark} runner that logs the start and end of collecting
     * traces. It also passes the operation log to the provided test {@code Class}, if it is a
     * {@link LoggingTest}. This is used for ensuring the proper order for evaluating test {@link
     * Statement}s.
     */
    public static class LoggingMicrobenchmark extends Microbenchmark {
        private List<String> mOperationLog;

        public LoggingMicrobenchmark(Class<?> klass) throws InitializationError {
            super(klass);
        }

        LoggingMicrobenchmark(Class<?> klass, Bundle arguments) throws InitializationError {
            super(klass, arguments);
        }

        protected Object createTest() throws Exception {
            Object test = super.createTest();
            if (test instanceof LoggingTest) {
                ((LoggingTest)test).setOperationLog(mOperationLog);
            }
            return test;
        }

        void setOperationLog(List<String> log) {
            mOperationLog = log;
        }

        List<String> getOperationLog() {
            return mOperationLog;
        }

        @Override
        protected TracePointRule getTracePointRule() {
            return new LoggingTracePointRule();
        }

        class LoggingTracePointRule extends TracePointRule {
            @Override
            protected void beginSection(String sectionTag) {
                mOperationLog.add(String.format("begin: %s", sectionTag));
            }

            @Override
            protected void endSection() {
                mOperationLog.add("end");
            }
        }
    }

    /**
     * A test that logs {@link Before}, {@link After}, {@link Test}, and the logging {@link
     * TightMethodRule} included, used in conjunction with {@link LoggingMicrobenchmark} to
     * determine all {@link Statement}s are evaluated in the proper order.
     */
    public static class LoggingTest {
        @Microbenchmark.TightMethodRule
        public TightRule orderRule = new TightRule();

        private List<String> mOperationLog;

        void setOperationLog(List<String> log) {
            mOperationLog = log;
        }

        @Before
        public void beforeMethod() {
            mOperationLog.add("before");
        }

        @Test
        public void testMethod() {
            mOperationLog.add("test");
        }

        @After
        public void afterMethod() {
            mOperationLog.add("after");
        }

        class TightRule implements TestRule {
            @Override
            public Statement apply(Statement base, Description description) {
                return new Statement() {
                    @Override
                    public void evaluate() throws Throwable {
                        mOperationLog.add("tight before");
                        base.evaluate();
                        mOperationLog.add("tight after");
                    }
                };
            }
        }
    }
}
