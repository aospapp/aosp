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

import android.tools.common.datatypes.ActiveBuffer
import android.tools.common.datatypes.Color
import android.tools.common.datatypes.Rect
import android.tools.common.datatypes.RectF
import android.tools.common.datatypes.Region
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Common properties of a layer that are not related to their position in the hierarchy
 *
 * These properties are frequently stable throughout the trace and can be more efficiently cached
 * than the full layers
 */
@JsExport
interface ILayerProperties {
    val visibleRegion: Region?
    @JsName("activeBuffer") val activeBuffer: ActiveBuffer
    @JsName("flags") val flags: Int
    @JsName("bounds") val bounds: RectF
    @JsName("color") val color: Color
    @JsName("isOpaque") val isOpaque: Boolean
    @JsName("shadowRadius") val shadowRadius: Float
    @JsName("cornerRadius") val cornerRadius: Float
    @JsName("type") val type: String
    @JsName("screenBounds") val screenBounds: RectF
    @JsName("transform") val transform: Transform
    @JsName("sourceBounds") val sourceBounds: RectF
    @JsName("effectiveScalingMode") val effectiveScalingMode: Int
    @JsName("bufferTransform") val bufferTransform: Transform
    @JsName("hwcCompositionType") val hwcCompositionType: HwcCompositionType
    @JsName("hwcCrop") val hwcCrop: RectF
    @JsName("hwcFrame") val hwcFrame: Rect
    @JsName("backgroundBlurRadius") val backgroundBlurRadius: Int
    @JsName("crop") val crop: Rect
    @JsName("isRelativeOf") val isRelativeOf: Boolean
    @JsName("zOrderRelativeOfId") val zOrderRelativeOfId: Int
    @JsName("stackId") val stackId: Int
    @JsName("requestedTransform") val requestedTransform: Transform
    @JsName("requestedColor") val requestedColor: Color
    @JsName("cornerRadiusCrop") val cornerRadiusCrop: RectF
    @JsName("inputTransform") val inputTransform: Transform
    @JsName("inputRegion") val inputRegion: Region?
    @JsName("excludesCompositionState") val excludesCompositionState: Boolean

    @JsName("isScaling")
    val isScaling: Boolean
        get() = transform.isScaling
    @JsName("isTranslating")
    val isTranslating: Boolean
        get() = transform.isTranslating
    @JsName("isRotating")
    val isRotating: Boolean
        get() = transform.isRotating

    /**
     * Checks if the layer's active buffer is empty
     *
     * An active buffer is empty if it is not in the proto or if its height or width are 0
     *
     * @return
     */
    @JsName("isActiveBufferEmpty")
    val isActiveBufferEmpty: Boolean
        get() = activeBuffer.isEmpty

    /**
     * Converts flags to human readable tokens.
     *
     * @return
     */
    @JsName("verboseFlags")
    val verboseFlags: String
        get() {
            val tokens = Flag.values().filter { (it.value and flags) != 0 }.map { it.name }

            return if (tokens.isEmpty()) {
                ""
            } else {
                "${tokens.joinToString("|")} (0x${flags.toString(16)})"
            }
        }

    /**
     * Checks if the [Layer] has a color
     *
     * @return
     */
    @JsName("fillsColor")
    val fillsColor: Boolean
        get() = color.isNotEmpty

    /**
     * Checks if the [Layer] draws a shadow
     *
     * @return
     */
    @JsName("drawsShadows")
    val drawsShadows: Boolean
        get() = shadowRadius > 0

    /**
     * Checks if the [Layer] has blur
     *
     * @return
     */
    @JsName("hasBlur")
    val hasBlur: Boolean
        get() = backgroundBlurRadius > 0

    /**
     * Checks if the [Layer] has rounded corners
     *
     * @return
     */
    @JsName("hasRoundedCorners")
    val hasRoundedCorners: Boolean
        get() = cornerRadius > 0

    /**
     * Checks if the [Layer] draws has effects, which include:
     * - is a color layer
     * - is an effects layers which [fillsColor] or [drawsShadows]
     *
     * @return
     */
    @JsName("hasEffects")
    val hasEffects: Boolean
        get() {
            return fillsColor || drawsShadows
        }

    fun isAnimating(prevLayerState: ILayerProperties?): Boolean =
        when (prevLayerState) {
            // when there's no previous state, use a heuristic based on the transform
            null -> !transform.isSimpleRotation
            else ->
                visibleRegion != prevLayerState.visibleRegion ||
                    transform != prevLayerState.transform ||
                    color != prevLayerState.color
        }
}
