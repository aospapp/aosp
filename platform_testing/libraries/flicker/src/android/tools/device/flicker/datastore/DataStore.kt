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

package android.tools.device.flicker.datastore

import android.tools.common.IScenario
import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.assertors.IFaasAssertion
import android.tools.device.traces.io.IResultData
import androidx.annotation.VisibleForTesting

/** In memory data store for flicker transitions, assertions and results */
object DataStore {
    private var cachedResults = mutableMapOf<IScenario, IResultData>()
    private var cachedFlickerServiceAssertions =
        mutableMapOf<IScenario, Map<IScenarioInstance, Collection<IFaasAssertion>>>()

    data class Backup(
        val cachedResults: MutableMap<IScenario, IResultData>,
        val cachedFlickerServiceAssertions:
            MutableMap<IScenario, Map<IScenarioInstance, Collection<IFaasAssertion>>>
    )

    @VisibleForTesting
    fun clear() {
        cachedResults = mutableMapOf()
        cachedFlickerServiceAssertions = mutableMapOf()
    }

    fun backup(): Backup {
        return Backup(cachedResults.toMutableMap(), cachedFlickerServiceAssertions.toMutableMap())
    }

    fun restore(backup: Backup) {
        cachedResults = backup.cachedResults
        cachedFlickerServiceAssertions = backup.cachedFlickerServiceAssertions
    }

    /** @return if the store has results for [scenario] */
    fun containsResult(scenario: IScenario): Boolean = cachedResults.containsKey(scenario)

    /**
     * Adds [result] to the store with [scenario] as id
     *
     * @throws IllegalStateException is [scenario] already exists in the data store
     */
    fun addResult(scenario: IScenario, result: IResultData) {
        require(!containsResult(scenario)) { "Result for $scenario already in data store" }
        cachedResults[scenario] = result
    }

    /**
     * Replaces the old value [scenario] result in the store by [newResult]
     *
     * @throws IllegalStateException is [scenario] doesn't exist in the data store
     */
    fun replaceResult(scenario: IScenario, newResult: IResultData) {
        if (!containsResult(scenario)) {
            error("Result for $scenario not in data store")
        }
        cachedResults[scenario] = newResult
    }

    /**
     * @return the result for [scenario]
     * @throws IllegalStateException is [scenario] doesn't exist in the data store
     */
    fun getResult(scenario: IScenario): IResultData =
        cachedResults[scenario] ?: error("No value for $scenario")

    /** @return if the store has results for [scenario] */
    fun containsFlickerServiceResult(scenario: IScenario): Boolean =
        cachedFlickerServiceAssertions.containsKey(scenario)

    fun addFlickerServiceAssertions(
        scenario: IScenario,
        groupedAssertions: Map<IScenarioInstance, Collection<IFaasAssertion>>
    ) {
        if (containsFlickerServiceResult(scenario)) {
            error("Result for $scenario already in data store")
        }
        cachedFlickerServiceAssertions[scenario] = groupedAssertions
    }

    fun getFlickerServiceAssertions(
        scenario: IScenario
    ): Map<IScenarioInstance, Collection<IFaasAssertion>> {
        return cachedFlickerServiceAssertions[scenario]
            ?: error("No flicker service results for $scenario")
    }
}
