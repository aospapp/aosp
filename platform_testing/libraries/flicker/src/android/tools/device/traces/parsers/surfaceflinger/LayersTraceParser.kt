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

package android.tools.device.traces.parsers.surfaceflinger

import android.surfaceflinger.Common
import android.surfaceflinger.Display
import android.surfaceflinger.Layers
import android.surfaceflinger.Layerstrace
import android.tools.common.CrossPlatform
import android.tools.common.Timestamp
import android.tools.common.datatypes.ActiveBuffer
import android.tools.common.datatypes.Color
import android.tools.common.datatypes.Matrix33
import android.tools.common.datatypes.Rect
import android.tools.common.datatypes.RectF
import android.tools.common.datatypes.Region
import android.tools.common.datatypes.Size
import android.tools.common.parsers.AbstractTraceParser
import android.tools.common.traces.surfaceflinger.HwcCompositionType
import android.tools.common.traces.surfaceflinger.Layer
import android.tools.common.traces.surfaceflinger.LayerTraceEntry
import android.tools.common.traces.surfaceflinger.LayerTraceEntryBuilder
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.surfaceflinger.Transform
import android.tools.common.traces.surfaceflinger.Transform.Companion.isFlagClear
import android.tools.common.traces.surfaceflinger.Transform.Companion.isFlagSet

