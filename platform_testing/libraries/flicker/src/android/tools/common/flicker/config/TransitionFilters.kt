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

package android.tools.common.flicker.config

import android.tools.common.PlatformConsts.SPLIT_SCREEN_TRANSITION_HANDLER
import android.tools.common.flicker.extractors.ITransitionMatcher
import android.tools.common.flicker.extractors.TransitionsTransform
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.wm.Transition
import android.tools.common.traces.wm.TransitionType
import android.tools.common.traces.wm.WmTransitionData

object TransitionFilters {
    val OPEN_APP_TRANSITION_FILTER: TransitionsTransform = { ts, _, _ ->
        ts.filter { t ->
            t.changes.any {
                it.transitMode == TransitionType.OPEN || // cold launch
                it.transitMode == TransitionType.TO_FRONT // warm launch
            }
        }
    }

    val CLOSE_APP_TO_LAUNCHER_FILTER: TransitionsTransform = { ts, _, reader ->
        val layersTrace = reader.readLayersTrace() ?: error("Missing layers trace")
        val layers =
            layersTrace.entries.flatMap { it.flattenedLayers.asList() }.distinctBy { it.id }
        val launcherLayers = layers.filter { ComponentNameMatcher.LAUNCHER.layerMatchesAnyOf(it) }

        ts.filter { t ->
            t.changes.any {
                it.transitMode == TransitionType.CLOSE || it.transitMode == TransitionType.TO_BACK
            } &&
                t.changes.any { change ->
                    launcherLayers.any { it.id == change.layerId }
                    change.transitMode == TransitionType.TO_FRONT
                }
        }
    }

    // TODO: Quick switch with split screen support (b/285142231)
    val QUICK_SWITCH_TRANSITION_FILTER: TransitionsTransform = { ts, _, reader ->
        val layersTrace = reader.readLayersTrace() ?: error("Missing layers trace")

        val enterQuickswitchTransitions =
            ts.filter { t ->
                t.changes.size == 3 &&
                    t.changes.any {
                        it.transitMode == TransitionType.TO_FRONT &&
                            isLauncherTopLevelTaskLayer(it.layerId, layersTrace)
                    } && // LAUNCHER
                    t.changes.any {
                        it.transitMode == TransitionType.TO_FRONT &&
                            isWallpaperTokenLayer(it.layerId, layersTrace)
                    } && // WALLPAPER
                    t.changes.any { it.transitMode == TransitionType.TO_BACK } // closing app
            }

        val finalTransitions = mutableListOf<Transition>()
        for (enterQuickswitchTransition in enterQuickswitchTransitions) {
            val matchingExitQuickswitchTransitions =
                ts.filter { t ->
                    t.changes.size == 2 &&
                        t.changes.any {
                            it.transitMode == TransitionType.TO_BACK &&
                                isLauncherTopLevelTaskLayer(it.layerId, layersTrace)
                        } && // LAUNCHER
                        t.changes.any {
                            it.transitMode == TransitionType.TO_FRONT
                        } && // opening app
                        t.mergedInto ==
                            enterQuickswitchTransition
                                .id // transition merged into previous transition
                }

            if (matchingExitQuickswitchTransitions.isEmpty()) {
                continue
            }

            require(matchingExitQuickswitchTransitions.size == 1) {
                "Expected 1 transition to have the exit quickswitch properties but got " +
                    "${matchingExitQuickswitchTransitions.size}"
            }
            finalTransitions.add(
                enterQuickswitchTransition.merge(matchingExitQuickswitchTransitions[0])
            )
        }

        finalTransitions
    }

