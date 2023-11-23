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

package android.tools.common.traces.surfaceflinger

import android.tools.common.CrossPlatform
import android.tools.common.ITraceEntry
import android.tools.common.datatypes.Rect
import android.tools.common.datatypes.RectF
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.IComponentMatcher
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents a single Layer trace entry.
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
class LayerTraceEntry(
    @JsName("elapsedTimestamp") val elapsedTimestamp: Long,
    @JsName("clockTimestamp") val clockTimestamp: Long?,
    val hwcBlob: String,
    @JsName("where") val where: String,
    @JsName("displays") val displays: Array<Display>,
    @JsName("vSyncId") val vSyncId: Long,
    _rootLayers: Array<Layer>
) : ITraceEntry {
    override val timestamp =
        CrossPlatform.timestamp.from(
            systemUptimeNanos = elapsedTimestamp,
            unixNanos = clockTimestamp
        )

    @JsName("stableId")
    val stableId: String = this::class.simpleName ?: error("Unable to determine class")

    @JsName("flattenedLayers") val flattenedLayers: Array<Layer> = fillFlattenedLayers(_rootLayers)

    // for winscope
    @JsName("isVisible") val isVisible: Boolean = true

    @JsName("visibleLayers")
    val visibleLayers: Array<Layer>
        get() = flattenedLayers.filter { it.isVisible }.toTypedArray()

    @JsName("children")
    val children: Array<Layer>
        get() = flattenedLayers.filter { it.isRootLayer }.toTypedArray()

    @JsName("physicalDisplay")
    val physicalDisplay: Display?
        get() = displays.firstOrNull { !it.isVirtual }

    @JsName("physicalDisplayBounds")
    val physicalDisplayBounds: Rect?
        get() = physicalDisplay?.layerStackSpace

    /**
     * @param componentMatcher Components to search
     * @return A [Layer] matching [componentMatcher] with a non-empty active buffer, or null if no
     *   layer matches [componentMatcher] or if the matching layer's buffer is empty
     */
    fun getLayerWithBuffer(componentMatcher: IComponentMatcher): Layer? {
        return flattenedLayers.firstOrNull {
            componentMatcher.layerMatchesAnyOf(it) && it.activeBuffer.isNotEmpty
        }
    }

    /** @return The [Layer] with [layerId], or null if the layer is not found */
    fun getLayerById(layerId: Int): Layer? = this.flattenedLayers.firstOrNull { it.id == layerId }

    /**
     * Checks if any layer matching [componentMatcher] in the screen is animating.
     *
     * The screen is animating when a layer is not simple rotation, of when the pip overlay layer is
     * visible
     *
     * @param componentMatcher Components to search
     */
    fun isAnimating(
        prevState: LayerTraceEntry?,
        componentMatcher: IComponentMatcher? = null
    ): Boolean {
        val curLayers =
            visibleLayers.filter {
                componentMatcher == null || componentMatcher.layerMatchesAnyOf(it)
            }
        val currIds = visibleLayers.map { it.id }
        val prevStateLayers =
            prevState?.visibleLayers?.filter { currIds.contains(it.id) } ?: emptyList()
        val layersAnimating =
            curLayers.any { currLayer ->
                val prevLayer = prevStateLayers.firstOrNull { it.id == currLayer.id }
                currLayer.isAnimating(prevLayer)
            }
        val pipAnimating = isVisible(ComponentNameMatcher.PIP_CONTENT_OVERLAY)
        return layersAnimating || pipAnimating
    }

    /**
     * Check if at least one window matching [componentMatcher] is visible.
     *
     * @param componentMatcher Components to search
     */
    @JsName("isVisibleComponent")
    fun isVisible(componentMatcher: IComponentMatcher): Boolean =
        componentMatcher.layerMatchesAnyOf(visibleLayers)

    /** @return A [LayersTrace] object containing this state as its only entry */
    fun asTrace(): LayersTrace = LayersTrace(arrayOf(this))

    override fun toString(): String = timestamp.toString()

    override fun equals(other: Any?): Boolean {
        return other is LayerTraceEntry && other.timestamp == this.timestamp
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + hwcBlob.hashCode()
        result = 31 * result + where.hashCode()
        result = 31 * result + displays.contentHashCode()
        result = 31 * result + isVisible.hashCode()
        result = 31 * result + flattenedLayers.contentHashCode()
        return result
    }

    private fun fillFlattenedLayers(rootLayers: Array<Layer>): Array<Layer> {
        val layers = mutableListOf<Layer>()
        val roots = rootLayers.fillOcclusionState().toMutableList()
        while (roots.isNotEmpty()) {
            val layer = roots.removeAt(0)
            layers.add(layer)
            roots.addAll(layer.children)
        }
        return layers.toTypedArray()
    }

    private fun Array<Layer>.topDownTraversal(): List<Layer> {
        return this.sortedBy { it.z }.flatMap { it.topDownTraversal() }
    }

    private fun Layer.topDownTraversal(): List<Layer> {
        val traverseList = mutableListOf(this)

        this.children
            .sortedBy { it.z }
            .forEach { childLayer -> traverseList.addAll(childLayer.topDownTraversal()) }

        return traverseList
    }

    private fun Array<Layer>.fillOcclusionState(): Array<Layer> {
        val traversalList = topDownTraversal().reversed()

        val opaqueLayers = mutableListOf<Layer>()
        val transparentLayers = mutableListOf<Layer>()

        traversalList.forEach { layer ->
            val visible = layer.isVisible
            val displaySize =
                displays
                    .firstOrNull { it.layerStackId == layer.stackId }
                    ?.layerStackSpace
                    ?.toRectF()
                    ?: RectF.EMPTY

            if (visible) {
                val occludedBy =
                    opaqueLayers
                        .filter {
                            it.stackId == layer.stackId &&
                                it.contains(layer, displaySize) &&
                                (!it.hasRoundedCorners || (layer.cornerRadius == it.cornerRadius))
                        }
                        .toTypedArray()
                layer.addOccludedBy(occludedBy)
                val partiallyOccludedBy =
                    opaqueLayers
                        .filter {
                            it.stackId == layer.stackId &&
                                it.overlaps(layer, displaySize) &&
                                it !in layer.occludedBy
                        }
                        .toTypedArray()
                layer.addPartiallyOccludedBy(partiallyOccludedBy)
                val coveredBy =
                    transparentLayers
                        .filter { it.stackId == layer.stackId && it.overlaps(layer, displaySize) }
                        .toTypedArray()
                layer.addCoveredBy(coveredBy)

                if (layer.isOpaque) {
                    opaqueLayers.add(layer)
                } else {
                    transparentLayers.add(layer)
                }
            }
        }

        return this
    }
}