/** Parser for [LayersTrace] objects containing traces or state dumps */
class LayersTraceParser(
    private val ignoreLayersStackMatchNoDisplay: Boolean = true,
    private val ignoreLayersInVirtualDisplay: Boolean = true,
    private val legacyTrace: Boolean = false,
    private val orphanLayerCallback: ((Layer) -> Boolean)? = null,
) :
    AbstractTraceParser<
        Layerstrace.LayersTraceFileProto, Layerstrace.LayersTraceProto, LayerTraceEntry, LayersTrace
    >() {
    private var realToElapsedTimeOffsetNanos = 0L

    override val traceName: String = "Layers Trace"

    override fun doDecodeByteArray(bytes: ByteArray): Layerstrace.LayersTraceFileProto =
        Layerstrace.LayersTraceFileProto.parseFrom(bytes)

    override fun createTrace(entries: List<LayerTraceEntry>): LayersTrace =
        LayersTrace(entries.toTypedArray())

    override fun getEntries(
        input: Layerstrace.LayersTraceFileProto
    ): List<Layerstrace.LayersTraceProto> = input.entryList

    override fun getTimestamp(entry: Layerstrace.LayersTraceProto): Timestamp {
        require(legacyTrace || realToElapsedTimeOffsetNanos != 0L)
        return CrossPlatform.timestamp.from(
            systemUptimeNanos = entry.elapsedRealtimeNanos,
            unixNanos = entry.elapsedRealtimeNanos + realToElapsedTimeOffsetNanos
        )
    }

    override fun onBeforeParse(input: Layerstrace.LayersTraceFileProto) {
        realToElapsedTimeOffsetNanos = input.realToElapsedTimeOffsetNanos
    }

    override fun doParseEntry(entry: Layerstrace.LayersTraceProto): LayerTraceEntry {
        val layers = entry.layers.layersList.map { newLayer(it) }.toTypedArray()
        val displays = entry.displaysList.map { newDisplay(it) }.toTypedArray()
        val builder =
            LayerTraceEntryBuilder()
                .setElapsedTimestamp(entry.elapsedRealtimeNanos.toString())
                .setLayers(layers)
                .setDisplays(displays)
                .setVSyncId(entry.vsyncId.toString())
                .setHwcBlob(entry.hwcBlob)
                .setWhere(entry.where)
                .setRealToElapsedTimeOffsetNs(realToElapsedTimeOffsetNanos.toString())
                .setOrphanLayerCallback(orphanLayerCallback)
                .ignoreLayersStackMatchNoDisplay(ignoreLayersStackMatchNoDisplay)
                .ignoreVirtualDisplay(ignoreLayersInVirtualDisplay)
        return builder.build()
    }

    companion object {
        private fun newLayer(
            proto: Layers.LayerProto,
            excludeCompositionState: Boolean = false
        ): Layer {
            // Differentiate between the cases when there's no HWC data on
            // the trace, and when the visible region is actually empty
            val activeBuffer = proto.activeBuffer.toBuffer()
            val visibleRegion = proto.visibleRegion.toRegion() ?: Region.EMPTY
            val crop = proto.crop?.toCropRect()
            return Layer.from(
                proto.name ?: "",
                proto.id,
                proto.parent,
                proto.z,
                visibleRegion,
                activeBuffer,
                proto.flags,
                proto.bounds?.toRectF() ?: RectF.EMPTY,
                proto.color.toColor(),
                proto.isOpaque,
                proto.shadowRadius,
                proto.cornerRadius,
                proto.type ?: "",
                proto.screenBounds?.toRectF() ?: RectF.EMPTY,
                createTransformFromProto(proto.transform, proto.position),
                proto.sourceBounds?.toRectF() ?: RectF.EMPTY,
                proto.currFrame,
                proto.effectiveScalingMode,
                createTransformFromProto(proto.bufferTransform, position = null),
                toHwcCompositionType(proto.hwcCompositionType),
                proto.hwcCrop.toRectF() ?: RectF.EMPTY,
                proto.hwcFrame.toRect(),
                proto.backgroundBlurRadius,
                crop,
                proto.isRelativeOf,
                proto.zOrderRelativeOf,
                proto.layerStack,
                createTransformFromProto(proto.transform, position = proto.requestedPosition),
                proto.requestedColor.toColor(),
                proto.cornerRadiusCrop?.toRectF() ?: RectF.EMPTY,
                createTransformFromProto(proto.inputWindowInfo?.transform, position = null),
                proto.inputWindowInfo?.touchableRegion?.toRegion(),
                excludeCompositionState
            )
        }

        private fun newDisplay(
            proto: Display.DisplayProto
        ): android.tools.common.traces.surfaceflinger.Display {
            return android.tools.common.traces.surfaceflinger.Display.from(
                "${proto.id}",
                proto.name,
                proto.layerStack,
                proto.size.toSize(),
                proto.layerStackSpaceRect.toRect(),
                createTransformFromProto(proto.transform, position = null),
                proto.isVirtual
            )
        }

        private fun Layers.FloatRectProto?.toRectF(): RectF? {
            return this?.let { RectF.from(left = left, top = top, right = right, bottom = bottom) }
        }

        private fun Common.SizeProto?.toSize(): Size {
            return this?.let { Size.from(this.w, this.h) } ?: Size.EMPTY
        }

        private fun Common.ColorProto?.toColor(): Color {
            return this?.let { Color.from(r, g, b, a) } ?: Color.EMPTY
        }

        private fun Layers.ActiveBufferProto?.toBuffer(): ActiveBuffer {
            return this?.let { ActiveBuffer.from(width, height, stride, format) }
                ?: ActiveBuffer.EMPTY
        }

        private fun toHwcCompositionType(value: Layers.HwcCompositionType): HwcCompositionType {
            return when (value) {
                Layers.HwcCompositionType.INVALID -> HwcCompositionType.INVALID
                Layers.HwcCompositionType.CLIENT -> HwcCompositionType.CLIENT
                Layers.HwcCompositionType.DEVICE -> HwcCompositionType.DEVICE
                Layers.HwcCompositionType.SOLID_COLOR -> HwcCompositionType.SOLID_COLOR
                Layers.HwcCompositionType.CURSOR -> HwcCompositionType.CURSOR
                Layers.HwcCompositionType.SIDEBAND -> HwcCompositionType.SIDEBAND
                else -> HwcCompositionType.UNRECOGNIZED
            }
        }

        private fun Common.RectProto?.toCropRect(): Rect? {
            return when {
                this == null -> Rect.EMPTY
                // crop (0,0) (-1,-1) means no crop
                right == -1 && left == 0 && bottom == -1 && top == 0 -> null
                (right - left) <= 0 || (bottom - top) <= 0 -> Rect.EMPTY
                else -> Rect.from(left, top, right, bottom)
            }
        }

        /**
         * Extracts [Rect] from [Common.RegionProto] by returning a rect that encompasses all the
         * rectangles making up the region.
         */
        private fun Common.RegionProto?.toRegion(): Region? {
            return this?.let {
                val rectArray = this.rectList.map { it.toRect() }.toTypedArray()
                return Region(rectArray)
            }
        }

        private fun Common.RectProto?.toRect(): Rect =
            Rect.from(this?.left ?: 0, this?.top ?: 0, this?.right ?: 0, this?.bottom ?: 0)

        fun createTransformFromProto(
            transform: Common.TransformProto?,
            position: Layers.PositionProto?
        ) = Transform.from(transform?.type, getMatrix(transform, position))

        private fun getMatrix(
            transform: Common.TransformProto?,
            position: Layers.PositionProto?
        ): Matrix33 {
            val x = position?.x ?: 0f
            val y = position?.y ?: 0f

            return when {
                transform == null || Transform.isSimpleTransform(transform.type) ->
                    transform?.type.getDefaultTransform(x, y)
                else ->
                    Matrix33.from(
                        transform.dsdx,
                        transform.dtdx,
                        x,
                        transform.dsdy,
                        transform.dtdy,
                        y
                    )
            }
        }

        private fun Int?.getDefaultTransform(x: Float, y: Float): Matrix33 {
            return when {
                // IDENTITY
                this == null -> Matrix33.identity(x, y)
                // // ROT_270 = ROT_90|FLIP_H|FLIP_V
                isFlagSet(Transform.ROT_90_VAL or Transform.FLIP_V_VAL or Transform.FLIP_H_VAL) ->
                    Matrix33.rot270(x, y)
                // ROT_180 = FLIP_H|FLIP_V
                isFlagSet(Transform.FLIP_V_VAL or Transform.FLIP_H_VAL) -> Matrix33.rot180(x, y)
                // ROT_90
                isFlagSet(Transform.ROT_90_VAL) -> Matrix33.rot90(x, y)
                // IDENTITY
                isFlagClear(Transform.SCALE_VAL or Transform.ROTATE_VAL) -> Matrix33.identity(x, y)
                else -> throw IllegalStateException("Unknown transform type $this")
            }
        }
    }
}
