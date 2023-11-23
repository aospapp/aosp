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

package com.android.server.wm.flicker.service

import com.android.server.wm.flicker.service.assertors.AssertionData
import com.android.server.wm.flicker.service.assertors.TransitionAssertor
import com.android.server.wm.traces.common.errors.ErrorState
import com.android.server.wm.traces.common.errors.ErrorTrace
import com.android.server.wm.traces.common.layers.LayersTrace
import com.android.server.wm.traces.common.tags.Tag
import com.android.server.wm.traces.common.tags.TagTrace
import com.android.server.wm.traces.common.tags.Transition
import com.android.server.wm.traces.common.tags.TransitionTag
import com.android.server.wm.traces.common.windowmanager.WindowManagerTrace

/**
 * Invokes the configured assertors and summarizes the results.
 */
class AssertionEngine(
    private val assertions: List<AssertionData>,
    private val logger: (String) -> Unit
) {
    private val knownTypes = assertions.map { it.transitionType }

    fun analyze(
        wmTrace: WindowManagerTrace,
        layersTrace: LayersTrace,
        tagTrace: TagTrace
    ): ErrorTrace {
        val errors = mutableListOf<ErrorState>()
        val allTransitions = getTransitionTags(tagTrace)

        allTransitions
            .filter { knownTypes.contains(it.tag.transition) }
            .forEach { transition ->
                val (filteredWmTrace, filteredLayersTrace) =
                    splitTraces(transition, wmTrace, layersTrace)

                val assertionsOfType = assertions
                    .filter { it.transitionType == transition.tag.transition }
                val assertor = TransitionAssertor(assertionsOfType, logger)
                val errorTrace = assertor.analyze(
                    transition.tag, filteredWmTrace, filteredLayersTrace)
                errors.addAll(errorTrace)
            }

        /* Ensure all error states with same timestamp are merged */
        val errorStates = errors.distinct()
                .groupBy({ it.timestamp }, { it.errors.asList() })
                .mapValues { (key, value) ->
                    ErrorState(value.flatten().toTypedArray(), key.toString()) }
                .values.toTypedArray()

        return ErrorTrace(errorStates, source = "")
    }

    /**
     * Extracts all [TransitionTag]s from a [TagTrace].
     *
     * @param tagTrace Tag Trace
     * @return a list with [TransitionTag]
     */
    fun getTransitionTags(tagTrace: TagTrace): List<TransitionTag> {
        return tagTrace.entries.flatMap { state ->
            state.tags.filter { tag -> tag.isStartTag }
                .map {
                    TransitionTag(
                        tag = it,
                        startTimestamp = state.timestamp,
                        endTimestamp = getEndTagTimestamp(tagTrace, it)
                    )
                }
        }
    }

    private fun getEndTagTimestamp(tagTrace: TagTrace, tag: Tag): Long {
        val finalTag = tag.copy(isStartTag = false)
        return tagTrace.entries.firstOrNull { state -> state.tags.contains(finalTag) }?.timestamp
            ?: throw RuntimeException("All open tags should be closed!")
    }

    /**
     * Splits a [WindowManagerTrace] and a [LayersTrace] by a [Transition].
     *
     * @param tag a list with all [TransitionTag]s
     * @param wmTrace Window Manager trace
     * @param layersTrace Surface Flinger trace
     * @return a list with [WindowManagerTrace] blocks
     */
    fun splitTraces(
        tag: TransitionTag,
        wmTrace: WindowManagerTrace,
        layersTrace: LayersTrace
    ): Pair<WindowManagerTrace, LayersTrace> {
        val filteredWmTrace = wmTrace.filter(tag.startTimestamp, tag.endTimestamp)
        val filteredLayersTrace = layersTrace.filter(tag.startTimestamp, tag.endTimestamp)
        return Pair(filteredWmTrace, filteredLayersTrace)
    }
}
