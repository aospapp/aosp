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

package android.tools.common.flicker

import android.device.collectors.DataRecord
import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.flicker.assertors.AssertionResult
import android.tools.common.flicker.assertors.IAssertionResult
import android.tools.common.flicker.assertors.IFaasAssertion
import android.tools.common.io.IReader
import android.tools.common.traces.wm.TransitionsTrace
import android.tools.device.flicker.FlickerServiceResultsCollector
import android.tools.device.flicker.FlickerServiceResultsCollector.Companion.FLICKER_ASSERTIONS_COUNT_KEY
import android.tools.device.flicker.FlickerServiceResultsCollector.Companion.WINSCOPE_FILE_PATH_KEY
import android.tools.device.flicker.FlickerServiceResultsCollector.Companion.getKeyForAssertionResult
import android.tools.device.traces.io.InMemoryArtifact
import android.tools.device.traces.io.ParsedTracesReader
import android.tools.utils.KotlinMockito
import android.tools.utils.MockLayersTraceBuilder
import android.tools.utils.MockWindowManagerTraceBuilder
import com.google.common.truth.Truth
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runners.MethodSorters
import org.mockito.Mockito

/**
 * Contains [FlickerServiceResultsCollector] tests. To run this test: `atest
 * FlickerLibTest:FlickerServiceResultsCollectorTest`
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FlickerServiceResultsCollectorTest {
    @Test
    fun reportsMetricsOnlyForPassingTestsIfRequested() {
        val collector = createCollector(reportOnlyForPassingTests = true)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription("TestSuite")
        val testData = DataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestFail(testData, testDescription, Mockito.mock(Failure::class.java))
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isEmpty()
        Truth.assertThat(collector.assertionResultsByTest[testDescription]).isNull()
        Truth.assertThat(runData.hasMetrics()).isFalse()
        Truth.assertThat(testData.hasMetrics()).isFalse()
    }

    @Test
    fun reportsMetricsForFailingTestsIfRequested() {
        val collector =
            createCollector(reportOnlyForPassingTests = false, collectMetricsPerTest = true)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription("TestSuite")
        val testData = DataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestFail(testData, testDescription, Mockito.mock(Failure::class.java))
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isEmpty()
        Truth.assertThat(collector.resultsForTest(testDescription)).isNotEmpty()
        Truth.assertThat(testData.hasMetrics()).isTrue()
        Truth.assertThat(runData.hasMetrics()).isFalse()
    }

    @Test
    fun collectsMetricsForEachTestIfRequested() {
        val collector = createCollector(collectMetricsPerTest = true)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription("TestSuite")
        val testData = DataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isEmpty()
        Truth.assertThat(collector.resultsForTest(testDescription)).isNotEmpty()
        Truth.assertThat(testData.hasMetrics()).isTrue()
        Truth.assertThat(runData.hasMetrics()).isFalse()
    }

    @Test
    fun collectsMetricsForEntireTestRunIfRequested() {
        val collector = createCollector(collectMetricsPerTest = false)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription(this::class.java)
        val testData = DataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isEmpty()
        Truth.assertThat(collector.assertionResults).isNotEmpty()
        Truth.assertThat(testData.hasMetrics()).isFalse()
        Truth.assertThat(runData.hasMetrics()).isTrue()
    }

    @Test
    fun reportsAssertionCountMetric() {
        val assertionResults = listOf(mockSuccessfulAssertionResult, mockFailedAssertionResult)
        val collector = createCollector(assertionResults = assertionResults)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription(this::class.java)
        val testData = SpyDataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isEmpty()
        Truth.assertThat(collector.assertionResults).isNotEmpty()

        Truth.assertThat(testData.hasMetrics()).isTrue()
        Truth.assertThat(testData.stringMetrics).containsKey(FLICKER_ASSERTIONS_COUNT_KEY)
        Truth.assertThat(testData.stringMetrics[FLICKER_ASSERTIONS_COUNT_KEY])
            .isEqualTo("${assertionResults.size}")

        Truth.assertThat(runData.hasMetrics()).isFalse()
    }

    @Test
    fun reportsMetricForTraceFile() {
        val assertionResults = listOf(mockSuccessfulAssertionResult, mockFailedAssertionResult)
        val collector = createCollector(assertionResults = assertionResults)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription(this::class.java)
        val testData = SpyDataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isEmpty()
        Truth.assertThat(collector.assertionResults).isNotEmpty()

        Truth.assertThat(testData.hasMetrics()).isTrue()
        Truth.assertThat(testData.stringMetrics).containsKey(WINSCOPE_FILE_PATH_KEY)
        Truth.assertThat(testData.stringMetrics[WINSCOPE_FILE_PATH_KEY])
            .isEqualTo("IN_MEMORY/Empty")

        Truth.assertThat(runData.hasMetrics()).isFalse()
    }

    @Test
    fun reportsMetricForTraceFileOnServiceFailure() {
        val assertionResults = listOf(mockSuccessfulAssertionResult, mockFailedAssertionResult)
        val collector =
            createCollector(assertionResults = assertionResults, serviceProcessingError = true)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription(this::class.java)
        val testData = SpyDataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isNotEmpty()
        Truth.assertThat(collector.assertionResults).isEmpty()

        Truth.assertThat(testData.hasMetrics()).isTrue()
        Truth.assertThat(testData.stringMetrics).containsKey(WINSCOPE_FILE_PATH_KEY)
        Truth.assertThat(testData.stringMetrics[WINSCOPE_FILE_PATH_KEY])
            .isEqualTo("IN_MEMORY/Empty")

        Truth.assertThat(runData.hasMetrics()).isFalse()
    }

    @Test
    fun reportsDuplicateAssertionsWithIndex() {
        val assertionResults =
            listOf(
                mockSuccessfulAssertionResult,
                mockSuccessfulAssertionResult,
                mockFailedAssertionResult
            )
        val collector = createCollector(assertionResults = assertionResults)
        val runData = DataRecord()
        val runDescription = Description.createSuiteDescription(this::class.java)
        val testData = SpyDataRecord()
        val testDescription = Description.createTestDescription(this::class.java, "TestName")

        collector.onTestRunStart(runData, runDescription)
        collector.onTestStart(testData, testDescription)
        collector.onTestEnd(testData, testDescription)
        collector.onTestRunEnd(runData, Mockito.mock(org.junit.runner.Result::class.java))

        Truth.assertThat(collector.executionErrors).isEmpty()
        Truth.assertThat(collector.assertionResults).isNotEmpty()

        Truth.assertThat(testData.hasMetrics()).isTrue()
        Truth.assertThat(testData.stringMetrics).containsKey(WINSCOPE_FILE_PATH_KEY)
        Truth.assertThat(testData.stringMetrics[WINSCOPE_FILE_PATH_KEY])
            .isEqualTo("IN_MEMORY/Empty")

        Truth.assertThat(testData.stringMetrics).containsKey(FLICKER_ASSERTIONS_COUNT_KEY)
        Truth.assertThat(testData.stringMetrics[FLICKER_ASSERTIONS_COUNT_KEY])
            .isEqualTo("${assertionResults.size}")

        val key0 = "${getKeyForAssertionResult(mockSuccessfulAssertionResult)}_0"
        val key1 = "${getKeyForAssertionResult(mockSuccessfulAssertionResult)}_1"
        val key2 = "${getKeyForAssertionResult(mockFailedAssertionResult)}_0"
        Truth.assertThat(testData.stringMetrics).containsKey(key0)
        Truth.assertThat(testData.stringMetrics[key0]).isEqualTo("0")
        Truth.assertThat(testData.stringMetrics[key1]).isEqualTo("0")
        Truth.assertThat(testData.stringMetrics[key2]).isEqualTo("1")

        Truth.assertThat(runData.hasMetrics()).isFalse()
    }

    private fun createCollector(
        assertionResults: Collection<AssertionResult> = listOf(mockSuccessfulAssertionResult),
        reportOnlyForPassingTests: Boolean = true,
        collectMetricsPerTest: Boolean = true,
        serviceProcessingError: Boolean = false,
    ): FlickerServiceResultsCollector {
        val mockTraceCollector = Mockito.mock(ITracesCollector::class.java)
        Mockito.`when`(mockTraceCollector.stop())
            .thenReturn(
                ParsedTracesReader(
                    artifact = InMemoryArtifact.EMPTY,
                    wmTrace = MockWindowManagerTraceBuilder().build(),
                    layersTrace = MockLayersTraceBuilder().build(),
                    transitionsTrace = TransitionsTrace(emptyArray()),
                    transactionsTrace = null
                )
            )
        val mockFlickerService = Mockito.mock(IFlickerService::class.java)
        if (serviceProcessingError) {
            Mockito.`when`(
                    mockFlickerService.detectScenarios(KotlinMockito.any(IReader::class.java))
                )
                .thenThrow(RuntimeException("Flicker Service Processing Error"))
        }
        Mockito.`when`(mockFlickerService.executeAssertions(KotlinMockito.argThat { true }))
            .thenReturn(assertionResults)

        return FlickerServiceResultsCollector(
            tracesCollector = mockTraceCollector,
            flickerService = mockFlickerService,
            reportOnlyForPassingTests = reportOnlyForPassingTests,
            collectMetricsPerTest = collectMetricsPerTest,
        )
    }

    companion object {
        val mockSuccessfulAssertionResult =
            AssertionResult(
                object : IFaasAssertion {
                    override val name: String
                        get() = "MockPassedAssertion"
                    override val stabilityGroup: AssertionInvocationGroup
                        get() = AssertionInvocationGroup.BLOCKING
                    override fun evaluate(): IAssertionResult {
                        error("Unimplemented - shouldn't be called")
                    }
                },
                assertionError = null
            )

        val mockFailedAssertionResult =
            AssertionResult(
                object : IFaasAssertion {
                    override val name: String
                        get() = "MockFailedAssertion"
                    override val stabilityGroup: AssertionInvocationGroup
                        get() = AssertionInvocationGroup.BLOCKING
                    override fun evaluate(): IAssertionResult {
                        error("Unimplemented - shouldn't be called")
                    }
                },
                assertionError = Throwable("Assertion failed")
            )

        private class SpyDataRecord : DataRecord() {
            val stringMetrics = mutableMapOf<String, String>()
            override fun addStringMetric(key: String, value: String) {
                super.addStringMetric(key, value)
                stringMetrics[key] = value
            }
        }

        @Rule @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
