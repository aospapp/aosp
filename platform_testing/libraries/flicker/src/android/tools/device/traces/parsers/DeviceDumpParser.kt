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

package android.tools.device.traces.parsers

import android.tools.common.CrossPlatform
import android.tools.common.traces.DeviceStateDump
import android.tools.common.traces.DeviceTraceDump
import android.tools.common.traces.NullableDeviceStateDump
import android.tools.common.traces.surfaceflinger.LayerTraceEntry
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.wm.WindowManagerState
import android.tools.common.traces.wm.WindowManagerTrace
import android.tools.device.traces.parsers.surfaceflinger.LayersTraceParser
import android.tools.device.traces.parsers.wm.WindowManagerDumpParser
import android.tools.device.traces.parsers.wm.WindowManagerTraceParser

/**
 * Represents a state dump containing the [WindowManagerTrace] and the [LayersTrace] both parsed and
 * in raw (byte) data.
 */
class DeviceDumpParser {
    companion object {
        /**
         * Creates a device state dump containing the [WindowManagerTrace] and [LayersTrace]
         * obtained from a `dumpsys` command. The parsed traces will contain a single
         * [WindowManagerState] or [LayerTraceEntry].
         *
         * @param wmTraceData [WindowManagerTrace] content
         * @param layersTraceData [LayersTrace] content
         * @param clearCacheAfterParsing If the caching used while parsing the proto should be
         *
         * ```
         *                               cleared or remain in memory
         * ```
         */
        @JvmStatic
        fun fromNullableDump(
            wmTraceData: ByteArray,
            layersTraceData: ByteArray,
            clearCacheAfterParsing: Boolean
        ): NullableDeviceStateDump {
            return CrossPlatform.log.withTracing("fromNullableDump") {
                NullableDeviceStateDump(
                    wmState =
                        if (wmTraceData.isNotEmpty()) {
                            WindowManagerDumpParser()
                                .parse(wmTraceData, clearCache = clearCacheAfterParsing)
                                .entries
                                .first()
                        } else {
                            null
                        },
                    layerState =
                        if (layersTraceData.isNotEmpty()) {
                            LayersTraceParser()
                                .parse(layersTraceData, clearCache = clearCacheAfterParsing)
                                .entries
                                .first()
                        } else {
                            null
                        }
                )
            }
        }

        /** See [fromNullableDump] */
        @JvmStatic
        fun fromDump(
            wmTraceData: ByteArray,
            layersTraceData: ByteArray,
            clearCacheAfterParsing: Boolean
        ): DeviceStateDump {
            return CrossPlatform.log.withTracing("fromDump") {
                val nullableDump =
                    fromNullableDump(wmTraceData, layersTraceData, clearCacheAfterParsing)
                DeviceStateDump(
                    nullableDump.wmState ?: error("WMState dump missing"),
                    nullableDump.layerState ?: error("Layer State dump missing")
                )
            }
        }

        /**
         * Creates a device state dump containing the WindowManager and Layers trace obtained from a
         * regular trace. The parsed traces may contain a multiple [WindowManagerState] or
         * [LayerTraceEntry].
         *
         * @param wmTraceData [WindowManagerTrace] content
         * @param layersTraceData [LayersTrace] content
         * @param clearCache If the caching used while parsing the proto should be
         *
         * ```
         *                               cleared or remain in memory
         * ```
         */
        @JvmStatic
        fun fromTrace(
            wmTraceData: ByteArray,
            layersTraceData: ByteArray,
            clearCache: Boolean
        ): DeviceTraceDump {
            return CrossPlatform.log.withTracing("fromTrace") {
                DeviceTraceDump(
                    wmTrace =
                        if (wmTraceData.isNotEmpty()) {
                            WindowManagerTraceParser().parse(wmTraceData, clearCache = clearCache)
                        } else {
                            null
                        },
                    layersTrace =
                        if (layersTraceData.isNotEmpty()) {
                            LayersTraceParser().parse(layersTraceData, clearCache = clearCache)
                        } else {
                            null
                        }
                )
            }
        }
    }
}
