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
package android.platform.test.longevity;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static java.lang.Math.abs;

import android.app.Instrumentation;
import android.content.Context;
import android.host.test.longevity.listener.TimeoutTerminator;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.longevity.samples.testing.SampleProfileSuite;
import android.platform.test.scenario.annotation.Scenario;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.internal.builders.AllDefaultPossibilitiesBuilder;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite.SuiteClasses;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.TestTimedOutException;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Unit tests for the {@link ProfileSuite} runner. */
@RunWith(JUnit4.class)
public class ProfileSuiteTest {
    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    @Mock private Instrumentation mInstrumentation;
    @Mock private Context mContext;
    @Mock private Profile mProfile;
    private RunNotifier mRunNotifier;

    // Threshold above which missing a schedule is considered a failure.
    private static final long SCHEDULE_LEEWAY_MS = 500;

    @Before
    public void setUpSuite() throws InitializationError {
        initMocks(this);
        mRunNotifier = spy(new RunNotifier());
    }

    /** Test that profile suites with classes that aren't scenarios are rejected. */
    @Test
    public void testRejectInvalidTests_notScenarios() throws InitializationError {
        mExpectedException.expect(InitializationError.class);
        new ProfileSuite(NonScenarioSuite.class, new AllDefaultPossibilitiesBuilder(true),
                mInstrumentation, mContext, new Bundle());
    }

    /** Test that profile suites with classes that aren't scenarios are rejected. */
    @Test
    public void testRejectInvalidTests_notSupportedRunner() throws InitializationError {
        mExpectedException.expect(InitializationError.class);
        new ProfileSuite(InvalidRunnerSuite.class, new AllDefaultPossibilitiesBuilder(true),
                mInstrumentation, mContext, new Bundle());
    }

    /** Test that profile suites with classes that have no runner are rejected. */
    @Test
    public void testRejectInvalidTests_badRunnerBuilder() throws Throwable {
        mExpectedException.expect(InitializationError.class);
        RunnerBuilder builder = spy(new AllDefaultPossibilitiesBuilder(true));
        when(builder.runnerForClass(BasicScenario.class)).thenThrow(new Throwable("empty"));
        new ProfileSuite(BasicSuite.class, builder, mInstrumentation, mContext, new Bundle());
    }

    /** Test that the basic scenario suite is accepted if properly annotated. */
    @Test
    public void testValidScenario_basic() throws InitializationError {
        new ProfileSuite(BasicSuite.class, new AllDefaultPossibilitiesBuilder(true),
                    mInstrumentation, mContext, new Bundle());
    }

    // Scenarios and suites used for the suite validation tests above.

    @RunWith(ProfileSuite.class)
    @SuiteClasses({
        BasicScenario.class,
    })
    public static class BasicSuite {}

    @RunWith(ProfileSuite.class)
    @SuiteClasses({
        NonScenario.class,
    })
    public static class NonScenarioSuite { }

    @RunWith(ProfileSuite.class)
    @SuiteClasses({
        NotSupportedRunner.class,
    })
    public static class InvalidRunnerSuite { }

    @Scenario
    @RunWith(JUnit4.class)
    public static class BasicScenario {
        @Test
        public void testNothing() { }
    }

    // Note: @Scenario annotations are not inherited.
    @RunWith(JUnit4.class)
    public static class NonScenario extends BasicScenario { }

    @Scenario
    @RunWith(Parameterized.class)
    public static class NotSupportedRunner extends BasicScenario {}

