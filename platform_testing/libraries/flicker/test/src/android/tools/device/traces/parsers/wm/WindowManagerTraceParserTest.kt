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

package android.tools.device.traces.parsers.wm

import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.Cache
import android.tools.device.traces.monitors.wm.WindowManagerTraceMonitor
import android.tools.readAsset
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test

/** Tests for [WindowManagerTraceParser] */
class WindowManagerTraceParserTest {
    @Before
    fun before() {
        Cache.clear()
    }

    @Test
    fun canParseAllEntriesFromStoredTrace() {
        val trace =
            WindowManagerTraceParser(legacyTrace = true)
                .parse(readAsset("wm_trace_openchrome.pb"), clearCache = false)
        val firstEntry = trace.entries[0]
        Truth.assertThat(firstEntry.timestamp.elapsedNanos).isEqualTo(9213763541297L)
        Truth.assertThat(firstEntry.windowStates.size).isEqualTo(10)
        Truth.assertThat(firstEntry.visibleWindows.size).isEqualTo(5)
        Truth.assertThat(trace.entries[trace.entries.size - 1].timestamp.elapsedNanos)
            .isEqualTo(9216093628925L)
    }

    @Test
    fun canParseAllEntriesFromNewTrace() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val data =
            WindowManagerTraceMonitor().withTracing {
                device.pressHome()
                device.pressRecentApps()
            }
        val trace = WindowManagerTraceParser().parse(data, clearCache = false)
        Truth.assertThat(trace.entries).asList().isNotEmpty()
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
