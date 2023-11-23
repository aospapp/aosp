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

package android.tools.device.flicker.legacy.runner

import android.app.Instrumentation
import android.platform.test.rule.ArtifactSaver
import android.tools.common.CrossPlatform
import android.tools.common.FLICKER_TAG
import android.tools.common.IScenario
import android.tools.device.traces.io.ResultWriter
import android.tools.device.traces.monitors.ITransitionMonitor
import android.tools.device.traces.parsers.WindowManagerStateHelper
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Test rule to start and stop trace monitors and update [resultWriter]
 *
 * @param traceMonitors to collect device data
 * @param scenario to run the transition
 * @param wmHelper to stabilize the UI before/after transitions
 * @param resultWriter to write
 * @param instrumentation to interact with the device
 */
class TraceMonitorRule(
    private val traceMonitors: List<ITransitionMonitor>,
    private val scenario: IScenario,
    private val wmHelper: WindowManagerStateHelper,
    private val resultWriter: ResultWriter,
    private val instrumentation: Instrumentation
) : TestRule {
    override fun apply(base: Statement?, description: Description?): Statement {
        return object : Statement() {
            override fun evaluate() {
                try {
                    doStartMonitors(description)
                    base?.evaluate()
                } finally {
                    doStopMonitors(description)
                }
            }
        }
    }

    private fun doStartMonitors(description: Description?) {
        CrossPlatform.log.withTracing("doStartMonitors") {
            Utils.notifyRunnerProgress(scenario, "Starting traces for $description")
            traceMonitors.forEach {
                try {
                    it.start()
                } catch (e: Throwable) {
                    ArtifactSaver.onError(Utils.expandDescription(description, "startTrace"), e)
                    throw e
                }
            }
        }
    }

    private fun doStopMonitors(description: Description?) {
        CrossPlatform.log.withTracing("doStopMonitors") {
            Utils.notifyRunnerProgress(scenario, "Stopping traces for $description")
            val errors =
                traceMonitors.map {
                    runCatching {
                        try {
                            it.stop(resultWriter)
                        } catch (e: Throwable) {
                            ArtifactSaver.onError(
                                Utils.expandDescription(description, "stopTrace"),
                                e
                            )
                            CrossPlatform.log.e(FLICKER_TAG, "Unable to stop $it", e)
                            throw e
                        }
                    }
                }
            errors.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
        }
    }
}
