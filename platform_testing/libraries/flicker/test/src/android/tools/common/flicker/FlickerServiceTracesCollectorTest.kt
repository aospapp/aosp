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

package android.tools.common.flicker

import android.app.Instrumentation
import android.tools.CleanFlickerEnvironmentRule
import android.tools.TEST_SCENARIO
import android.tools.assertArchiveContainsFiles
import android.tools.device.apphelpers.BrowserAppHelper
import android.tools.device.flicker.FlickerServiceTracesCollector
import android.tools.device.flicker.isShellTransitionsEnabled
import android.tools.device.traces.parsers.WindowManagerStateHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth
import java.io.File
import org.junit.Assume
import org.junit.Before
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Contains [FlickerServiceTracesCollector] tests. To run this test: `atest
 * FlickerLibTest:FlickerServiceTracesCollectorTest`
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FlickerServiceTracesCollectorTest {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp: BrowserAppHelper = BrowserAppHelper(instrumentation)

    @Before
    fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }

    @Test
    fun canCollectTraces() {
        val wmHelper = WindowManagerStateHelper(instrumentation)
        val collector = FlickerServiceTracesCollector()
        collector.start(TEST_SCENARIO)
        testApp.launchViaIntent(wmHelper)
        testApp.exit(wmHelper)
        val reader = collector.stop()

        Truth.assertThat(reader.readWmTrace()?.entries ?: emptyArray()).isNotEmpty()
        Truth.assertThat(reader.readLayersTrace()?.entries ?: emptyArray()).isNotEmpty()
        Truth.assertThat(reader.readTransitionsTrace()?.entries ?: emptyArray()).isNotEmpty()
    }

    @Test
    fun reportsTraceFile() {
        val wmHelper = WindowManagerStateHelper(instrumentation)
        val collector = FlickerServiceTracesCollector()
        collector.start(TEST_SCENARIO)
        testApp.launchViaIntent(wmHelper)
        testApp.exit(wmHelper)
        val reader = collector.stop()
        val tracePath = reader.artifactPath

        require(tracePath.isNotEmpty()) { "Artifact path missing in result" }
        val traceFile = File(tracePath)
        Truth.assertThat(traceFile.exists()).isTrue()
    }

    @Test
    fun reportedTraceFileContainsAllTraces() {
        val wmHelper = WindowManagerStateHelper(instrumentation)
        val collector = FlickerServiceTracesCollector()
        collector.start(TEST_SCENARIO)
        testApp.launchViaIntent(wmHelper)
        testApp.exit(wmHelper)
        val reader = collector.stop()
        val tracePath = reader.artifactPath

        require(tracePath.isNotEmpty()) { "Artifact path missing in result" }
        val traceFile = File(tracePath)
        assertArchiveContainsFiles(traceFile, expectedTraces)
    }

    companion object {
        val expectedTraces =
            listOf(
                "wm_trace.winscope",
                "layers_trace.winscope",
                "transactions_trace.winscope",
                "wm_transition_trace.winscope",
                "shell_transition_trace.winscope",
                "eventlog.winscope"
            )

        @Rule @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
