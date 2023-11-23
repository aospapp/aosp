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

import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.CrossPlatform
import com.google.common.truth.Truth
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Contains [WindowManagerTraceEntryBuilder] tests. To run this test: `atest
 * FlickerLibTest:WindowManagerTraceEntryBuilderTest`
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WindowManagerTraceEntryBuilderTest {

    private val emptyRootContainer =
        RootWindowContainer(
            WindowContainer(
                title = "root",
                token = "",
                orientation = 0,
                layerId = 0,
                _isVisible = true,
                children = emptyArray(),
                configurationContainer = ConfigurationContainer(null, null, null),
                computedZ = 0
            )
        )

    @Test
    fun createsEntryWithCorrectClockTime() {
        val builder =
            WindowManagerTraceEntryBuilder(
                _elapsedTimestamp = "100",
                where = "",
                policy = null,
                focusedApp = "",
                focusedDisplayId = 0,
                focusedWindow = "",
                inputMethodWindowAppToken = "",
                isHomeRecentsComponent = false,
                isDisplayFrozen = false,
                pendingActivities = emptyArray(),
                root = emptyRootContainer,
                keyguardControllerState =
                    KeyguardControllerState.from(
                        isAodShowing = false,
                        isKeyguardShowing = false,
                        keyguardOccludedStates = mapOf()
                    ),
                realToElapsedTimeOffsetNs = "500"
            )
        val entry = builder.build()
        Truth.assertThat(entry.elapsedTimestamp).isEqualTo(100)
        Truth.assertThat(entry.clockTimestamp).isEqualTo(600)

        Truth.assertThat(entry.timestamp.elapsedNanos).isEqualTo(100)
        Truth.assertThat(entry.timestamp.systemUptimeNanos)
            .isEqualTo(CrossPlatform.timestamp.empty().systemUptimeNanos)
        Truth.assertThat(entry.timestamp.unixNanos).isEqualTo(600)
    }

    @Test
    fun supportsMissingRealToElapsedTimeOffsetNs() {
        val builder =
            WindowManagerTraceEntryBuilder(
                _elapsedTimestamp = "100",
                where = "",
                policy = null,
                focusedApp = "",
                focusedDisplayId = 0,
                focusedWindow = "",
                inputMethodWindowAppToken = "",
                isHomeRecentsComponent = false,
                isDisplayFrozen = false,
                pendingActivities = emptyArray(),
                root = emptyRootContainer,
                keyguardControllerState =
                    KeyguardControllerState.from(
                        isAodShowing = false,
                        isKeyguardShowing = false,
                        keyguardOccludedStates = mapOf()
                    )
            )
        val entry = builder.build()
        Truth.assertThat(entry.elapsedTimestamp).isEqualTo(100)
        Truth.assertThat(entry.clockTimestamp).isEqualTo(null)

        Truth.assertThat(entry.timestamp.elapsedNanos).isEqualTo(100)
        Truth.assertThat(entry.timestamp.systemUptimeNanos)
            .isEqualTo(CrossPlatform.timestamp.empty().systemUptimeNanos)
        Truth.assertThat(entry.timestamp.unixNanos)
            .isEqualTo(CrossPlatform.timestamp.empty().unixNanos)
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
