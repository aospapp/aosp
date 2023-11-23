/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.platform.test.longevity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;
import static java.lang.Math.abs;

import android.os.Bundle;
import android.platform.test.longevity.proto.Configuration.Scenario;
import android.platform.test.longevity.proto.Configuration.Scenario.AfterTest;
import android.platform.test.longevity.proto.Configuration.Scenario.ExtraArg;
import android.platform.test.longevity.samples.testing.SampleProfileSuite;
import androidx.test.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.JUnit4;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestTimedOutException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.exceptions.base.MockitoAssertionError;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;


/** Unit tests for the {@link ScheduledScenarioRunner} runner. */
@RunWith(JUnit4.class)
public class ScheduledScenarioRunnerTest {

    @Mock private RunNotifier mRunNotifier;

    private static final String ASSERTION_FAILURE_MESSAGE = "Test assertion failed";

    public static class ArgumentTest {
        public static final String TEST_ARG = "test-arg-test-only";
        public static final String TEST_ARG_DEFAULT = "default";
        public static final String TEST_ARG_OVERRIDE = "not default";

        @Before
        public void setUp() {
            // The actual argument testing happens here as this is where instrumentation args are
            // parsed in the CUJs.
            String argValue =
                    InstrumentationRegistry.getArguments().getString(TEST_ARG, TEST_ARG_DEFAULT);
            Assert.assertEquals(ASSERTION_FAILURE_MESSAGE, argValue, TEST_ARG_OVERRIDE);
        }

        @Test
        public void dummyTest() {
            // Does nothing; always passes.
        }
    }

    // Threshold above which missing a schedule is considered a failure.
    private static final long TIMEOUT_ERROR_MARGIN_MS = 500;

    // Holds the state of the instrumentation args before each test for restoring after, as one test
    // might affect the state of another otherwise.
    // TODO(b/124239142): Avoid manipulating the instrumentation args here.
    private Bundle mArgumentsBeforeTest;

    @Before
    public void setUpSuite() throws InitializationError {
        initMocks(this);
        mArgumentsBeforeTest = InstrumentationRegistry.getArguments();
    }

    @After
    public void restoreSuite() {
        InstrumentationRegistry.registerInstance(
                InstrumentationRegistry.getInstrumentation(), mArgumentsBeforeTest);
    }