    val QUICK_SWITCH_TRANSITION_POST_PROCESSING: TransitionsTransform = { transitions, _, reader ->
        require(transitions.size == 1) { "Expected 1 transition but got ${transitions.size}" }

        val transition = transitions[0]

        require(transition.changes.size == 5)
        require(transition.changes.count { it.transitMode == TransitionType.TO_BACK } == 2)
        require(transition.changes.count { it.transitMode == TransitionType.TO_FRONT } == 3)

        val layersTrace = reader.readLayersTrace() ?: error("Missing layers trace")
        val wallpaperId =
            transition.changes
                .map { it.layerId }
                .firstOrNull { isWallpaperTokenLayer(it, layersTrace) }
                ?: error("Missing wallpaper layer in transition")
        val launcherId =
            transition.changes
                .map { it.layerId }
                .firstOrNull { isLauncherTopLevelTaskLayer(it, layersTrace) }
                ?: error("Missing launcher layer in transition")

        val filteredChanges =
            transition.changes.filter { it.layerId != wallpaperId && it.layerId != launcherId }

        val closingAppChange = filteredChanges.first { it.transitMode == TransitionType.TO_BACK }
        val openingAppChange = filteredChanges.first { it.transitMode == TransitionType.TO_FRONT }

        listOf(
            Transition(
                transition.id,
                WmTransitionData(
                    createTime = transition.wmData.createTime,
                    sendTime = transition.wmData.sendTime,
                    abortTime = transition.wmData.abortTime,
                    finishTime = transition.wmData.finishTime,
                    startTransactionId = transition.wmData.startTransactionId,
                    finishTransactionId = transition.wmData.finishTransactionId,
                    type = transition.wmData.type,
                    changes = arrayOf(closingAppChange, openingAppChange),
                ),
                transition.shellData
            )
        )
    }

    private fun isLauncherTopLevelTaskLayer(layerId: Int, layersTrace: LayersTrace): Boolean {
        return layersTrace.entries.any { entry ->
            val launcherLayer =
                entry.flattenedLayers.firstOrNull { layer ->
                    ComponentNameMatcher.LAUNCHER.or(ComponentNameMatcher.AOSP_LAUNCHER)
                        .layerMatchesAnyOf(layer)
                }
                    ?: return@any false

            var curLayer = launcherLayer
            while (!curLayer.isTask && curLayer.parent != null) {
                curLayer = curLayer.parent ?: error("unreachable")
            }
            if (!curLayer.isTask) {
                error("Expected a task layer above the launcher layer")
            }

            var launcherTopLevelTaskLayer = curLayer
            // Might have nested task layers
            while (
                launcherTopLevelTaskLayer.parent != null &&
                    launcherTopLevelTaskLayer.parent!!.isTask
            ) {
                launcherTopLevelTaskLayer = launcherTopLevelTaskLayer.parent ?: error("unreachable")
            }

            return@any launcherTopLevelTaskLayer.id == layerId
        }
    }

    private fun isWallpaperTokenLayer(layerId: Int, layersTrace: LayersTrace): Boolean {
        return layersTrace.entries.any { entry ->
            entry.flattenedLayers.any { layer ->
                layer.id == layerId &&
                    ComponentNameMatcher.WALLPAPER_WINDOW_TOKEN.layerMatchesAnyOf(layer)
            }
        }
    }

    val APP_CLOSE_TO_PIP_TRANSITION_FILTER: TransitionsTransform = { ts, _, _ ->
        ts.filter { it.type == TransitionType.PIP }
    }

    val ENTER_SPLIT_SCREEN_MATCHER =
        object : ITransitionMatcher {
            override fun findAll(transitions: Collection<Transition>): Collection<Transition> {
                return transitions.filter { isSplitscreenEnterTransition(it) }
            }
        }

    val EXIT_SPLIT_SCREEN_FILTER: TransitionsTransform = { ts, _, _ ->
        ts.filter { isSplitscreenExitTransition(it) }
    }

    val RESIZE_SPLIT_SCREEN_FILTER: TransitionsTransform = { ts, _, _ ->
        ts.filter { isSplitscreenResizeTransition(it) }
    }

    fun isSplitscreenEnterTransition(transition: Transition): Boolean {
        return transition.handler == SPLIT_SCREEN_TRANSITION_HANDLER &&
            transition.type == TransitionType.TO_FRONT
    }

    fun isSplitscreenExitTransition(transition: Transition): Boolean {
        return transition.type == TransitionType.SPLIT_DISMISS ||
            transition.type == TransitionType.SPLIT_DISMISS_SNAP
    }

    fun isSplitscreenResizeTransition(transition: Transition): Boolean {
        // This transition doesn't have a special type
        return transition.type == TransitionType.CHANGE &&
            transition.changes.size == 2 &&
            transition.changes.all { change -> change.transitMode == TransitionType.CHANGE }
    }
}
