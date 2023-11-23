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

package android.tools.common.flicker.subject.surfaceflinger

import android.tools.CleanFlickerEnvironmentRule
import android.tools.TestComponents
import android.tools.assertFail
import android.tools.assertThatErrorContainsDebugInfo
import android.tools.assertThrows
import android.tools.common.Cache
import android.tools.common.datatypes.Region
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.io.IReader
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.getLayerTraceReaderFromAsset
import androidx.test.filters.FlakyTest
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Contains [LayersTraceSubject] tests. To run this test: `atest
 * FlickerLibTest:LayersTraceSubjectTest`
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LayersTraceSubjectTest {
    @Before
    fun before() {
        Cache.clear()
    }

    @Test
    fun exceptionContainsDebugInfo() {
        val reader =
            getLayerTraceReaderFromAsset("layers_trace_launch_split_screen.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val error = assertThrows<AssertionError> { LayersTraceSubject(trace, reader).isEmpty() }
        assertThatErrorContainsDebugInfo(error)
    }

    @Test
    fun testCanDetectEmptyRegionFromLayerTrace() {
        val reader = getLayerTraceReaderFromAsset("layers_trace_emptyregion.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        assertFail("SkRegion((0,0,1440,1440)) should cover at least SkRegion((0,0,1440,2880))") {
            LayersTraceSubject(trace, reader)
                .visibleRegion()
                .coversAtLeast(DISPLAY_REGION)
                .forAllEntries()
            error("Assertion should not have passed")
        }
    }

    @Test
    fun testCanInspectBeginning() {
        val reader =
            getLayerTraceReaderFromAsset("layers_trace_launch_split_screen.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        LayersTraceSubject(trace, reader)
            .first()
            .isVisible(ComponentNameMatcher.NAV_BAR)
            .notContains(TestComponents.DOCKER_STACK_DIVIDER)
            .isVisible(TestComponents.LAUNCHER)
    }

    @Test
    fun testCanInspectEnd() {
        val reader =
            getLayerTraceReaderFromAsset("layers_trace_launch_split_screen.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        LayersTraceSubject(trace, reader)
            .last()
            .isVisible(ComponentNameMatcher.NAV_BAR)
            .isVisible(TestComponents.DOCKER_STACK_DIVIDER)
    }

    @Test
    fun testAssertionsOnRange() {
        val reader =
            getLayerTraceReaderFromAsset("layers_trace_launch_split_screen.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")

        LayersTraceSubject(trace, reader)
            .isVisible(ComponentNameMatcher.NAV_BAR)
            .isInvisible(TestComponents.DOCKER_STACK_DIVIDER)
            .forSystemUpTimeRange(90480846872160L, 90480994138424L)

        LayersTraceSubject(trace, reader)
            .isVisible(ComponentNameMatcher.NAV_BAR)
            .isVisible(TestComponents.DOCKER_STACK_DIVIDER)
            .forSystemUpTimeRange(90491795074136L, 90493757372977L)
    }

    @Test
    fun testCanDetectChangingAssertions() {
        val reader =
            getLayerTraceReaderFromAsset("layers_trace_launch_split_screen.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        LayersTraceSubject(trace, reader)
            .isVisible(ComponentNameMatcher.NAV_BAR)
            .notContains(TestComponents.DOCKER_STACK_DIVIDER)
            .then()
            .isVisible(ComponentNameMatcher.NAV_BAR)
            .isInvisible(TestComponents.DOCKER_STACK_DIVIDER)
            .then()
            .isVisible(ComponentNameMatcher.NAV_BAR)
            .isVisible(TestComponents.DOCKER_STACK_DIVIDER)
            .forAllEntries()
    }

    @FlakyTest
    @Test
    fun testCanDetectIncorrectVisibilityFromLayerTrace() {
        val reader =
            getLayerTraceReaderFromAsset(
                "layers_trace_invalid_layer_visibility.pb",
                legacyTrace = true
            )
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val error =
            assertThrows<AssertionError> {
                LayersTraceSubject(trace, reader)
                    .isVisible(TestComponents.SIMPLE_APP)
                    .then()
                    .isInvisible(TestComponents.SIMPLE_APP)
                    .forAllEntries()
            }

        Truth.assertThat(error)
            .hasMessageThat()
            .contains("layers_trace_invalid_layer_visibility.pb")
        Truth.assertThat(error).hasMessageThat().contains("2d22h13m14s303ms")
        Truth.assertThat(error).hasMessageThat().contains("!isVisible")
        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "com.android.server.wm.flicker.testapp/" +
                    "com.android.server.wm.flicker.testapp.SimpleActivity#0 is visible"
            )
    }

    @Test
    fun testCanDetectInvalidVisibleLayerForMoreThanOneConsecutiveEntry() {
        val reader =
            getLayerTraceReaderFromAsset(
                "layers_trace_invalid_visible_layers.pb",
                legacyTrace = true
            )
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val error =
            assertThrows<AssertionError> {
                LayersTraceSubject(trace, reader)
                    .visibleLayersShownMoreThanOneConsecutiveEntry()
                    .forAllEntries()
                error("Assertion should not have passed")
            }

        Truth.assertThat(error).hasMessageThat().contains("2d18h35m56s397ms")
        Truth.assertThat(error).hasMessageThat().contains("StatusBar#0")
        Truth.assertThat(error).hasMessageThat().contains("is not visible for 2 entries")
    }

    private fun testCanDetectVisibleLayersMoreThanOneConsecutiveEntry(reader: IReader) {
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        LayersTraceSubject(trace, reader)
            .visibleLayersShownMoreThanOneConsecutiveEntry()
            .forAllEntries()
    }

    @Test
    fun testCanDetectVisibleLayersMoreThanOneConsecutiveEntry() {
        testCanDetectVisibleLayersMoreThanOneConsecutiveEntry(
            getLayerTraceReaderFromAsset("layers_trace_snapshot_visible.pb", legacyTrace = true)
        )
    }

    @Test
    fun testCanIgnoreLayerEqualNameInVisibleLayersMoreThanOneConsecutiveEntry() {
        val reader =
            getLayerTraceReaderFromAsset(
                "layers_trace_invalid_visible_layers.pb",
                legacyTrace = true
            )
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        LayersTraceSubject(trace, reader)
            .visibleLayersShownMoreThanOneConsecutiveEntry(listOf(ComponentNameMatcher.STATUS_BAR))
            .forAllEntries()
    }

    @Test
    fun testCanIgnoreLayerShorterNameInVisibleLayersMoreThanOneConsecutiveEntry() {
        val reader =
            getLayerTraceReaderFromAsset("one_visible_layer_launcher_trace.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val launcherComponent =
            ComponentNameMatcher(
                "com.google.android.apps.nexuslauncher",
                "com.google.android.apps.nexuslauncher.NexusLauncherActivity#1"
            )
        LayersTraceSubject(trace, reader)
            .visibleLayersShownMoreThanOneConsecutiveEntry(listOf(launcherComponent))
            .forAllEntries()
    }

    private fun detectRootLayer(fileName: String) {
        val reader = getLayerTraceReaderFromAsset(fileName, legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        for (entry in trace.entries) {
            val rootLayers = entry.children
            Truth.assertWithMessage("Does not have any root layer")
                .that(rootLayers.size)
                .isGreaterThan(0)
            val firstParentId = rootLayers.first().parentId
            Truth.assertWithMessage("Has multiple root layers")
                .that(rootLayers.all { it.parentId == firstParentId })
                .isTrue()
        }
    }

    @Test
    fun testCanDetectRootLayer() {
        detectRootLayer("layers_trace_root.pb")
    }

    @Test
    fun testCanDetectRootLayerAOSP() {
        detectRootLayer("layers_trace_root_aosp.pb")
    }

    @Test
    fun canTestLayerOccludedBySplashScreenLayerIsNotVisible() {
        val reader = getLayerTraceReaderFromAsset("layers_trace_occluded.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val entry =
            LayersTraceSubject(trace, reader)
                .getEntryBySystemUpTime(1700382131522L, byElapsedTimestamp = true)
        entry.isInvisible(TestComponents.SIMPLE_APP)
        entry.isVisible(ComponentNameMatcher.SPLASH_SCREEN)
    }

    @Test
    fun testCanDetectLayerExpanding() {
        val reader = getLayerTraceReaderFromAsset("layers_trace_openchrome.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val animation =
            LayersTraceSubject(trace, reader).layers("animation-leash of app_transition#0")
        // Obtain the area of each layer and checks if the next area is
        // greater or equal to the previous one
        val areas =
            animation.map {
                val region = it.layer.visibleRegion ?: Region()
                val area = region.width * region.height
                area
            }
        val expanding = areas.zipWithNext { currentArea, nextArea -> nextArea >= currentArea }

        Truth.assertWithMessage("Animation leash should be expanding")
            .that(expanding.all { it })
            .isTrue()
    }

    @Test
    fun checkVisibleRegionAppMinusPipLayer() {
        val reader = getLayerTraceReaderFromAsset("layers_trace_pip_wmshell.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val subject = LayersTraceSubject(trace, reader).last()

        try {
            subject.visibleRegion(TestComponents.FIXED_APP).coversExactly(DISPLAY_REGION_ROTATED)
            error(
                "Layer is partially covered by a Pip layer and should not cover the device screen"
            )
        } catch (e: AssertionError) {
            val pipRegion = subject.visibleRegion(TestComponents.PIP_APP).region
            val expectedWithoutPip = DISPLAY_REGION_ROTATED.minus(pipRegion)
            subject.visibleRegion(TestComponents.FIXED_APP).coversExactly(expectedWithoutPip)
        }
    }

    @Test
    fun checkVisibleRegionAppPlusPipLayer() {
        val reader = getLayerTraceReaderFromAsset("layers_trace_pip_wmshell.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        val subject = LayersTraceSubject(trace, reader).last()
        val pipRegion = subject.visibleRegion(TestComponents.PIP_APP).region
        subject
            .visibleRegion(TestComponents.FIXED_APP)
            .plus(pipRegion)
            .coversExactly(DISPLAY_REGION_ROTATED)
    }

    @Test
    fun checkCanDetectSplashScreen() {
        val reader =
            getLayerTraceReaderFromAsset("layers_trace_splashscreen.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")
        LayersTraceSubject(trace, reader)
            .isVisible(TestComponents.LAUNCHER)
            .then()
            .isSplashScreenVisibleFor(TestComponents.SIMPLE_APP, isOptional = false)
            .then()
            .isVisible(TestComponents.SIMPLE_APP)
            .forAllEntries()

        assertFail("SimpleActivity# should be visible") {
            LayersTraceSubject(trace, reader)
                .isVisible(TestComponents.LAUNCHER)
                .then()
                .isVisible(TestComponents.SIMPLE_APP)
                .forAllEntries()
        }
    }

    @Test
    fun checkCanDetectMissingSplashScreen() {
        val reader =
            getLayerTraceReaderFromAsset("layers_trace_splashscreen.pb", legacyTrace = true)
        val trace = reader.readLayersTrace() ?: error("Unable to read layers trace")

        // No splashscreen because no matching activity record
        assertFail("SimpleActivity# should exist") {
            LayersTraceSubject(trace, reader)
                .first()
                .isSplashScreenVisibleFor(TestComponents.SIMPLE_APP)
        }
    }

    companion object {
        private val DISPLAY_REGION = Region.from(0, 0, 1440, 2880)
        private val DISPLAY_REGION_ROTATED = Region.from(0, 0, 2160, 1080)

        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
