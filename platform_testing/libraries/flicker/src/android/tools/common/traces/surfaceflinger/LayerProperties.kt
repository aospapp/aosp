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
import android.tools.common.withCache
import kotlin.js.JsExport
import kotlin.js.JsName

/** {@inheritDoc} */
@JsExport
class LayerProperties
private constructor(
    override val visibleRegion: Region = Region.EMPTY,
    override val activeBuffer: ActiveBuffer = ActiveBuffer.EMPTY,
    override val flags: Int = 0,
    override val bounds: RectF = RectF.EMPTY,
    override val color: Color = Color.EMPTY,
    private val _isOpaque: Boolean = false,
    override val shadowRadius: Float = 0f,
    override val cornerRadius: Float = 0f,
    override val type: String = "",
    override val screenBounds: RectF = RectF.EMPTY,
    override val transform: Transform = Transform.EMPTY,
    override val sourceBounds: RectF = RectF.EMPTY,
    override val effectiveScalingMode: Int = 0,
    override val bufferTransform: Transform = Transform.EMPTY,
    override val hwcCompositionType: HwcCompositionType = HwcCompositionType.INVALID,
    override val hwcCrop: RectF = RectF.EMPTY,
    override val hwcFrame: Rect = Rect.EMPTY,
    override val backgroundBlurRadius: Int = 0,
    override val crop: Rect = Rect.EMPTY,
    override val isRelativeOf: Boolean = false,
    override val zOrderRelativeOfId: Int = 0,
    override val stackId: Int = 0,
    override val requestedTransform: Transform = Transform.EMPTY,
    override val requestedColor: Color = Color.EMPTY,
    override val cornerRadiusCrop: RectF = RectF.EMPTY,
    override val inputTransform: Transform = Transform.EMPTY,
    override val inputRegion: Region? = null,
    override val excludesCompositionState: Boolean = false
) : ILayerProperties {
    override val isOpaque: Boolean = if (color.a != 1.0f) false else _isOpaque

    override fun hashCode(): Int {
        var result = visibleRegion.hashCode()
        result = 31 * result + activeBuffer.hashCode()
        result = 31 * result + flags
        result = 31 * result + bounds.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + _isOpaque.hashCode()
        result = 31 * result + shadowRadius.hashCode()
        result = 31 * result + cornerRadius.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + screenBounds.hashCode()
        result = 31 * result + transform.hashCode()
        result = 31 * result + sourceBounds.hashCode()
        result = 31 * result + effectiveScalingMode
        result = 31 * result + bufferTransform.hashCode()
        result = 31 * result + hwcCompositionType.hashCode()
        result = 31 * result + hwcCrop.hashCode()
        result = 31 * result + hwcFrame.hashCode()
        result = 31 * result + backgroundBlurRadius
        result = 31 * result + crop.hashCode()
        result = 31 * result + isRelativeOf.hashCode()
        result = 31 * result + zOrderRelativeOfId
        result = 31 * result + stackId
        result = 31 * result + requestedTransform.hashCode()
        result = 31 * result + requestedColor.hashCode()
        result = 31 * result + cornerRadiusCrop.hashCode()
        result = 31 * result + inputTransform.hashCode()
        result = 31 * result + (inputRegion?.hashCode() ?: 0)
        result = 31 * result + screenBounds.hashCode()
        result = 31 * result + isOpaque.hashCode()
        result = 31 * result + excludesCompositionState.hashCode()
        return result
    }

    override fun toString(): String {
        return "LayerProperties(visibleRegion=$visibleRegion, activeBuffer=$activeBuffer, " +
            "flags=$flags, bounds=$bounds, color=$color, _isOpaque=$_isOpaque, " +
            "shadowRadius=$shadowRadius, cornerRadius=$cornerRadius, type='$type', " +
            "screenBounds=$screenBounds, transform=$transform, sourceBounds=$sourceBounds, " +
            "effectiveScalingMode=$effectiveScalingMode, bufferTransform=$bufferTransform, " +
            "hwcCompositionType=$hwcCompositionType, hwcCrop=$hwcCrop, hwcFrame=$hwcFrame, " +
            "backgroundBlurRadius=$backgroundBlurRadius, crop=$crop, isRelativeOf=$isRelativeOf, " +
            "zOrderRelativeOfId=$zOrderRelativeOfId, stackId=$stackId, " +
            "requestedTransform=$requestedTransform, requestedColor=$requestedColor, " +
            "cornerRadiusCrop=$cornerRadiusCrop, inputTransform=$inputTransform, " +
            "inputRegion=$inputRegion, screenBounds=$screenBounds, isOpaque=$isOpaque, " +
            "excludesCompositionState=$excludesCompositionState)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerProperties) return false

        if (visibleRegion != other.visibleRegion) return false
        if (activeBuffer != other.activeBuffer) return false
        if (flags != other.flags) return false
        if (bounds != other.bounds) return false
        if (color != other.color) return false
        if (_isOpaque != other._isOpaque) return false
        if (shadowRadius != other.shadowRadius) return false
        if (cornerRadius != other.cornerRadius) return false
        if (type != other.type) return false
        if (screenBounds != other.screenBounds) return false
        if (transform != other.transform) return false
        if (sourceBounds != other.sourceBounds) return false
        if (effectiveScalingMode != other.effectiveScalingMode) return false
        if (bufferTransform != other.bufferTransform) return false
        if (hwcCompositionType != other.hwcCompositionType) return false
        if (hwcCrop != other.hwcCrop) return false
        if (hwcFrame != other.hwcFrame) return false
        if (backgroundBlurRadius != other.backgroundBlurRadius) return false
        if (crop != other.crop) return false
        if (isRelativeOf != other.isRelativeOf) return false
        if (zOrderRelativeOfId != other.zOrderRelativeOfId) return false
        if (stackId != other.stackId) return false
        if (requestedTransform != other.requestedTransform) return false
        if (requestedColor != other.requestedColor) return false
        if (cornerRadiusCrop != other.cornerRadiusCrop) return false
        if (inputTransform != other.inputTransform) return false
        if (inputRegion != other.inputRegion) return false
        if (screenBounds != other.screenBounds) return false
        if (isOpaque != other.isOpaque) return false
        if (excludesCompositionState != other.excludesCompositionState) return false

        return true
    }

    companion object {
        @JsName("EMPTY")
        val EMPTY: LayerProperties
            get() = withCache { LayerProperties() }

        @JsName("from")
        fun from(
            visibleRegion: Region,
            activeBuffer: ActiveBuffer,
            flags: Int,
            bounds: RectF,
            color: Color,
            isOpaque: Boolean,
            shadowRadius: Float,
            cornerRadius: Float,
            type: String,
            screenBounds: RectF,
            transform: Transform,
            sourceBounds: RectF,
            effectiveScalingMode: Int,
            bufferTransform: Transform,
            hwcCompositionType: HwcCompositionType,
            hwcCrop: RectF,
            hwcFrame: Rect,
            backgroundBlurRadius: Int,
            crop: Rect?,
            isRelativeOf: Boolean,
            zOrderRelativeOfId: Int,
            stackId: Int,
            requestedTransform: Transform,
            requestedColor: Color,
            cornerRadiusCrop: RectF,
            inputTransform: Transform,
            inputRegion: Region?,
            excludesCompositionState: Boolean
        ): ILayerProperties {
            return withCache {
                LayerProperties(
                    visibleRegion,
                    activeBuffer,
                    flags,
                    bounds,
                    color,
                    isOpaque,
                    shadowRadius,
                    cornerRadius,
                    type,
                    screenBounds,
                    transform,
                    sourceBounds,
                    effectiveScalingMode,
                    bufferTransform,
                    hwcCompositionType,
                    hwcCrop,
                    hwcFrame,
                    backgroundBlurRadius,
                    crop ?: Rect.EMPTY,
                    isRelativeOf,
                    zOrderRelativeOfId,
                    stackId,
                    requestedTransform,
                    requestedColor,
                    cornerRadiusCrop,
                    inputTransform,
                    inputRegion,
                    excludesCompositionState
                )
            }
        }
    }
}
