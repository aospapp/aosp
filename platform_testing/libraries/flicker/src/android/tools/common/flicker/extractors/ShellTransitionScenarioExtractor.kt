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

package android.tools.common.flicker.extractors

import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.ScenarioInstance
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.io.IReader

class ShellTransitionScenarioExtractor(
    val type: FaasScenarioType,
    val transitionMatcher: ITransitionMatcher,
) : IScenarioExtractor {
    override fun extract(reader: IReader): List<IScenarioInstance> {
        val layersTrace = reader.readLayersTrace() ?: error("Missing layers trace")

        val transitionsTrace = reader.readTransitionsTrace() ?: error("Missing transitions trace")
        val completeTransitions = transitionsTrace.entries.filter { !it.isIncomplete }

        val transitions = transitionMatcher.findAll(completeTransitions)

        return transitions.map {
            val startTimestamp = Utils.interpolateStartTimestampFromTransition(it, reader)
            val endTimestamp = Utils.interpolateFinishTimestampFromTransition(it, reader)

            val displayAtStart =
                Utils.getOnDisplayFor(layersTrace.getFirstEntryWithOnDisplayAfter(startTimestamp))
            val displayAtEnd =
                Utils.getOnDisplayFor(layersTrace.getLastEntryWithOnDisplayBefore(endTimestamp))

            ScenarioInstance(
                type,
                startRotation = displayAtStart.transform.getRotation(),
                endRotation = displayAtEnd.transform.getRotation(),
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                associatedCuj = null,
                associatedTransition = it,
                reader = reader.slice(startTimestamp, endTimestamp)
            )
        }
    }
}
