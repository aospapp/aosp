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

package android.tools.common.flicker.extractor

import android.tools.common.CrossPlatform
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.flicker.extractors.ITransitionMatcher
import android.tools.common.flicker.extractors.ShellTransitionScenarioExtractor
import android.tools.common.traces.wm.Transition
import android.tools.common.traces.wm.TransitionType
import android.tools.getTraceReaderFromScenario
import com.google.common.truth.Truth
import org.junit.Test

class ShellTransitionScenarioExtractorTest {
    @Test
    fun canExtractWithNoFilter() {
        val reader = getTraceReaderFromScenario("AppLaunch")
        val transitions =
            (reader.readTransitionsTrace() ?: error("Missing transitions trace")).entries.filter {
                it.played
            }

        val noFilterExtractor =
            ShellTransitionScenarioExtractor(
                FaasScenarioType.LAUNCHER_APP_LAUNCH_FROM_ICON,
                object : ITransitionMatcher {
                    override fun findAll(
                        transitions: Collection<Transition>
                    ): Collection<Transition> {
                        // No Filter
                        return transitions
                    }
                }
            )
        val scenarios = noFilterExtractor.extract(reader)
        Truth.assertThat(scenarios).hasSize(2)
        Truth.assertThat(
                scenarios.all { it.type == FaasScenarioType.LAUNCHER_APP_LAUNCH_FROM_ICON }
            )
            .isTrue()
        Truth.assertThat(scenarios.all { it.associatedTransition != null }).isTrue()
        transitions.forEachIndexed { index, transition ->
            Truth.assertThat(scenarios[index].associatedTransition).isEqualTo(transition)
        }
    }

    @Test
    fun canExtractWithSimpleFilter() {
        val reader = getTraceReaderFromScenario("AppLaunch")
        val transitions =
            (reader.readTransitionsTrace() ?: error("Missing transitions trace")).entries

        val filterToFrontExtractor =
            ShellTransitionScenarioExtractor(
                FaasScenarioType.LAUNCHER_APP_LAUNCH_FROM_ICON,
                object : ITransitionMatcher {
                    override fun findAll(
                        transitions: Collection<Transition>
                    ): Collection<Transition> {
                        return transitions.filter { it.type == TransitionType.OPEN }
                    }
                }
            )
        val scenarios = filterToFrontExtractor.extract(reader)
        Truth.assertThat(scenarios).hasSize(1)
        Truth.assertThat(scenarios.first().type)
            .isEqualTo(FaasScenarioType.LAUNCHER_APP_LAUNCH_FROM_ICON)
        val layersTrace = scenarios.first().reader.readLayersTrace() ?: error("Missing layer trace")
        Truth.assertThat(layersTrace.entries.first().timestamp)
            .isEqualTo(
                CrossPlatform.timestamp.from(
                    unixNanos = 1682433275759078118,
                    systemUptimeNanos = 2766599071189,
                    elapsedNanos = 0
                )
            )
        Truth.assertThat(layersTrace.entries.first().timestamp)
            .isEqualTo(
                CrossPlatform.timestamp.from(
                    unixNanos = 1682433275759078118,
                    systemUptimeNanos = 2766599071189,
                    elapsedNanos = 0
                )
            )
        Truth.assertThat(scenarios.first().associatedTransition)
            .isEqualTo(transitions.first { it.type == TransitionType.OPEN })
    }

    @Test
    fun canExtractWithNoMatches() {
        val reader = getTraceReaderFromScenario("AppLaunch")

        val filterToBackExtractor =
            ShellTransitionScenarioExtractor(
                FaasScenarioType.LAUNCHER_APP_LAUNCH_FROM_ICON,
                object : ITransitionMatcher {
                    override fun findAll(
                        transitions: Collection<Transition>
                    ): Collection<Transition> {
                        return transitions.filter { it.type == TransitionType.TO_BACK }
                    }
                }
            )
        Truth.assertThat(filterToBackExtractor.extract(reader)).hasSize(0)
    }
}
