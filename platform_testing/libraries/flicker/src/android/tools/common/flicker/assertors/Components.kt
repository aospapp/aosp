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

package android.tools.common.flicker.assertors

import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.FullComponentIdMatcher
import android.tools.common.traces.component.IComponentMatcher
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.wm.Transition
import android.tools.common.traces.wm.TransitionType

object Components {
    /** Common */
    val NAV_BAR = ComponentTemplate("Navbar") { ComponentNameMatcher.NAV_BAR }
    val STATUS_BAR = ComponentTemplate("StatusBar") { ComponentNameMatcher.STATUS_BAR }
    val LAUNCHER = ComponentTemplate("Launcher") { ComponentNameMatcher.LAUNCHER }

    /** App launch */
    val OPENING_APP =
        ComponentTemplate("OPENING_APP") { scenarioInstance: IScenarioInstance ->
            openingAppFrom(
                scenarioInstance.associatedTransition ?: error("Missing associated transition")
            )
        }

    /** App close */
    val CLOSING_APP =
        ComponentTemplate("CLOSING_APP") { scenarioInstance: IScenarioInstance ->
            closingAppFrom(
                scenarioInstance.associatedTransition ?: error("Missing associated transition")
            )
        }

    /** PIP */
    val PIP_DISMISS_OVERLAY =
        ComponentTemplate("PipDismissOverlay") { ComponentNameMatcher("", "pip-dismiss-overlay") }
    val PIP_CONTENT_OVERLAY =
        ComponentTemplate("PipContentOverlay") { ComponentNameMatcher.PIP_CONTENT_OVERLAY }
    val PIP_APP =
        ComponentTemplate("PIP") { scenarioInstance: IScenarioInstance ->
            if (scenarioInstance.type == FaasScenarioType.LAUNCHER_APP_CLOSE_TO_PIP) {
                val associatedTransition =
                    scenarioInstance.associatedTransition ?: error("Missing associated transition")
                val change =
                    associatedTransition.changes.firstOrNull {
                        it.transitMode == TransitionType.TO_BACK
                    }
                        ?: error("Missing to back change")
                FullComponentIdMatcher(change.windowId, change.layerId)
            } else {
                error("Unhandled case - can't get PiP app for this case")
            }
        }

    /** Splitscreen */
    val SPLIT_SCREEN_DIVIDER =
        ComponentTemplate("SplitScreenDivider") {
            ComponentNameMatcher("", "StageCoordinatorSplitDivider#")
        }
    val SPLIT_SCREEN_PRIMARY_APP =
        ComponentTemplate("SPLIT_SCREEN_PRIMARY_APP") { scenarioInstance: IScenarioInstance ->
            val associatedTransition =
                scenarioInstance.associatedTransition
                    ?: error(
                        "Can only extract SPLIT_SCREEN_PRIMARY_APP from scenario with transition"
                    )
            val layersTrace =
                scenarioInstance.reader.readLayersTrace() ?: error("Missing layers trace")

            when (scenarioInstance.type) {
                FaasScenarioType.SPLIT_SCREEN_ENTER -> {
                    getSplitscreenOpeningComponentMatchers(associatedTransition, layersTrace)[0]
                }
                FaasScenarioType.SPLIT_SCREEN_EXIT -> {
                    TODO(
                        "Not implemented :: ${scenarioInstance.type} :: " +
                            "${scenarioInstance.associatedTransition}"
                    )
                }
                FaasScenarioType.SPLIT_SCREEN_RESIZE -> {
                    val change = associatedTransition.changes.first()
                    FullComponentIdMatcher(change.windowId, change.layerId)
                }
                else -> error("Unsupported transition type")
            }
        }
    val SPLIT_SCREEN_SECONDARY_APP =
        ComponentTemplate("SPLIT_SCREEN_SECONDARY_APP") { scenarioInstance: IScenarioInstance ->
            val associatedTransition =
                scenarioInstance.associatedTransition
                    ?: error(
                        "Can only extract SPLIT_SCREEN_SECONDARY_APP from scenario with transition"
                    )
            val layersTrace =
                scenarioInstance.reader.readLayersTrace() ?: error("Missing layers trace")

            when (scenarioInstance.type) {
                FaasScenarioType.SPLIT_SCREEN_ENTER -> {
                    getSplitscreenOpeningComponentMatchers(associatedTransition, layersTrace)[1]
                }
                FaasScenarioType.SPLIT_SCREEN_EXIT -> {
                    TODO(
                        "Not implemented :: ${scenarioInstance.type} :: " +
                            "${scenarioInstance.associatedTransition}"
                    )
                }
                FaasScenarioType.SPLIT_SCREEN_RESIZE -> {
                    val change = associatedTransition.changes.last()
                    FullComponentIdMatcher(change.windowId, change.layerId)
                }
                else -> error("Unsupported transition type")
            }
        }

