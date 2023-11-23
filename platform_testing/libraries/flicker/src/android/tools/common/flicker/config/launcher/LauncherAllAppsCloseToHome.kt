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

package android.tools.common.flicker.config.launcher

import android.tools.common.flicker.config.AssertionTemplates
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.flicker.config.IScenarioConfig
import android.tools.common.flicker.config.TransitionFilters
import android.tools.common.flicker.extractors.TaggedCujTransitionMatcher
import android.tools.common.flicker.extractors.TaggedScenarioExtractor
import android.tools.common.traces.events.CujType

class LauncherAllAppsCloseToHome : IScenarioConfig {
    override val enabled = true

    override val type = FaasScenarioType.LAUNCHER_CLOSE_ALL_APPS_TO_HOME

    override val assertionTemplates = AssertionTemplates.APP_CLOSE_TO_HOME_ASSERTIONS

    override val extractor =
        TaggedScenarioExtractor(
            targetTag = CujType.CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME,
            type,
            transitionMatcher =
                TaggedCujTransitionMatcher(TransitionFilters.CLOSE_APP_TO_LAUNCHER_FILTER)
        )
}