    /**
     * Test that an over time test causes a JUnit TestTimedOutException with the correct exception
     * timeout.
     */
    @Test
    public void testOverTimeTest_throwsTestTimedOutException() throws InitializationError {
        ArgumentCaptor<Failure> failureCaptor = ArgumentCaptor.forClass(Failure.class);
        // Set a over time test with a 5-second window that will idle until the end of the window is
        // reached.
        long timeoutMs = TimeUnit.SECONDS.toMillis(5);
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(SampleProfileSuite.LongIdleTest.class.getName())
                        .setAfterTest(AfterTest.STAY_IN_APP)
                        .build();
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                SampleProfileSuite.LongIdleTest.class,
                                testScenario,
                                timeoutMs,
                                true));
        runner.run(mRunNotifier);
        // Verify that a TestTimedOutException is fired and that the timeout is correct.
        verify(mRunNotifier, atLeastOnce()).fireTestFailure(failureCaptor.capture());
        List<Failure> failures = failureCaptor.getAllValues();
        boolean correctTestTimedOutExceptionFired =
                failures.stream()
                        .anyMatch(
                                f -> {
                                    if (!(f.getException() instanceof TestTimedOutException)) {
                                        return false;
                                    }
                                    TestTimedOutException exception =
                                            (TestTimedOutException) f.getException();
                                    long exceptionTimeout =
                                            exception
                                                    .getTimeUnit()
                                                    .toMillis(exception.getTimeout());
                                    long expectedTimeout =
                                            timeoutMs - ScheduledScenarioRunner.ENDTIME_LEEWAY_MS;
                                    return abs(exceptionTimeout - expectedTimeout)
                                            <= TIMEOUT_ERROR_MARGIN_MS;
                                });
        Assert.assertTrue(correctTestTimedOutExceptionFired);
    }

    /** Test that an over time test does not idle before teardown. */
    @Test
    public void testOverTimeTest_doesNotIdleBeforeTeardown() throws InitializationError {
        // Set a over time test with a 5-second window that will idle until the end of the window is
        // reached.
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(SampleProfileSuite.LongIdleTest.class.getName())
                        .setAfterTest(AfterTest.STAY_IN_APP)
                        .build();
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                SampleProfileSuite.LongIdleTest.class,
                                testScenario,
                                TimeUnit.SECONDS.toMillis(5),
                                true));
        runner.run(mRunNotifier);
        // There should not be idle before teardown as the test should not have left itself enough
        // time for that.
        verify(runner, never()).performIdleBeforeTeardown(anyLong());
    }

    /** Test that an over time test still idles until tne next scenario is supposed to begin. */
    @Test
    public void testOverTimeTest_idlesAfterTeardownUntilNextScenario() throws InitializationError {
        // Set a over time test with a 5-second window that will idle until the end of the window is
        // reached.
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(SampleProfileSuite.LongIdleTest.class.getName())
                        .setAfterTest(AfterTest.STAY_IN_APP)
                        .build();
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                SampleProfileSuite.LongIdleTest.class,
                                testScenario,
                                TimeUnit.SECONDS.toMillis(5),
                                true));
        runner.run(mRunNotifier);
        // Verify that it still idles until the next scenario; duration should be roughly equal to
        // the leeway set in @{link ScheduledScenarioRunner}.
        verify(runner, times(1))
                .performIdleBeforeNextScenario(
                        getWithinMarginMatcher(
                                ScheduledScenarioRunner.ENDTIME_LEEWAY_MS,
                                TIMEOUT_ERROR_MARGIN_MS));
    }

    /** Test that a test set to stay in the app after the test idles after its @Test method. */
    @Test
    public void testRespectsAfterTestPolicy_stayInApp() throws InitializationError {
        // Set a passing test with a 5-second timeout that will idle after its @Test method and
        // idle until the end of the timeout is reached.
        long timeoutMs = TimeUnit.SECONDS.toMillis(5);
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(SampleProfileSuite.PassingTest.class.getName())
                        .setAfterTest(AfterTest.STAY_IN_APP)
                        .build();
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                SampleProfileSuite.PassingTest.class,
                                testScenario,
                                timeoutMs,
                                true));
        runner.run(mRunNotifier);
        // Idles before teardown; duration should be roughly equal to the timeout minus the leeway
        // set in {@link ScheduledScenarioRunner}.
        verify(runner, times(1))
                .performIdleBeforeTeardown(
                        getWithinMarginMatcher(
                                timeoutMs - ScheduledScenarioRunner.ENDTIME_LEEWAY_MS,
                                TIMEOUT_ERROR_MARGIN_MS));
    }

    /** Test that a test set to exit the app after the test does not idle after its @Test method. */
    @Test
    public void testRespectsAfterTestPolicy_exit() throws InitializationError {
        // Set a passing test with a 5-second timeout that does not idle after its @Test method and
        // will idle until the end of the timeout is reached.
        long timeoutMs = TimeUnit.SECONDS.toMillis(5);
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(SampleProfileSuite.PassingTest.class.getName())
                        .setAfterTest(AfterTest.EXIT)
                        .build();
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                SampleProfileSuite.PassingTest.class,
                                testScenario,
                                timeoutMs,
                                true));
        runner.run(mRunNotifier);
        // There should not be idle before teardown.
        verify(runner, never()).performIdleBeforeTeardown(anyLong());
        // Idles before the next scenario; duration should be roughly equal to the timeout.
        verify(runner, times(1))
                .performIdleBeforeNextScenario(
                        getWithinMarginMatcher(timeoutMs, TIMEOUT_ERROR_MARGIN_MS));
    }

    /** Test that an ignored scenario still includes the timeout dictated in a profile. */
    @Test
    public void testIgnoredScenario_doesIdle() throws InitializationError, Exception {
        long timeoutMs = TimeUnit.SECONDS.toMillis(5);
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(SampleProfileSuite.PassingTest.class.getName())
                        .setAfterTest(AfterTest.EXIT)
                        .build();
        Bundle ignores = new Bundle();
        ignores.putString(
                LongevityClassRunner.FILTER_OPTION,
                SampleProfileSuite.PassingTest.class.getCanonicalName());
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                SampleProfileSuite.PassingTest.class,
                                testScenario,
                                timeoutMs,
                                true,
                                ignores));
        RunNotifier notifier = spy(new RunNotifier());
        RunListener listener = mock(RunListener.class);
        notifier.addListener(listener);
        runner.run(notifier);
        // There should not be idle before teardown.
        verify(runner, never()).performIdleBeforeTeardown(anyLong());
        // Ensure the test was ignored via listener.
        verify(listener, times(1)).testIgnored(any());
        // Idles before the next scenario; duration should be roughly equal to the timeout.
        verify(runner, times(1))
                .performIdleBeforeNextScenario(
                        getWithinMarginMatcher(timeoutMs, TIMEOUT_ERROR_MARGIN_MS));
    }

    /** Test that the last test does not have idle after it, regardless of its AfterTest policy. */
    @Test
    public void testLastScenarioDoesNotIdle() throws InitializationError {
        // Set a passing test with a 5-second timeout that is set to idle after its @Test method and
        // but should not idle as it will be the last test in practice.
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(SampleProfileSuite.PassingTest.class.getName())
                        .setAfterTest(AfterTest.STAY_IN_APP)
                        .build();
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                SampleProfileSuite.PassingTest.class,
                                testScenario,
                                TimeUnit.SECONDS.toMillis(5),
                                false));
        runner.run(mRunNotifier);
        // There should not be idle of any form.
        verify(runner, never()).performIdleBeforeTeardown(anyLong());
        verify(runner, never()).performIdleBeforeNextScenario(anyLong());
    }


    /** Test that the "extras" in a scenario is properly registered before the test. */
    @Test
    public void testExtraArgs_registeredBeforeTest() throws Throwable {
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(ArgumentTest.class.getName())
                        .setAfterTest(AfterTest.STAY_IN_APP)
                        .addExtras(
                                ExtraArg.newBuilder()
                                        .setKey(ArgumentTest.TEST_ARG)
                                        .setValue(ArgumentTest.TEST_ARG_OVERRIDE))
                        .build();
        ScheduledScenarioRunner runner =
                spy(
                        new ScheduledScenarioRunner(
                                ArgumentTest.class,
                                testScenario,
                                TimeUnit.SECONDS.toMillis(5),
                                false));
        runner.run(mRunNotifier);
        verifyForAssertionFailures(mRunNotifier);
    }

    /** Test that the "extras" in a scenario is properly un-registered after the test. */
    @Test
    public void testExtraArgs_unregisteredAfterTest() throws Throwable {
        Bundle argsBeforeTest = InstrumentationRegistry.getArguments();
        Scenario testScenario =
                Scenario.newBuilder()
                        .setAt("00:00:00")
                        .setJourney(ArgumentTest.class.getName())
                        .setAfterTest(AfterTest.STAY_IN_APP)
                        .addExtras(
                                ExtraArg.newBuilder()
                                        .setKey(ArgumentTest.TEST_ARG)
                                        .setValue(ArgumentTest.TEST_ARG_OVERRIDE))
                        .build();
        ScheduledScenarioRunner runner =
                new ScheduledScenarioRunner(
                        ArgumentTest.class, testScenario, TimeUnit.SECONDS.toMillis(5), false);
        runner.run(mRunNotifier);
        Bundle argsAfterTest = InstrumentationRegistry.getArguments();
        Assert.assertTrue(bundlesContainSameStringKeyValuePairs(argsBeforeTest, argsAfterTest));
    }

    /**
     * Helper method to get an argument matcher that checks whether the input value is equal to
     * expected value within a margin.
     */
    private long getWithinMarginMatcher(long expected, long margin) {
        return longThat(duration -> abs(duration - expected) <= margin);
    }

    /**
     * Verify that no test failure is fired because of an assertion failure in the stubbed methods.
     * If the verfication fails, check whether it's due the injected assertions failing. If yes,
     * throw that exception out; otherwise, throw the first exception.
     */
    private void verifyForAssertionFailures(final RunNotifier notifier) throws Throwable {
        try {
            verify(notifier, never()).fireTestFailure(any());
        } catch (MockitoAssertionError e) {
            ArgumentCaptor<Failure> failureCaptor = ArgumentCaptor.forClass(Failure.class);
            verify(notifier, atLeastOnce()).fireTestFailure(failureCaptor.capture());
            List<Failure> failures = failureCaptor.getAllValues();
            // Go through the failures, look for an known failure case from the above exceptions
            // and throw the exception in the first one out if any.
            for (Failure failure : failures) {
                if (failure.getException().getMessage().contains(ASSERTION_FAILURE_MESSAGE)) {
                    throw failure.getException();
                }
            }
            // Otherwise, throw the exception from the first failure reported.
            throw failures.get(0).getException();
        }
    }

    /**
     * Helper method to check whether two {@link Bundle}s are equal since the built-in {@code
     * equals} is not properly overriden.
     */
    private boolean bundlesContainSameStringKeyValuePairs(Bundle b1, Bundle b2) {
        if (b1.size() != b2.size()) {
            return false;
        }
        HashSet<String> allKeys = new HashSet<String>(b1.keySet());
        allKeys.addAll(b2.keySet());
        for (String key : allKeys) {
            if (b1.getString(key) != null) {
                // If key is in b1 and corresponds to a string, check whether this key corresponds
                // to the same value in b2.
                if (!b1.getString(key).equals(b2.getString(key))) {
                    return false;
                }
            } else if (b2.getString(key) != null) {
                // Otherwise if b2 has a string at this key, return false since we know that b1 does
                // not have a string at this key.
                return false;
            }
        }
        return true;
    }
}
