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

@file:JvmName("MonitorUtils")

package android.tools.device.traces.monitors

import android.tools.common.traces.DeviceTraceDump
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.wm.WindowManagerTrace
import android.tools.device.traces.monitors.surfaceflinger.LayersTraceMonitor
import android.tools.device.traces.monitors.wm.WindowManagerTraceMonitor
import android.tools.device.traces.parsers.DeviceDumpParser
import android.tools.device.traces.parsers.surfaceflinger.LayersTraceParser
import android.tools.device.traces.parsers.wm.WindowManagerTraceParser

/**
 * Acquire the [WindowManagerTrace] with the device state changes that happen when executing the
 * commands defined in the [predicate].
 *
 * @param predicate Commands to execute
 * @throws UnsupportedOperationException If tracing is already activated
 */
fun withWMTracing(predicate: () -> Unit): WindowManagerTrace {
    return WindowManagerTraceParser().parse(WindowManagerTraceMonitor().withTracing(predicate))
}

/**
 * Acquire the [LayersTrace] with the device state changes that happen when executing the commands
 * defined in the [predicate].
 *
 * @param traceFlags Flags to indicate tracing level
 * @param predicate Commands to execute
 * @throws UnsupportedOperationException If tracing is already activated
 */
@JvmOverloads
fun withSFTracing(
    traceFlags: Int = LayersTraceMonitor.TRACE_FLAGS,
    predicate: () -> Unit
): LayersTrace {
    return LayersTraceParser().parse(LayersTraceMonitor(traceFlags).withTracing(predicate))
}

/**
 * Acquire the [WindowManagerTrace] and [LayersTrace] with the device state changes that happen when
 * executing the commands defined in the [predicate].
 *
 * @param predicate Commands to execute
 * @throws UnsupportedOperationException If tracing is already activated
 */
fun withTracing(predicate: () -> Unit): DeviceTraceDump {
    val traces = recordTraces(predicate)
    val wmTraceData = traces.first
    val layersTraceData = traces.second
    return DeviceDumpParser.fromTrace(wmTraceData, layersTraceData, clearCache = true)
}

/**
 * Acquire the [WindowManagerTrace] and [LayersTrace] with the device state changes that happen when
 * executing the commands defined in the [predicate].
 *
 * @param predicate Commands to execute
 * @return a pair containing the WM and SF traces
 * @throws UnsupportedOperationException If tracing is already activated
 */
fun recordTraces(predicate: () -> Unit): Pair<ByteArray, ByteArray> {
    var wmTraceData = ByteArray(0)
    val layersTraceData =
        LayersTraceMonitor().withTracing {
            wmTraceData = WindowManagerTraceMonitor().withTracing(predicate)
        }

    return Pair(wmTraceData, layersTraceData)
}