    val EMPTY = ComponentTemplate("") { ComponentNameMatcher("", "") }

    private fun getSplitscreenOpeningComponentMatchers(
        associatedTransition: Transition,
        layersTrace: LayersTrace
    ): List<IComponentMatcher> {
        // Task (part of changes)
        // - Task (part of changes)
        //   - SplitDecorManager
        //   - Task for app (part of changes)
        // - Task (part of changes)
        //   - SplitDecorManager
        //   - Task for app (part of changes)
        // - SplitWindowManager
        //   - StageCoordinatorSplitDividerLeash#1378

        val layerIds = associatedTransition.changes.map { it.layerId }
        val layers =
            associatedTransition.changes.map { change ->
                layersTrace.entries.last().flattenedLayers.first { it.id == change.layerId }
            }

        val appIds = layers.filter { layerIds.contains(it.parent?.parent?.id) }.map { it.id }

        val componentMatchers =
            associatedTransition.changes
                .filter { appIds.contains(it.layerId) }
                .map { FullComponentIdMatcher(it.windowId, it.layerId) }

        require(componentMatchers.size == 2) {
            "Expected to get 2 splitscreen apps but got ${componentMatchers.size}"
        }

        return componentMatchers
    }

    private fun openingAppFrom(transition: Transition): IComponentMatcher {
        val targetChanges =
            transition.changes.filter {
                it.transitMode == TransitionType.OPEN || it.transitMode == TransitionType.TO_FRONT
            }

        val openingLayerIds = targetChanges.map { it.layerId }
        require(openingLayerIds.size == 1) {
            "Expected 1 opening layer but got ${openingLayerIds.size}"
        }

        val openingWindowIds = targetChanges.map { it.windowId }
        require(openingWindowIds.size == 1) {
            "Expected 1 opening window but got ${openingWindowIds.size}"
        }

        val windowId = openingWindowIds.first()
        val layerId = openingLayerIds.first()
        return FullComponentIdMatcher(windowId, layerId)
    }

    private fun closingAppFrom(transition: Transition): IComponentMatcher {
        val targetChanges =
            transition.changes.filter {
                it.transitMode == TransitionType.CLOSE || it.transitMode == TransitionType.TO_BACK
            }

        val closingLayerIds = targetChanges.map { it.layerId }
        require(closingLayerIds.size == 1) {
            "Expected 1 closing layer but got ${closingLayerIds.size}"
        }

        val closingWindowIds = targetChanges.map { it.windowId }
        require(closingWindowIds.size == 1) {
            "Expected 1 closing window but got ${closingWindowIds.size}"
        }

        val windowId = closingWindowIds.first()
        val layerId = closingLayerIds.first()
        return FullComponentIdMatcher(windowId, layerId)
    }

    val byType: Map<String, ComponentTemplate> =
        mapOf("OPENING_APP" to OPENING_APP, "CLOSING_APP" to CLOSING_APP)
}
