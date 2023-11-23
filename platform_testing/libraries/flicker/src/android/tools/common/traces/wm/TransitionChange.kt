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

package android.tools.common.traces.wm

import android.tools.common.traces.surfaceflinger.LayersTrace
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
class TransitionChange(
    @JsName("transitMode") val transitMode: TransitionType,
    @JsName("layerId") val layerId: Int,
    @JsName("windowId") val windowId: Int,
) {

    override fun toString(): String = Formatter(null, null).format(this)

    class Formatter(val layersTrace: LayersTrace?, val wmTrace: WindowManagerTrace?) {
        fun format(change: TransitionChange): String {
            val layerName =
                layersTrace
                    ?.entries
                    ?.flatMap { it.flattenedLayers.asList() }
                    ?.firstOrNull { it.id == change.layerId }
                    ?.name

            val windowName =
                wmTrace
                    ?.entries
                    ?.flatMap { it.windowStates.asList() }
                    ?.firstOrNull { it.token == change.windowId.toString(16) }
                    ?.name

            return buildString {
                append("TransitionChange(")
                append("transitMode=${change.transitMode}, ")
                append("layerId=${change.layerId}, ")
                if (layerName != null) {
                    append("layerName=$layerName, ")
                }
                append("windowId=${change.windowId}, ")
                if (windowName != null) {
                    append("windowName=$windowName, ")
                }
                append(")")
            }
        }
    }
}
