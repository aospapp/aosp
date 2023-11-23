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

package android.tools.device.traces.monitors.surfaceflinger

import android.tools.common.io.TraceType
import android.tools.common.io.WINSCOPE_EXT
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.device.traces.monitors.TraceMonitor
import android.view.WindowManagerGlobal
import java.io.File

/** Captures [LayersTrace] from SurfaceFlinger. */
open class LayersTraceMonitor @JvmOverloads constructor(private val traceFlags: Int = TRACE_FLAGS) :
    TraceMonitor() {
    private val windowManager = WindowManagerGlobal.getWindowManagerService()
    override val traceType = TraceType.SF
    override val isEnabled
        get() = windowManager.isLayerTracing

    override fun doStart() {
        windowManager.setLayerTracingFlags(traceFlags)
        windowManager.isLayerTracing = true
    }

    override fun doStop(): File {
        windowManager.isLayerTracing = false
        return TRACE_DIR.resolve("layers_trace$WINSCOPE_EXT")
    }

    companion object {
        const val TRACE_FLAGS = 0x47 // TRACE_CRITICAL|TRACE_INPUT|TRACE_COMPOSITION|TRACE_SYNC
    }
}
