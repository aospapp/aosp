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

package android.tools.common.flicker.config.lockscreen

import android.tools.common.flicker.config.AssertionTemplates
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.flicker.config.IScenarioConfig
import android.tools.common.flicker.extractors.TaggedCujTransitionMatcher
import android.tools.common.flicker.extractors.TaggedScenarioExtractor
import android.tools.common.traces.events.CujType

class LockscreenPinDisappear : IScenarioConfig {
    override val enabled = false

    override val type = FaasScenarioType.LOCKSCREEN_PIN_DISAPPEAR

    override val assertionTemplates =
        AssertionTemplates.COMMON_ASSERTIONS // TODO: Add specific assertions

    override val extractor =
        TaggedScenarioExtractor(
            targetTag = CujType.CUJ_LOCKSCREEN_PIN_DISAPPEAR,
            type,
            transitionMatcher = TaggedCujTransitionMatcher(associatedTransitionRequired = false),
        )
}
