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

package android.tools.device.flicker.rules

import android.platform.test.rule.TestWatcher
import android.tools.common.CrossPlatform
import android.tools.common.FLICKER_TAG
import android.tools.common.TimestampFactory
import android.tools.device.flicker.FlickerServiceResultsCollector
import android.tools.device.flicker.FlickerServiceTracesCollector
import android.tools.device.flicker.IFlickerServiceResultsCollector
import android.tools.device.flicker.annotation.FlickerTest
import android.tools.device.traces.ANDROID_LOGGER
import android.tools.device.traces.formatRealTimestamp
import android.tools.device.traces.getDefaultFlickerOutputDir
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth
import org.junit.AssumptionViolatedException
import org.junit.runner.Description
import org.junit.runner.notification.Failure

/**
 * A test rule that runs Flicker as a Service on the tests this rule is applied to.
 *
 * Note there are performance implications to using this test rule in tests. Tracing will be enabled
 * during the test which will slow down everything. So if the test is performance critical then an
 * alternative should be used.
 *
 * @see TODO for examples on how to use this test rule in your own tests
 */
open class FlickerServiceRule
@JvmOverloads
constructor(
    enabled: Boolean = true,
    failTestOnFaasFailure: Boolean = enabled,
    private val metricsCollector: IFlickerServiceResultsCollector =
        FlickerServiceResultsCollector(
            tracesCollector = FlickerServiceTracesCollector(getDefaultFlickerOutputDir()),
            instrumentation = InstrumentationRegistry.getInstrumentation()
        ),
) : TestWatcher() {
    private val enabled: Boolean =
        InstrumentationRegistry.getArguments().getString("faas:enabled")?.let { it.toBoolean() }
            ?: enabled

    private val failTestOnFaasFailure: Boolean =
        InstrumentationRegistry.getArguments().getString("faas:failTestOnFaasFailure")?.let {
            it.toBoolean()
        }
            ?: failTestOnFaasFailure

    init {
        CrossPlatform.setLogger(ANDROID_LOGGER)
            .setTimestampFactory(TimestampFactory { formatRealTimestamp(it) })
    }

    /** Invoked when a test is about to start */
    public override fun starting(description: Description) {
        if (shouldRun(description)) {
            handleStarting(description)
        }
    }

    /** Invoked when a test succeeds */
    public override fun succeeded(description: Description) {
        if (shouldRun(description)) {
            handleSucceeded(description)
        }
    }

    /** Invoked when a test fails */
    public override fun failed(e: Throwable?, description: Description) {
        if (shouldRun(description)) {
            handleFailed(e, description)
        }
    }

    /** Invoked when a test is skipped due to a failed assumption. */
    public override fun skipped(e: AssumptionViolatedException, description: Description) {
        if (shouldRun(description)) {
            handleSkipped(e, description)
        }
    }

    /** Invoked when a test method finishes (whether passing or failing) */
    public override fun finished(description: Description) {
        if (shouldRun(description)) {
            handleFinished(description)
        }
    }

    private fun handleStarting(description: Description) {
        CrossPlatform.log.i(LOG_TAG, "Test starting $description")
        metricsCollector.testStarted(description)
    }

    private fun handleSucceeded(description: Description) {
        CrossPlatform.log.i(LOG_TAG, "Test succeeded $description")
    }

    private fun handleFailed(e: Throwable?, description: Description) {
        CrossPlatform.log.e(LOG_TAG, "$description test failed  with $e")
        metricsCollector.testFailure(Failure(description, e))
    }

    private fun handleSkipped(e: AssumptionViolatedException, description: Description) {
        CrossPlatform.log.i(LOG_TAG, "Test skipped $description with $e")
        metricsCollector.testSkipped(description)
    }

    private fun shouldRun(description: Description): Boolean {
        // Only run FaaS if test rule is enabled and on tests with FlickerTest annotation if it's
        // used within the class, otherwise run on all tests
        return enabled &&
            (testClassHasFlickerTestAnnotations(description.testClass) ||
                description.annotations.none { it is FlickerTest })
    }

    private fun testClassHasFlickerTestAnnotations(testClass: Class<*>): Boolean {
        return testClass.methods.flatMap { it.annotations.asList() }.any { it is FlickerTest }
    }

    private fun handleFinished(description: Description) {
        CrossPlatform.log.i(LOG_TAG, "Test finished $description")
        metricsCollector.testFinished(description)
        if (metricsCollector.executionErrors.isNotEmpty()) {
            for (executionError in metricsCollector.executionErrors) {
                CrossPlatform.log.e(LOG_TAG, "FaaS reported execution errors", executionError)
            }
            throw metricsCollector.executionErrors[0]
        }
        if (failTestOnFaasFailure && testContainsFlicker(description)) {
            throw metricsCollector.resultsForTest(description).first { it.failed }.assertionError
                ?: error("Unexpectedly missing assertion error")
        }
        val flickerTestAnnotation: FlickerTest? =
            description.annotations.filterIsInstance<FlickerTest>().firstOrNull()
        if (failTestOnFaasFailure && flickerTestAnnotation != null) {
            val detectedScenarios = metricsCollector.detectedScenariosForTest(description)
            Truth.assertThat(detectedScenarios)
                .containsAtLeastElementsIn(flickerTestAnnotation.expected)
        }
    }

    private fun testContainsFlicker(description: Description): Boolean {
        val resultsForTest = metricsCollector.resultsForTest(description)
        return resultsForTest.any { it.failed }
    }

    companion object {
        const val LOG_TAG = "$FLICKER_TAG-ServiceRule"
    }
}
