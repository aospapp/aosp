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

package android.tools.device.traces.parsers

import android.annotation.SuppressLint
import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.Cache
import android.tools.common.CrossPlatform
import android.tools.common.Rotation
import android.tools.common.datatypes.ActiveBuffer
import android.tools.common.datatypes.Color
import android.tools.common.datatypes.Matrix33
import android.tools.common.datatypes.Rect
import android.tools.common.datatypes.RectF
import android.tools.common.datatypes.Region
import android.tools.common.flicker.subject.wm.WindowManagerStateSubject
import android.tools.common.traces.DeviceStateDump
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.IComponentName
import android.tools.common.traces.surfaceflinger.HwcCompositionType
import android.tools.common.traces.surfaceflinger.Layer
import android.tools.common.traces.surfaceflinger.LayerTraceEntryBuilder
import android.tools.common.traces.surfaceflinger.Transform
import android.tools.common.traces.wm.WindowManagerState
import android.tools.common.traces.wm.WindowManagerTrace
import android.tools.getWmDumpReaderFromAsset
import android.tools.getWmTraceReaderFromAsset
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Contains [WindowManagerStateHelper] tests. To run this test: `atest
 * FlickerLibTest:WindowManagerTraceHelperTest`
 */
@SuppressLint("VisibleForTests")
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WindowManagerStateHelperTest {
    class TestWindowManagerStateHelper(
        _wmState: WindowManagerState,
        /** Predicate to supply a new UI information */
        deviceDumpSupplier: () -> DeviceStateDump,
        numRetries: Int = 5,
        retryIntervalMs: Long = 500L
    ) :
        WindowManagerStateHelper(
            InstrumentationRegistry.getInstrumentation(),
            clearCacheAfterParsing = false,
            deviceDumpSupplier,
            numRetries,
            retryIntervalMs
        ) {
        var wmState: WindowManagerState = _wmState
            private set

        override fun updateCurrState(value: DeviceStateDump) {
            wmState = value.wmState
        }
    }

    private val chromeComponent =
        ComponentNameMatcher.unflattenFromString(
            "com.android.chrome/org.chromium.chrome.browser" + ".firstrun.FirstRunActivity"
        )
    private val simpleAppComponentName =
        ComponentNameMatcher.unflattenFromString(
            "com.android.server.wm.flicker.testapp/.SimpleActivity"
        )

    @Before
    fun before() {
        Cache.clear()
    }

    private fun createImaginaryLayer(name: String, index: Int, id: Int, parentId: Int): Layer {
        val transform = Transform.from(0, Matrix33.EMPTY)
        val rect =
            RectF.from(
                left = index.toFloat(),
                top = index.toFloat(),
                right = index.toFloat() + 1,
                bottom = index.toFloat() + 1
            )
        return Layer.from(
            name,
            id,
            parentId,
            z = 0,
            visibleRegion = Region.from(rect.toRect()),
            activeBuffer = ActiveBuffer.from(1, 1, 1, 1),
            flags = 0,
            bounds = rect,
            color = Color.DEFAULT,
            isOpaque = true,
            shadowRadius = 0f,
            cornerRadius = 0f,
            type = "",
            screenBounds = rect,
            transform = transform,
            sourceBounds = rect,
            currFrame = 0,
            effectiveScalingMode = 0,
            bufferTransform = transform,
            hwcCompositionType = HwcCompositionType.INVALID,
            hwcCrop = RectF.EMPTY,
            hwcFrame = Rect.EMPTY,
            crop = rect.toRect(),
            backgroundBlurRadius = 0,
            isRelativeOf = false,
            zOrderRelativeOfId = -1,
            stackId = 0,
            requestedTransform = transform,
            requestedColor = Color.DEFAULT,
            cornerRadiusCrop = RectF.EMPTY,
            inputTransform = transform,
            inputRegion = Region.from(rect.toRect()),
            excludesCompositionState = false
        )
    }

    private fun createImaginaryVisibleLayers(names: List<IComponentName>): Array<Layer> {
        val root = createImaginaryLayer("root", -1, id = "root".hashCode(), parentId = -1)
        val layers = mutableListOf(root)
        names.forEachIndexed { index, name ->
            layers.add(
                createImaginaryLayer(
                    name.toLayerName(),
                    index,
                    id = name.hashCode(),
                    parentId = root.id
                )
            )
        }
        return layers.toTypedArray()
    }

    /**
     * Creates a device state dump provider based on the WM trace
     *
     * Alongside the SF trac,e this function creates an imaginary SF trace with visible Status and
     * NavBar, as well as all visible non-system windows (those with name containing /)
     */
    private fun WindowManagerTrace.asSupplier(startingTimestamp: Long = 0): () -> DeviceStateDump {
        val iterator =
            this.entries.dropWhile { it.timestamp.elapsedNanos < startingTimestamp }.iterator()
        return {
            if (iterator.hasNext()) {
                val wmState = iterator.next()
                val layerList: MutableList<IComponentName> =
                    mutableListOf(ComponentNameMatcher.STATUS_BAR, ComponentNameMatcher.NAV_BAR)
                if (wmState.isWindowSurfaceShown(ComponentNameMatcher.SPLASH_SCREEN)) {
                    layerList.add(ComponentNameMatcher.SPLASH_SCREEN)
                }
                if (wmState.isWindowSurfaceShown(ComponentNameMatcher.SNAPSHOT)) {
                    layerList.add(ComponentNameMatcher.SNAPSHOT)
                }
                layerList.addAll(
                    wmState.visibleWindows
                        .filter { it.name.contains("/") }
                        .map { ComponentNameMatcher.unflattenFromString(it.name) }
                )
                if (wmState.inputMethodWindowState?.isSurfaceShown == true) {
                    layerList.add(ComponentNameMatcher.IME)
                }
                val layerTraceEntry =
                    LayerTraceEntryBuilder()
                        .setElapsedTimestamp("0")
                        .setDisplays(emptyArray())
                        .setLayers(createImaginaryVisibleLayers(layerList))
                        .setVSyncId("-1")
                        .build()
                DeviceStateDump(wmState, layerTraceEntry)
            } else {
                error("Reached the end of the trace")
            }
        }
    }

    @Test
    fun canWaitForIme() {
        val reader = getWmTraceReaderFromAsset("wm_trace_ime.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        try {
            WindowManagerStateSubject(helper.wmState, reader)
                .isNonAppWindowVisible(ComponentNameMatcher.IME)
            error("IME state should not be available")
        } catch (e: AssertionError) {
            helper.StateSyncBuilder().withImeShown().waitFor()
            WindowManagerStateSubject(helper.wmState)
                .isNonAppWindowVisible(ComponentNameMatcher.IME)
        }
    }

    @Test
    fun canFailImeNotShown() {
        val reader = getWmTraceReaderFromAsset("wm_trace_ime.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        try {
            WindowManagerStateSubject(helper.wmState, reader)
                .isNonAppWindowVisible(ComponentNameMatcher.IME)
            error("IME state should not be available")
        } catch (e: AssertionError) {
            helper.StateSyncBuilder().withImeShown().waitFor()
            WindowManagerStateSubject(helper.wmState, reader)
                .isNonAppWindowVisible(ComponentNameMatcher.IME)
        }
    }

    @Test
    fun canWaitForWindow() {
        val reader = getWmTraceReaderFromAsset("wm_trace_open_app_cold.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        try {
            WindowManagerStateSubject(helper.wmState, reader)
                .containsAppWindow(simpleAppComponentName)
            error("Chrome window should not exist in the start of the trace")
        } catch (e: AssertionError) {
            helper.StateSyncBuilder().withWindowSurfaceAppeared(simpleAppComponentName).waitFor()
            WindowManagerStateSubject(helper.wmState, reader)
                .isAppWindowVisible(simpleAppComponentName)
        }
    }

    @Test
    fun canFailWindowNotShown() {
        val reader = getWmTraceReaderFromAsset("wm_trace_open_app_cold.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = 3,
                retryIntervalMs = 1
            )
        try {
            WindowManagerStateSubject(helper.wmState, reader)
                .containsAppWindow(simpleAppComponentName)
            error("SimpleActivity window should not exist in the start of the trace")
        } catch (e: AssertionError) {
            // nothing to do
        }

        try {
            helper.StateSyncBuilder().withWindowSurfaceAppeared(simpleAppComponentName).waitFor()
        } catch (e: IllegalArgumentException) {
            WindowManagerStateSubject(helper.wmState, reader).notContains(simpleAppComponentName)
        }
    }

    @Test
    fun canDetectHomeActivityVisibility() {
        val reader =
            getWmTraceReaderFromAsset("wm_trace_open_and_close_chrome.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        WindowManagerStateSubject(helper.wmState, reader).isHomeActivityVisible()
        helper.StateSyncBuilder().withWindowSurfaceAppeared(chromeComponent).waitFor()
        WindowManagerStateSubject(helper.wmState, reader).isHomeActivityInvisible()
        helper.StateSyncBuilder().withHomeActivityVisible().waitFor()
        WindowManagerStateSubject(helper.wmState, reader).isHomeActivityVisible()
    }

    @Test
    fun canWaitActivityRemoved() {
        val reader =
            getWmTraceReaderFromAsset("wm_trace_open_and_close_chrome.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        WindowManagerStateSubject(helper.wmState, reader)
            .isHomeActivityVisible()
            .notContains(chromeComponent)
        helper.StateSyncBuilder().withWindowSurfaceAppeared(chromeComponent).waitFor()
        WindowManagerStateSubject(helper.wmState, reader).isAppWindowVisible(chromeComponent)
        helper.StateSyncBuilder().withActivityRemoved(chromeComponent).waitFor()
        WindowManagerStateSubject(helper.wmState, reader)
            .notContains(chromeComponent)
            .isHomeActivityVisible()
    }

    @Test
    fun canWaitAppStateIdle() {
        val reader =
            getWmTraceReaderFromAsset("wm_trace_open_and_close_chrome.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val initialTimestamp = 69443918698679
        val supplier = trace.asSupplier(startingTimestamp = initialTimestamp)
        val initialEntry =
            trace.getEntryExactlyAt(CrossPlatform.timestamp.from(elapsedNanos = initialTimestamp))
        val helper =
            TestWindowManagerStateHelper(
                initialEntry,
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        try {
            WindowManagerStateSubject(helper.wmState, reader).isValid()
            error("Initial state in the trace should not be valid")
        } catch (e: AssertionError) {
            Truth.assertWithMessage("App transition never became idle")
                .that(helper.StateSyncBuilder().withAppTransitionIdle().waitFor())
                .isTrue()
            WindowManagerStateSubject(helper.wmState, reader).isValid()
        }
    }

    @Test
    fun canWaitForRotation() {
        val reader = getWmTraceReaderFromAsset("wm_trace_rotation.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        WindowManagerStateSubject(helper.wmState, reader).hasRotation(Rotation.ROTATION_0)
        helper.StateSyncBuilder().withRotation(Rotation.ROTATION_270).waitFor()
        WindowManagerStateSubject(helper.wmState, reader).hasRotation(Rotation.ROTATION_270)
        helper.StateSyncBuilder().withRotation(Rotation.ROTATION_0).waitFor()
        WindowManagerStateSubject(helper.wmState, reader).hasRotation(Rotation.ROTATION_0)
    }

    @Test
    fun canDetectResumedActivitiesInStacks() {
        val reader = getWmDumpReaderFromAsset("wm_trace_resumed_activities_in_stack.pb")
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val entry = trace.entries.first()
        Truth.assertWithMessage("Trace should have a resumed activity in stacks")
            .that(entry.resumedActivities)
            .asList()
            .hasSize(1)
    }

    @FlakyTest
    @Test
    fun canWaitForRecents() {
        val reader = getWmTraceReaderFromAsset("wm_trace_open_recents.pb", legacyTrace = true)
        val trace = reader.readWmTrace() ?: error("Unable to read WM trace")
        val supplier = trace.asSupplier()
        val helper =
            TestWindowManagerStateHelper(
                trace.entries.first(),
                supplier,
                numRetries = trace.entries.size,
                retryIntervalMs = 1
            )
        WindowManagerStateSubject(helper.wmState, reader).isRecentsActivityInvisible()
        helper.StateSyncBuilder().withRecentsActivityVisible().waitFor()
        WindowManagerStateSubject(helper.wmState, reader).isRecentsActivityVisible()
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
