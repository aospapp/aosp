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

import android.surfaceflinger.Layerstrace
import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.io.TraceType
import android.tools.device.traces.monitors.TraceMonitorTest
import android.tools.device.traces.monitors.withSFTracing
import com.google.common.truth.Truth
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Contains [LayersTraceMonitor] tests. To run this test: `atest
 * FlickerLibTest:LayersTraceMonitorTest`
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LayersTraceMonitorTest : TraceMonitorTest<LayersTraceMonitor>() {
    override val traceType = TraceType.SF
    override fun getMonitor() = LayersTraceMonitor()

    override fun assertTrace(traceData: ByteArray) {
        val trace = Layerstrace.LayersTraceFileProto.parseFrom(traceData)
        Truth.assertThat(trace.magicNumber)
            .isEqualTo(
                Layerstrace.LayersTraceFileProto.MagicNumber.MAGIC_NUMBER_H.number.toLong() shl
                    32 or
                    Layerstrace.LayersTraceFileProto.MagicNumber.MAGIC_NUMBER_L.number.toLong()
            )
    }

    @Test
    fun withSFTracingTest() {
        val trace = withSFTracing {
            device.pressHome()
            device.pressRecentApps()
        }

        Truth.assertWithMessage("Could not obtain SF trace").that(trace.entries).isNotEmpty()
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
