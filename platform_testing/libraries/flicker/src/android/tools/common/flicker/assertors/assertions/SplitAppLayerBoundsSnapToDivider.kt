/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.tools.common.flicker.assertors.Components.SPLIT_SCREEN_DIVIDER
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.flicker.subject.wm.WindowManagerTraceSubject

class SplitAppLayerBoundsSnapToDivider(private val component: ComponentTemplate) :
    AssertionTemplateWithComponent(component) {
    /** {@inheritDoc} */
    override fun doEvaluate(
        scenarioInstance: IScenarioInstance,
        wmSubject: WindowManagerTraceSubject,
        layerSubject: LayersTraceSubject
    ) {
        // TODO: Replace with always on tracing available data
        val landscapePosLeft = !wmSubject.trace.isTablet
        val portraitPosTop = true // TODO: Figure out how to know if we are top or bottom app

        val splitscreenDivider = SPLIT_SCREEN_DIVIDER.build(scenarioInstance)

        layerSubject
            .invoke("splitAppLayerBoundsSnapToDivider") {
                val displaySize =
                    it.entry.displays
                        .first()
                        .size // TODO: Better way of getting correct display instead of just first
                val dividerRegion =
                    it.layer(splitscreenDivider)?.visibleRegion?.region
                        ?: error("Missing splitscreen divider")

                require(dividerRegion.isNotEmpty) {
                    "Splitscreen divider region should not be empty"
                }

                it.visibleRegion(component.build(scenarioInstance))
                    .coversAtMost(
                        if (displaySize.width > displaySize.height) {
                            if (landscapePosLeft) {
                                Region.from(
                                    0,
                                    0,
                                    (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
                                    displaySize.height
                                )
                            } else {
                                Region.from(
                                    (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
                                    0,
                                    displaySize.width,
                                    displaySize.height
                                )
                            }
                        } else {
                            if (portraitPosTop) {
                                Region.from(
                                    0,
                                    0,
                                    displaySize.width,
                                    (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2
                                )
                            } else {
                                Region.from(
                                    0,
                                    (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2,
                                    displaySize.width,
                                    displaySize.height
                                )
                            }
                        }
                    )
            }
            .forAllEntries()
    }
}
