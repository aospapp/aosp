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

package android.tools.common.flicker.assertors.assertions

import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.assertors.ComponentTemplate
import android.tools.common.flicker.subject.wm.WindowManagerTraceSubject
import android.tools.common.traces.component.ComponentNameMatcher

/**
 * Checks that the app layer doesn't exist or is invisible at the start of the transition, but is
 * created and/or becomes visible during the transition.
 */
class AppWindowBecomesVisible(private val component: ComponentTemplate) :
    AssertionTemplateWithComponent(component) {
    /** {@inheritDoc} */
    override fun doEvaluate(
        scenarioInstance: IScenarioInstance,
        wmSubject: WindowManagerTraceSubject
    ) {
        // The app launch transition can finish when the splashscreen or SnapshotStartingWindows
        // are shown before the app window and layers are actually shown. (b/284302118)

        wmSubject
            .isAppWindowInvisible(component.build(scenarioInstance))
            .then()
            .isAppWindowVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
            .then()
            .isAppWindowVisible(ComponentNameMatcher.SPLASH_SCREEN, isOptional = true)
            .then()
            .isAppWindowVisible(component.build(scenarioInstance), isOptional = true)
            .forAllEntries()

        wmSubject
            .last()
            .isAppWindowVisible(
                ComponentNameMatcher.SNAPSHOT.or(ComponentNameMatcher.SPLASH_SCREEN)
                    .or(component.build(scenarioInstance))
            )
    }
}