    /** Test that a profile's scheduling is followed. */
    @Test
    public void testScheduling_respectsSchedule() throws InitializationError {
        // TODO(harrytczhang@): Find a way to run this without relying on actual idles.

        // Arguments with the profile under test.
        Bundle args = new Bundle();
        args.putString(Profile.PROFILE_OPTION_NAME, "testScheduling_respectsSchedule");
        // Scenario names from the profile.
        final String firstScenarioName =
                "android.platform.test.longevity.samples.testing.SampleProfileSuite$LongIdleTest";
        final String secondScenarioName =
                "android.platform.test.longevity.samples.testing.SampleProfileSuite$PassingTest";
        // Stores the start time of the test run for the suite. Using AtomicLong here as the time
        // should be initialized when run() is called on the suite, but Java does not want
        // assignment to local varaible in lambda expressions. AtomicLong allows for using the
        // same reference but altering the value.
        final AtomicLong runStartTimeMs = new AtomicLong(SystemClock.elapsedRealtime());
        ProfileSuite suite =
                spy(
                        new ProfileSuite(
                                SampleProfileSuite.class,
                                new AllDefaultPossibilitiesBuilder(true),
                                mInstrumentation,
                                mContext,
                                args));
        // Stub the lifecycle calls to verify that tests are run on schedule.
        doAnswer(
                        invocation -> {
                            runStartTimeMs.set(SystemClock.elapsedRealtime());
                            invocation.callRealMethod();
                            return null;
                        })
                .when(suite)
                .run(argThat(notifier -> notifier.equals(mRunNotifier)));
        doAnswer(
                        invocation -> {
                            // The first scenario should start immediately.
                            Assert.assertTrue(
                                    abs(SystemClock.elapsedRealtime() - runStartTimeMs.longValue())
                                            <= SCHEDULE_LEEWAY_MS);
                            invocation.callRealMethod();
                            return null;
                        })
                .when(suite)
                .runChild(
                        argThat(
                                runner ->
                                        runner.getDescription()
                                                .getDisplayName()
                                                .equals(firstScenarioName)),
                        argThat(notifier -> notifier.equals(mRunNotifier)));
        doAnswer(
                        invocation -> {
                            // The second scenario should begin at 00:00:10 - 00:00:01 = 9 seconds.
                            Assert.assertTrue(
                                    abs(
                                                    SystemClock.elapsedRealtime()
                                                            - runStartTimeMs.longValue()
                                                            - TimeUnit.SECONDS.toMillis(9))
                                            <= SCHEDULE_LEEWAY_MS);
                            invocation.callRealMethod();
                            return null;
                        })
                .when(suite)
                .runChild(
                        argThat(
                                runner ->
                                        runner.getDescription()
                                                .getDisplayName()
                                                .equals(secondScenarioName)),
                        argThat(notifier -> notifier.equals(mRunNotifier)));
        InOrder inOrderVerifier = inOrder(suite);

        suite.run(mRunNotifier);
        // Verify that the test run is started.
        inOrderVerifier.verify(suite).run(argThat(notifier -> notifier.equals(mRunNotifier)));
        // Verify that the first scenario is started.
        inOrderVerifier
                .verify(suite)
                .runChild(
                        argThat(
                                runner ->
                                        runner.getDescription()
                                                .getDisplayName()
                                                .equals(firstScenarioName)),
                        argThat(notifier -> notifier.equals(mRunNotifier)));
        // Verify that the second scenario is started.
        inOrderVerifier
                .verify(suite)
                .runChild(
                        argThat(
                                runner ->
                                        runner.getDescription()
                                                .getDisplayName()
                                                .equals(secondScenarioName)),
                        argThat(notifier -> notifier.equals(mRunNotifier)));
    }

    /** Test that a profile's last scenario is bounded by the suite timeout. */
    @Test
    public void testScheduling_respectsSuiteTimeout() throws InitializationError {
        long suiteTimeoutMsecs = TimeUnit.SECONDS.toMillis(10);
        ArgumentCaptor<Failure> failureCaptor = ArgumentCaptor.forClass(Failure.class);

        // Arguments with the profile under test and suite timeout.
        Bundle args = new Bundle();
        args.putString(Profile.PROFILE_OPTION_NAME, "testScheduling_respectsSuiteTimeout");
        args.putString(TimeoutTerminator.OPTION, String.valueOf(suiteTimeoutMsecs));

        // Construct and run the profile suite.
        ProfileSuite suite =
                spy(
                        new ProfileSuite(
                                SampleProfileSuite.class,
                                new AllDefaultPossibilitiesBuilder(true),
                                mInstrumentation,
                                mContext,
                                args));
        suite.run(mRunNotifier);

        // Verify that a TestTimedOutException is fired and the timeout is correct.
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
                                    // Expected timeout the duration from the last scenario to when
                                    // the suite should time out, minus the end time leeway set in
                                    // ScheduledScenarioRunner. Note that the second scenario is
                                    // executed at 00:00:04 as the first scenario is always
                                    // considered to be at 00:00:00.
                                    long expectedTimeout =
                                            suiteTimeoutMsecs
                                                    - TimeUnit.SECONDS.toMillis(4)
                                                    - ScheduledScenarioRunner.ENDTIME_LEEWAY_MS;
                                    return abs(exceptionTimeout - expectedTimeout)
                                            <= SCHEDULE_LEEWAY_MS;
                                });
        Assert.assertTrue(correctTestTimedOutExceptionFired);
    }
}
