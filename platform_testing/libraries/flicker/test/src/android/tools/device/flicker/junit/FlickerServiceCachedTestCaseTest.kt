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

package android.tools.device.flicker.junit

import android.app.Instrumentation
import android.device.collectors.util.SendToInstrumentation
import android.tools.assertThrows
import android.tools.common.flicker.AssertionInvocationGroup
import android.tools.common.flicker.IFlickerService
import android.tools.common.flicker.assertors.IAssertionResult
import android.tools.common.flicker.assertors.IFaasAssertion
import android.tools.device.flicker.FlickerServiceResultsCollector
import android.tools.utils.KotlinMockito
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.Description
import org.mockito.Mockito

class FlickerServiceCachedTestCaseTest {
    @Test
    fun reportsPassingResultMetric() {
        val mockAssertion = Mockito.mock(IFaasAssertion::class.java)
        val mockFlickerService = Mockito.mock(IFlickerService::class.java)
        val decorator = Mockito.mock(IFlickerJUnitDecorator::class.java)
        val mockInstrumentation = Mockito.mock(Instrumentation::class.java)

        val test =
            FlickerServiceCachedTestCase(
                assertion = mockAssertion,
                flickerService = mockFlickerService,
                method = InjectedTestCase::class.java.getMethod("execute", Description::class.java),
                onlyBlocking = false,
                isLast = false,
                injectedBy = decorator,
                instrumentation = mockInstrumentation,
                paramString = "",
            )

        val assertionResult =
            object : IAssertionResult {
                override val assertion =
                    object : IFaasAssertion {
                        override val name = "PassingFaasAssertion"
                        override val stabilityGroup = AssertionInvocationGroup.BLOCKING
                        override fun evaluate(): IAssertionResult {
                            TODO("Not implemented")
                        }
                    }
                override val passed = true
                override val assertionError = null
            }
        Mockito.`when`(
                mockFlickerService.executeAssertion(KotlinMockito.any(IFaasAssertion::class.java))
            )
            .thenReturn(assertionResult)

        val mockDescription = Mockito.mock(Description::class.java)
        test.execute(mockDescription)

        Mockito.verify(mockInstrumentation)
            .sendStatus(
                Mockito.eq(SendToInstrumentation.INST_STATUS_IN_PROGRESS),
                KotlinMockito.argThat {
                    this.getString(
                        "${FlickerServiceResultsCollector.FAAS_METRICS_PREFIX}::" +
                            "${assertionResult.assertion.name}"
                    ) == "0"
                }
            )
    }

    @Test
    fun reportsFailingResultMetric() {
        val mockAssertion = Mockito.mock(IFaasAssertion::class.java)
        val mockFlickerService = Mockito.mock(IFlickerService::class.java)
        val decorator = Mockito.mock(IFlickerJUnitDecorator::class.java)
        val mockInstrumentation = Mockito.mock(Instrumentation::class.java)

        val test =
            FlickerServiceCachedTestCase(
                assertion = mockAssertion,
                flickerService = mockFlickerService,
                method = InjectedTestCase::class.java.getMethod("execute", Description::class.java),
                onlyBlocking = false,
                isLast = false,
                injectedBy = decorator,
                instrumentation = mockInstrumentation,
                paramString = "",
            )

        val assertionResult =
            object : IAssertionResult {
                override val assertion =
                    object : IFaasAssertion {
                        override val name = "PassingFaasAssertion"
                        override val stabilityGroup = AssertionInvocationGroup.BLOCKING
                        override fun evaluate(): IAssertionResult {
                            TODO("Not implemented")
                        }
                    }
                override val passed = false
                override val assertionError = Throwable("Some assertion")
            }
        Mockito.`when`(
                mockFlickerService.executeAssertion(KotlinMockito.any(IFaasAssertion::class.java))
            )
            .thenReturn(assertionResult)

        val mockDescription = Mockito.mock(Description::class.java)

        val failure = assertThrows<Throwable> { test.execute(mockDescription) }
        Truth.assertThat(failure).hasMessageThat().isEqualTo("Some assertion")

        Mockito.verify(mockInstrumentation)
            .sendStatus(
                Mockito.eq(SendToInstrumentation.INST_STATUS_IN_PROGRESS),
                KotlinMockito.argThat {
                    this.getString(
                        "${FlickerServiceResultsCollector.FAAS_METRICS_PREFIX}::" +
                            "${assertionResult.assertion.name}"
                    ) == "1"
                }
            )
    }
}
