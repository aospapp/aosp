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

package android.tools.device.flicker

import android.tools.common.CrossPlatform
import android.tools.common.FLICKER_TAG
import android.tools.common.flicker.IFlickerService
import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.assertors.IAssertionResult
import android.tools.common.flicker.assertors.IFaasAssertion
import android.tools.common.flicker.assertors.factories.AssertionFactory
import android.tools.common.flicker.assertors.factories.CombinedAssertionFactory
import android.tools.common.flicker.assertors.factories.GeneratedAssertionsFactory
import android.tools.common.flicker.assertors.factories.IAssertionFactory
import android.tools.common.flicker.assertors.runners.AssertionRunner
import android.tools.common.flicker.assertors.runners.IAssertionRunner
import android.tools.common.flicker.config.FlickerServiceConfig
import android.tools.common.flicker.extractors.CombinedScenarioExtractor
import android.tools.common.flicker.extractors.IScenarioExtractor
import android.tools.common.io.IReader

/** Contains the logic for Flicker as a Service. */
class FlickerService(
    val scenarioExtractor: IScenarioExtractor =
        CombinedScenarioExtractor(FlickerServiceConfig.getExtractors()),
    val assertionFactory: IAssertionFactory =
        CombinedAssertionFactory(listOf(AssertionFactory(), GeneratedAssertionsFactory())),
    val assertionRunner: IAssertionRunner = AssertionRunner(),
) : IFlickerService {

    /**
     * The entry point for WM Flicker Service.
     *
     * @param reader A flicker trace reader
     * @return A list of assertion results
     */
    override fun process(reader: IReader): Collection<IAssertionResult> {
        return CrossPlatform.log.withTracing("FlickerService#process") {
            try {
                val scenarioInstances = detectScenarios(reader)
                val assertions = generateAssertions(scenarioInstances)
                executeAssertions(assertions)
            } catch (exception: Throwable) {
                CrossPlatform.log.e("$FLICKER_TAG-ASSERT", "FAILED PROCESSING", exception)
                throw exception
            }
        }
    }

    override fun detectScenarios(reader: IReader): Collection<IScenarioInstance> {
        return CrossPlatform.log.withTracing("FlickerService#detectScenarios") {
            scenarioExtractor.extract(reader)
        }
    }

    override fun generateAssertions(
        scenarioInstances: Collection<IScenarioInstance>
    ): Collection<IFaasAssertion> {
        return CrossPlatform.log.withTracing("FlickerService#generateAssertions") {
            scenarioInstances.flatMap { assertionFactory.generateAssertionsFor(it) }
        }
    }

    override fun executeAssertions(
        assertions: Collection<IFaasAssertion>
    ): Collection<IAssertionResult> {
        return CrossPlatform.log.withTracing("FlickerService#executeAssertions") {
            val results = assertionRunner.execute(assertions)
            logResults(results)
            results
        }
    }

    private fun logResults(results: Collection<IAssertionResult>) {
        results.forEach {
            if (it.failed) {
                CrossPlatform.log.w(
                    "$FLICKER_TAG-SERVICE",
                    "${it.assertion} FAILED :: " +
                        (it.assertionError?.message ?: "<NO ERROR MESSAGE>")
                )
            } else {
                CrossPlatform.log.w("$FLICKER_TAG-SERVICE", "${it.assertion} PASSED")
            }
        }
    }
}
