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

import android.tools.common.datatypes.Region
import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.assertors.ComponentTemplate
import android.tools.common.flicker.assertors.Components
import android.tools.common.flicker.subject.layers.LayerTraceEntrySubject
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.flicker.subject.wm.WindowManagerTraceSubject
import android.tools.common.traces.component.IComponentMatcher

class SplitAppLayerBoundsBecomesVisible(
    private val component: ComponentTemplate,
    val isPrimaryApp: Boolean
) : AssertionTemplateWithComponent(component) {
    override fun doEvaluate(
        scenarioInstance: IScenarioInstance,
        wmSubject: WindowManagerTraceSubject,
        layerSubject: LayersTraceSubject
    ) {
        val splitscreenDivider = Components.SPLIT_SCREEN_DIVIDER.build(scenarioInstance)
        val app = component.build(scenarioInstance)

        val landscapePosLeft: Boolean
        val portraitPosTop: Boolean
        if (isPrimaryApp) {
            landscapePosLeft = false
            portraitPosTop = false
        } else {
            // secondaryApp
            landscapePosLeft = true
            portraitPosTop = true
        }

        layerSubject
            .notContains(splitscreenDivider.or(app), isOptional = true)
            .then()
            .isInvisible(splitscreenDivider.or(app))
            .then()
            .splitAppLayerBoundsSnapToDivider(
                app,
                splitscreenDivider,
                landscapePosLeft,
                portraitPosTop
            )
            .forAllEntries()
    }
}

fun LayersTraceSubject.splitAppLayerBoundsSnapToDivider(
    component: IComponentMatcher,
    splitscreenDivider: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean
): LayersTraceSubject {
    return invoke("splitAppLayerBoundsSnapToDivider") {
        it.splitAppLayerBoundsSnapToDivider(
            component,
            splitscreenDivider,
            landscapePosLeft,
            portraitPosTop
        )
    }
}

fun LayerTraceEntrySubject.splitAppLayerBoundsSnapToDivider(
    component: IComponentMatcher,
    splitscreenDivider: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean
): LayerTraceEntrySubject {
    val activeDisplay =
        this.entry.displays.firstOrNull { it.isOn && !it.isVirtual }
            ?: error("No non-virtual and on display found")

    return invoke {
        val dividerRegion =
            layer(splitscreenDivider)?.visibleRegion?.region
                ?: error("$splitscreenDivider component not found")
        visibleRegion(component)
            .coversAtMost(
                if (activeDisplay.bounds.width > activeDisplay.bounds.height) {
                    if (landscapePosLeft) {
                        Region.from(
                            0,
                            0,
                            (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
                            activeDisplay.bounds.bottom
                        )
                    } else {
                        Region.from(
                            (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
                            0,
                            activeDisplay.bounds.right,
                            activeDisplay.bounds.bottom
                        )
                    }
                } else {
                    if (portraitPosTop) {
                        Region.from(
                            0,
                            0,
                            activeDisplay.bounds.right,
                            (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2
                        )
                    } else {
                        Region.from(
                            0,
                            (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2,
                            activeDisplay.bounds.right,
                            activeDisplay.bounds.bottom
                        )
                    }
                }
            )
    }
}
