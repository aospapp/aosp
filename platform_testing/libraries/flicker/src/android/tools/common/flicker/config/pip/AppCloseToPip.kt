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

package android.tools.common.flicker.config.pip

import android.tools.common.flicker.config.AssertionTemplates
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.flicker.config.IScenarioConfig
import android.tools.common.flicker.config.TransitionFilters
import android.tools.common.flicker.extractors.TaggedCujTransitionMatcher
import android.tools.common.flicker.extractors.TaggedScenarioExtractor
import android.tools.common.traces.events.Cuj
import android.tools.common.traces.events.CujType

class AppCloseToPip : IScenarioConfig {
    override val enabled = true

    override val type = FaasScenarioType.LAUNCHER_APP_CLOSE_TO_PIP

    override val assertionTemplates = AssertionTemplates.APP_CLOSE_TO_PIP_ASSERTIONS

    override val extractor =
        TaggedScenarioExtractor(
            targetTag = CujType.CUJ_LAUNCHER_APP_CLOSE_TO_PIP,
            type,
            transitionMatcher =
                TaggedCujTransitionMatcher(TransitionFilters.APP_CLOSE_TO_PIP_TRANSITION_FILTER),
            adjustCuj = { cuj, reader ->
                val cujs = reader.readCujTrace() ?: error("Missing CUJ trace")
                val closeToHomeCuj =
                    cujs.entries.firstOrNull {
                        it.cuj == CujType.CUJ_LAUNCHER_APP_CLOSE_TO_HOME &&
                            it.startTimestamp <= cuj.startTimestamp &&
                            cuj.startTimestamp <= it.endTimestamp
                    }

                if (closeToHomeCuj == null) {
                    cuj
                } else {
                    Cuj(cuj.cuj, closeToHomeCuj.startTimestamp, cuj.endTimestamp, cuj.canceled)
                }
            }
        )
}
