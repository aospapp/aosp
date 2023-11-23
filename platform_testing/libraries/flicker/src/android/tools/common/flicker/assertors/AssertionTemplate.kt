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

package android.tools.common.flicker.assertors

import android.tools.common.flicker.AssertionInvocationGroup
import android.tools.common.flicker.AssertionInvocationGroup.NON_BLOCKING
import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.subject.events.EventLogSubject
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.flicker.subject.wm.WindowManagerTraceSubject

/** Base class for a FaaS assertion */
abstract class AssertionTemplate : IAssertionTemplate {
    override val assertionName = "${this@AssertionTemplate::class.simpleName}"
    private var stabilityGroup: AssertionInvocationGroup = NON_BLOCKING

    override fun createAssertion(scenarioInstance: IScenarioInstance): IFaasAssertion {
        return object : IFaasAssertion {
            override val name = "${scenarioInstance.type}::${this@AssertionTemplate.assertionName}"

            override val stabilityGroup
                get() = this@AssertionTemplate.stabilityGroup

            override fun evaluate(): AssertionResult {
                val wmTraceSubject =
                    scenarioInstance.reader.readWmTrace()?.let {
                        WindowManagerTraceSubject(it, scenarioInstance.reader)
                    }
                val layersTraceSubject =
                    scenarioInstance.reader.readLayersTrace()?.let {
                        LayersTraceSubject(it, scenarioInstance.reader)
                    }
                val eventLogSubject =
                    scenarioInstance.reader.readEventLogTrace()?.let {
                        EventLogSubject(it, scenarioInstance.reader)
                    }

                var assertionError: Throwable? = null
                try {
                    if (wmTraceSubject !== null) {
                        doEvaluate(scenarioInstance, wmTraceSubject)
                    }
                    if (layersTraceSubject !== null) {
                        doEvaluate(scenarioInstance, layersTraceSubject)
                    }
                    if (wmTraceSubject !== null && layersTraceSubject !== null) {
                        doEvaluate(scenarioInstance, wmTraceSubject, layersTraceSubject)
                    }
                    if (eventLogSubject != null) {
                        doEvaluate(scenarioInstance, eventLogSubject)
                    }
                } catch (e: Throwable) {
                    assertionError = e
                }

                return AssertionResult(this, assertionError)
            }
        }
    }

    /**
     * Evaluates assertions that require only WM traces. NOTE: Will not run if WM trace is not
     * available.
     */
    protected open fun doEvaluate(
        scenarioInstance: IScenarioInstance,
        wmSubject: WindowManagerTraceSubject
    ) {
        // Does nothing, unless overridden
    }

    /**
     * Evaluates assertions that require only SF traces. NOTE: Will not run if layers trace is not
     * available.
     */
    protected open fun doEvaluate(
        scenarioInstance: IScenarioInstance,
        layerSubject: LayersTraceSubject
    ) {
        // Does nothing, unless overridden
    }

    /**
     * Evaluates assertions that require both SF and WM traces. NOTE: Will not run if any of the
     * traces are not available.
     */
    protected open fun doEvaluate(
        scenarioInstance: IScenarioInstance,
        wmSubject: WindowManagerTraceSubject,
        layerSubject: LayersTraceSubject
    ) {
        // Does nothing, unless overridden
    }

    /**
     * Evaluates assertions that require the vent log. NOTE: Will not run if the event log traces is
     * not available.
     */
    protected open fun doEvaluate(scenarioInstance: IScenarioInstance, eventLog: EventLogSubject) {
        // Does nothing, unless overridden
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) {
            return false
        }
        // Ensure both assertions are instances of the same class.
        return this::class == other::class
    }

    override fun hashCode(): Int {
        var result = stabilityGroup.hashCode()
        result = 31 * result + assertionName.hashCode()
        return result
    }
}
