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

import static java.lang.Math.max;

import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.longevity.proto.Configuration.Scenario;
import android.platform.test.longevity.proto.Configuration.Scenario.ExtraArg;
import androidx.annotation.VisibleForTesting;
import androidx.test.InstrumentationRegistry;

import java.util.List;

import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * A {@link BlockJUnit4ClassRunner} that runs a test class with a specified timeout and optionally
 * performs an idle before teardown (staying inside the app for Android CUJs).
 */
public class ScheduledScenarioRunner extends LongevityClassRunner {
    @VisibleForTesting static final long ENDTIME_LEEWAY_MS = 3000;

    private final Scenario mScenario;
    private final long mTotalTimeoutMs;
    private final boolean mShouldIdle;
    private final Bundle mArguments;

    private long mStartTimeMs;

    public ScheduledScenarioRunner(
            Class<?> klass, Scenario scenario, long timeout, boolean shouldIdle)
            throws InitializationError {
        this(klass, scenario, timeout, shouldIdle, InstrumentationRegistry.getArguments());
    }

    @VisibleForTesting
    ScheduledScenarioRunner(
            Class<?> klass, Scenario scenario, long timeout, boolean shouldIdle, Bundle arguments)
            throws InitializationError {
        super(klass, arguments);
        mScenario = scenario;
        // Ensure that the timeout is non-negative.
        mTotalTimeoutMs = max(timeout, 0);
        mShouldIdle = shouldIdle;
        mArguments = arguments;
    }

    @Override
    protected List<TestRule> getTestRules(Object target) {
        List<TestRule> rules = super.getTestRules(target);
        // Ensure that the timeout rule has a non-negative timeout.
        rules.add(0, Timeout.millis(max(mTotalTimeoutMs - ENDTIME_LEEWAY_MS, 0)));
        return rules;
    }

    @Override
    protected Statement withAfters(FrameworkMethod method, Object target, Statement statement) {
        Statement withIdle =
                new Statement() {
                    @Override
                    public void evaluate() throws Throwable {
                        try {
                            // Run the underlying test and report exceptions.
                            statement.evaluate();
                        } finally {
                            // If there is time left for idling (i.e. more than ENDTIME_LEEWAY_MS),
                            // and the scenario is set to stay in app, idle for the remainder of
                            // its timeout window until ENDTIME_LEEWAY_MS before the start time of
                            // the next scenario, before executing the scenario's @After methods.
                            // The above does not apply if current scenario is the last one, in
                            // which case the idle is never performed regardless of its after_test
                            // policy.
                            if (mShouldIdle
                                    && mScenario
                                            .getAfterTest()
                                            .equals(Scenario.AfterTest.STAY_IN_APP)) {
                                performIdleBeforeTeardown(
                                        max(getTimeRemaining() - ENDTIME_LEEWAY_MS, 0));
                            }
                        }
                    }
                };
        return super.withAfters(method, target, withIdle);
    }

    @Override
    protected void runChild(final FrameworkMethod method, RunNotifier notifier) {
        mStartTimeMs = System.currentTimeMillis();
        // Keep a copy of the bundle arguments for restoring later.
        Bundle modifiedArguments = mArguments.deepCopy();
        for (ExtraArg argPair : mScenario.getExtrasList()) {
            if (argPair.getKey() == null || argPair.getValue() == null) {
                throw new IllegalArgumentException(
                        String.format(
                                "Each extra arg entry in scenario must have both a key and a value,"
                                        + " but scenario is %s.",
                                mScenario.toString()));
            }
            modifiedArguments.putString(argPair.getKey(), argPair.getValue());
        }
        InstrumentationRegistry.registerInstance(
                InstrumentationRegistry.getInstrumentation(), modifiedArguments);
        super.runChild(method, notifier);
        // Restore the arguments to the state prior to the scenario.
        InstrumentationRegistry.registerInstance(
                InstrumentationRegistry.getInstrumentation(), mArguments);
        // If there are remaining scenarios, idle until the next one starts.
        if (mShouldIdle) {
            performIdleBeforeNextScenario(getTimeRemaining());
        }
    }

    /**
     * Get the duration to idle after the current scenario. If the current scenario is the last one
     * in the profile, returns 0.
     */
    private long getTimeRemaining() {
        // The idle time is total time minus time elapsed since the current scenario started.
        return max(mTotalTimeoutMs - (System.currentTimeMillis() - mStartTimeMs), 0);
    }

    @VisibleForTesting
    protected void performIdleBeforeTeardown(long durationMs) {
        idleWithSystemClockSleep(durationMs);
    }

    @VisibleForTesting
    protected void performIdleBeforeNextScenario(long durationMs) {
        // TODO (b/119386011): Change this idle method to using a sleep test; for now, using the
        // same idling logic as {@link performIdleBeforeTeardown}.
        idleWithSystemClockSleep(durationMs);
    }

    private void idleWithSystemClockSleep(long durationMs) {
        if (durationMs <= 0) {
            return;
        }
        SystemClock.sleep(durationMs);
    }
}
