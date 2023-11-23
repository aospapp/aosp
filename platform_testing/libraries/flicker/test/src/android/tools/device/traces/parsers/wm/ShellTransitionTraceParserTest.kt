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

import android.app.Instrumentation
import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.Cache
import android.tools.device.apphelpers.BrowserAppHelper
import android.tools.device.traces.monitors.wm.ShellTransitionTraceMonitor
import android.tools.readAsset
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.tapl.LauncherInstrumentation
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test

/** Tests for [WindowManagerTraceParser] */
class ShellTransitionTraceParserTest {
    @Before
    fun before() {
        Cache.clear()
    }

    @Test
    fun canParseAllEntriesFromStoredTrace() {
        val trace =
            ShellTransitionTraceParser()
                .parse(readAsset("shell_transition_trace.winscope"), clearCache = false)
        val firstEntry = trace.entries.first()
        val lastEntry = trace.entries.last()
        Truth.assertThat(firstEntry.timestamp.elapsedNanos).isEqualTo(760760231809L)
        Truth.assertThat(lastEntry.timestamp.elapsedNanos).isEqualTo(2770678425968L)
    }

    @Test
    fun canParseAllEntriesFromNewTrace() {
        val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
        val tapl = LauncherInstrumentation()
        val data =
            ShellTransitionTraceMonitor().withTracing {
                BrowserAppHelper(instrumentation).open()
                tapl.goHome().switchToAllApps()
                tapl.goHome()
            }
        val trace = ShellTransitionTraceParser().parse(data, clearCache = false)
        Truth.assertThat(trace.entries).asList().isNotEmpty()
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
