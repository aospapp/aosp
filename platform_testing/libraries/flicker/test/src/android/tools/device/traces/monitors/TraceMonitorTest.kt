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

package android.tools.device.traces.monitors

import android.app.Instrumentation
import android.support.test.uiautomator.UiDevice
import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.io.RunStatus
import android.tools.common.io.TraceType
import android.tools.common.traces.DeviceTraceDump
import android.tools.device.traces.TRACE_CONFIG_REQUIRE_CHANGES
import android.tools.device.traces.deleteIfExists
import android.tools.device.traces.io.ResultReader
import android.tools.device.traces.parsers.DeviceDumpParser
import android.tools.newTestResultWriter
import android.tools.outputFileName
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test

abstract class TraceMonitorTest<T : TraceMonitor> {
    abstract fun getMonitor(): T
    abstract fun assertTrace(traceData: ByteArray)
    abstract val traceType: TraceType

    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val traceMonitor by lazy { getMonitor() }

    @Before
    fun before() {
        Truth.assertWithMessage("Trace already enabled before starting test")
            .that(traceMonitor.isEnabled)
            .isFalse()
    }

    @After
    fun teardown() {
        device.pressHome()
        if (traceMonitor.isEnabled) {
            traceMonitor.stop(newTestResultWriter())
        }
        outputFileName(RunStatus.RUN_EXECUTED).deleteIfExists()
        Truth.assertWithMessage("Failed to disable trace at end of test")
            .that(traceMonitor.isEnabled)
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun canStartTrace() {
        traceMonitor.start()
        Truth.assertThat(traceMonitor.isEnabled).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun canStopTrace() {
        traceMonitor.start()
        Truth.assertThat(traceMonitor.isEnabled).isTrue()
        traceMonitor.stop(newTestResultWriter())
        Truth.assertThat(traceMonitor.isEnabled).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun captureTrace() {
        traceMonitor.start()
        val writer = newTestResultWriter()
        traceMonitor.stop(writer)
        val result = writer.write()
        val reader = ResultReader(result, TRACE_CONFIG_REQUIRE_CHANGES)
        Truth.assertWithMessage("Trace file exists ${traceMonitor.traceType}")
            .that(reader.hasTraceFile(traceMonitor.traceType))
            .isTrue()

        val trace =
            reader.readBytes(traceMonitor.traceType)
                ?: error("Missing trace file ${traceMonitor.traceType}")
        Truth.assertWithMessage("Trace file has data").that(trace.size).isGreaterThan(0)
        assertTrace(trace)
    }

    private fun validateTrace(dump: DeviceTraceDump) {
        Truth.assertWithMessage("Could not obtain SF trace")
            .that(dump.layersTrace?.entries ?: emptyArray())
            .asList()
            .isNotEmpty()
        Truth.assertWithMessage("Could not obtain WM trace")
            .that(dump.wmTrace?.entries ?: emptyArray())
            .asList()
            .isNotEmpty()
    }

    @Test
    fun withTracing() {
        val trace = withTracing {
            device.pressHome()
            device.pressRecentApps()
        }

        this.validateTrace(trace)
    }

    @Test
    fun recordTraces() {
        val trace = recordTraces {
            device.pressHome()
            device.pressRecentApps()
        }

        val dump = DeviceDumpParser.fromTrace(trace.first, trace.second, clearCache = true)
        this.validateTrace(dump)
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
