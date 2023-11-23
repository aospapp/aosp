/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker.rules

import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.AssertionTag
import com.android.server.wm.flicker.service.FlickerService
import com.android.server.wm.flicker.service.assertors.AssertionConfigParser
import com.android.server.wm.flicker.service.assertors.AssertionData
import com.android.server.wm.flicker.traces.layers.LayersTraceSubject
import com.android.server.wm.flicker.traces.windowmanager.WindowManagerTraceSubject
import com.android.server.wm.traces.common.errors.ErrorTrace
import org.junit.rules.TestWatcher

/**
 * A test rule reusing flicker data from [FlickerTestParameter], and fetching the traces
 * to call the WM Flicker Service after the test
 */
@Deprecated("This test rule should be only used with legacy flicker tests. " +
    "For new tests use WMFlickerServiceRule instead")
class WMFlickerServiceRuleForTestSpec(
    private val testSpec: FlickerTestParameter
) : TestWatcher() {
    private fun checkFlicker(category: String): List<ErrorTrace> {
        // run flicker if it was not executed before
        testSpec.flicker.result ?: testSpec.assertWm { isNotEmpty() }

        val errors = mutableListOf<ErrorTrace>()
        val result = testSpec.flicker.result ?: error("No flicker results for ${testSpec.flicker}")
        val assertions = AssertionData.readConfiguration().filter { it.category == category }
        val flickerService = FlickerService(assertions)

        result.runs
            .filter { it.assertionTag == AssertionTag.ALL }
            .filter {
                val hasWmTrace = it.wmSubject?.let { true } ?: false
                val hasLayersTrace = it.layersSubject?.let { true } ?: false
                hasWmTrace || hasLayersTrace
            }
            .forEach { run ->
                val wmSubject = run.wmSubject as WindowManagerTraceSubject
                val layersSubject = run.layersSubject as LayersTraceSubject

                val outputDir = run.traceFiles
                    .firstOrNull()
                    ?.parent
                    ?: error("Output dir not detected")

                val wmTrace = wmSubject.trace
                val layersTrace = layersSubject.trace
                errors.add(flickerService.process(wmTrace, layersTrace, outputDir, category))
            }

        return errors
    }

    fun checkPresubmitAssertions() {
        val errors = checkFlicker(AssertionConfigParser.PRESUBMIT_KEY)
        failIfAnyError(errors)
    }

    fun checkPostsubmitAssertions() {
        val errors = checkFlicker(AssertionConfigParser.POSTSUBMIT_KEY)
        failIfAnyError(errors)
    }

    fun checkFlakyAssertions() {
        val errors = checkFlicker(AssertionConfigParser.FLAKY_KEY)
        failIfAnyError(errors)
    }

    private fun failIfAnyError(errors: List<ErrorTrace>) {
        val errorMsg = errors.joinToString("\n") { runs ->
            runs.entries.joinToString { state ->
                state.errors.joinToString { "${it.assertionName}\n${it.message}" }
            }
        }
        if (errorMsg.isNotEmpty()) {
            error(errorMsg)
        }
    }
}