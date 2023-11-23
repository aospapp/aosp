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

import android.app.Instrumentation
import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.flicker.assertors.IFaasAssertion
import android.tools.common.flicker.assertors.factories.IAssertionFactory
import android.tools.common.flicker.assertors.runners.IAssertionRunner
import android.tools.common.flicker.extractors.IScenarioExtractor
import android.tools.common.io.IReader
import android.tools.device.flicker.FlickerService
import android.tools.device.traces.parsers.WindowManagerStateHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/** Contains [FlickerService] tests. To run this test: `atest FlickerLibTest:FlickerServiceTest` */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FlickerServiceTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val wmHelper = WindowManagerStateHelper(instrumentation, clearCacheAfterParsing = false)

    @Test
    fun generatesAssertionsFromExtractedScenarios() {
        val mockReader = Mockito.mock(IReader::class.java)
        val mockScenarioExtractor = Mockito.mock(IScenarioExtractor::class.java)
        val mockAssertionFactory = Mockito.mock(IAssertionFactory::class.java)
        val mockAssertionRunner = Mockito.mock(IAssertionRunner::class.java)

        val scenarioInstance = Mockito.mock(IScenarioInstance::class.java)
        val assertions = listOf(Mockito.mock(IFaasAssertion::class.java))

        Mockito.`when`(mockScenarioExtractor.extract(mockReader))
            .thenReturn(listOf(scenarioInstance))
        Mockito.`when`(mockAssertionFactory.generateAssertionsFor(scenarioInstance))
            .thenReturn(assertions)

        val service =
            FlickerService(
                scenarioExtractor = mockScenarioExtractor,
                assertionFactory = mockAssertionFactory,
                assertionRunner = mockAssertionRunner
            )
        service.process(mockReader)

        Mockito.verify(mockScenarioExtractor).extract(mockReader)
        Mockito.verify(mockAssertionFactory).generateAssertionsFor(scenarioInstance)
    }

    @Test
    fun executesAssertionsReturnedByAssertionFactories() {
        val mockReader = Mockito.mock(IReader::class.java)
        val mockScenarioExtractor = Mockito.mock(IScenarioExtractor::class.java)
        val mockAssertionFactory = Mockito.mock(IAssertionFactory::class.java)
        val mockAssertionRunner = Mockito.mock(IAssertionRunner::class.java)

        val scenarioInstance = Mockito.mock(IScenarioInstance::class.java)
        val assertions = listOf(Mockito.mock(IFaasAssertion::class.java))

        Mockito.`when`(mockScenarioExtractor.extract(mockReader))
            .thenReturn(listOf(scenarioInstance))
        Mockito.`when`(mockAssertionFactory.generateAssertionsFor(scenarioInstance))
            .thenReturn(assertions)

        val service =
            FlickerService(
                scenarioExtractor = mockScenarioExtractor,
                assertionFactory = mockAssertionFactory,
                assertionRunner = mockAssertionRunner
            )
        service.process(mockReader)

        Mockito.verify(mockScenarioExtractor).extract(mockReader)
        Mockito.verify(mockAssertionRunner).execute(assertions)
    }

    fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()

    inline fun <reified T : Any> argumentCaptor() = ArgumentCaptor.forClass(T::class.java)

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
