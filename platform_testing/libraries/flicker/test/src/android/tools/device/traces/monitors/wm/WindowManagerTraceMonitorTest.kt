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

package android.tools.device.traces.monitors.wm

import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.io.TraceType
import android.tools.device.traces.monitors.TraceMonitorTest
import android.tools.device.traces.monitors.withWMTracing
import com.android.server.wm.nano.WindowManagerTraceFileProto
import com.google.common.truth.Truth
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Contains [WindowManagerTraceMonitor] tests. To run this test: `atest
 * FlickerLibTest:LayersTraceMonitorTest`
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WindowManagerTraceMonitorTest : TraceMonitorTest<WindowManagerTraceMonitor>() {
    override val traceType = TraceType.WM
    override fun getMonitor() = WindowManagerTraceMonitor()

    override fun assertTrace(traceData: ByteArray) {
        val trace = WindowManagerTraceFileProto.parseFrom(traceData)
        Truth.assertThat(trace.magicNumber)
            .isEqualTo(
                WindowManagerTraceFileProto.MAGIC_NUMBER_H.toLong() shl
                    32 or
                    WindowManagerTraceFileProto.MAGIC_NUMBER_L.toLong()
            )
    }

    @Test
    fun withWMTracingTest() {
        val trace = withWMTracing {
            device.pressHome()
            device.pressRecentApps()
        }

        Truth.assertWithMessage("Could not obtain WM trace").that(trace.entries).isNotEmpty()
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
