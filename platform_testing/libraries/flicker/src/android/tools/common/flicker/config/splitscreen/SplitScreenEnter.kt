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

package android.tools.common.flicker.config.splitscreen

import android.tools.common.flicker.config.AssertionTemplates
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.flicker.config.IScenarioConfig
import android.tools.common.flicker.config.TransitionFilters
import android.tools.common.flicker.extractors.ShellTransitionScenarioExtractor

class SplitScreenEnter : IScenarioConfig {
    override val enabled = true

    override val type = FaasScenarioType.SPLIT_SCREEN_ENTER

    override val assertionTemplates = AssertionTemplates.ENTER_SPLITSCREEN_ASSERTIONS

    override val extractor =
        ShellTransitionScenarioExtractor(
            type,
            transitionMatcher = TransitionFilters.ENTER_SPLIT_SCREEN_MATCHER
        )
}
