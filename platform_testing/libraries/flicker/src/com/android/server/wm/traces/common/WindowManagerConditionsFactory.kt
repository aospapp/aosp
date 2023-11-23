/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.traces.common

import com.android.server.wm.traces.common.layers.Layer
import com.android.server.wm.traces.common.layers.LayerTraceEntry
import com.android.server.wm.traces.common.layers.Transform
import com.android.server.wm.traces.common.layers.Transform.Companion.isFlagSet
import com.android.server.wm.traces.common.service.PlatformConsts
import com.android.server.wm.traces.common.windowmanager.WindowManagerState
import com.android.server.wm.traces.common.windowmanager.windows.WindowState

object WindowManagerConditionsFactory {
    private val navBarWindowName = FlickerComponentName.NAV_BAR.toWindowName()
    private val navBarLayerName = FlickerComponentName.NAV_BAR.toLayerName()
    private val statusBarWindowName = FlickerComponentName.STATUS_BAR.toWindowName()
    private val statusBarLayerName = FlickerComponentName.STATUS_BAR.toLayerName()

    /**
     * Condition to check if the nav bar window is visible
     */
    fun isNavBarVisible(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        ConditionList(listOf(
            isNavBarWindowVisible(), isNavBarLayerVisible(), isNavBarLayerOpaque()))

    /**
     * Condition to check if the nav bar window is visible
     */
    fun isNavBarWindowVisible(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isNavBarWindowVisible") {
            it.wmState.isWindowVisible(navBarWindowName)
        }

    /**
     * Condition to check if the nav bar layer is visible
     */
    fun isNavBarLayerVisible(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        isLayerVisible(navBarLayerName)

    /**
     * Condition to check if the nav bar layer is opaque
     */
    fun isNavBarLayerOpaque(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isNavBarLayerOpaque") {
            it.layerState.getLayerWithBuffer(navBarLayerName)
                ?.color?.a ?: 0f == 1f
        }

    /**
     * Condition to check if the status bar window is visible
     */
    fun isStatusBarVisible(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        ConditionList(listOf(
            isStatusBarWindowVisible(), isStatusBarLayerVisible(), isStatusBarLayerOpaque()))

    /**
     * Condition to check if the nav bar window is visible
     */
    fun isStatusBarWindowVisible():
        Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isStatusBarWindowVisible") {
            it.wmState.isWindowVisible(statusBarWindowName)
        }

    /**
     * Condition to check if the nav bar layer is visible
     */
    fun isStatusBarLayerVisible(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        isLayerVisible(statusBarLayerName)

    /**
     * Condition to check if the nav bar layer is opaque
     */
    fun isStatusBarLayerOpaque(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isStatusBarLayerOpaque") {
            it.layerState.getLayerWithBuffer(statusBarLayerName)
                ?.color?.a ?: 0f == 1f
        }

    fun isHomeActivityVisible(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isHomeActivityVisible") {
            it.wmState.homeActivity?.isVisible == true
        }

    fun isAppTransitionIdle(
        displayId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isAppTransitionIdle[$displayId]") {
            it.wmState.getDisplay(displayId)
                ?.appTransitionState == WindowManagerState.APP_STATE_IDLE
        }

    fun containsActivity(
        component: FlickerComponentName
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("containsActivity[${component.toActivityName()}]") {
            it.wmState.containsActivity(component.toActivityName())
        }

    fun containsWindow(
        component: FlickerComponentName
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("containsWindow[${component.toWindowName()}]") {
            it.wmState.containsWindow(component.toWindowName())
        }

    fun isWindowSurfaceShown(
        windowName: String
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isWindowSurfaceShown[$windowName]") {
            it.wmState.isWindowSurfaceShown(windowName)
        }

    fun isWindowSurfaceShown(
        component: FlickerComponentName
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        isWindowSurfaceShown(component.toWindowName())

    fun isActivityVisible(
        component: FlickerComponentName
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isActivityVisible") {
            it.wmState.isActivityVisible(component.toActivityName())
        }

    fun isWMStateComplete(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isWMStateComplete") {
            it.wmState.isComplete()
        }

    fun hasRotation(
        expectedRotation: Int,
        displayId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> {
        val hasRotationCondition = Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>>(
            "hasRotation[$expectedRotation, display=$displayId]") {
            val currRotation = it.wmState.getRotation(displayId)
            currRotation == expectedRotation
        }
        return ConditionList(listOf(
            hasRotationCondition,
            isLayerVisible(FlickerComponentName.ROTATION).negate(),
            isLayerVisible(FlickerComponentName.BACK_SURFACE).negate(),
            hasLayersAnimating().negate()
        ))
    }

    fun isLayerVisible(
        layerName: String
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isLayerVisible[$layerName]") {
            it.layerState.isVisible(layerName)
        }

    fun isLayerVisible(
        layerId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isLayerVisible[$layerId]") {
            it.layerState.getLayerById(layerId)?.isVisible ?: false
        }

    fun isLayerColorAlphaOne(
        component: FlickerComponentName
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isLayerColorAlphaOne[${component.toLayerName()}]") {
            val layers = it.layerState.getVisibleLayersByName(component)
            layers.any { layer -> layer.color.a == 1.0f }
        }

    fun isLayerColorAlphaOne(
        layerId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isLayerColorAlphaOne[$layerId]") {
            val layer = it.layerState.getLayerById(layerId)
            layer?.color?.a == 1.0f
        }

    fun isLayerTransformFlagSet(
        component: FlickerComponentName,
        transform: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isLayerTransformFlagSet[" +
            "${component.toLayerName()},transform=$transform]") {
            val layers = it.layerState.getVisibleLayersByName(component)
            layers.any { layer -> isTransformFlagSet(layer, transform) }
        }

    fun isLayerTransformFlagSet(
        layerId: Int,
        transform: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isLayerTransformFlagSet[$layerId, $transform]") {
            val layer = it.layerState.getLayerById(layerId)
            layer?.transform?.type?.isFlagSet(transform) ?: false
        }

    fun isLayerTransformIdentity(
        layerId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        ConditionList(listOf(
            isLayerTransformFlagSet(layerId, Transform.SCALE_VAL).negate(),
            isLayerTransformFlagSet(layerId, Transform.TRANSLATE_VAL).negate(),
            isLayerTransformFlagSet(layerId, Transform.ROTATE_VAL).negate()
        ))

    private fun isTransformFlagSet(layer: Layer, transform: Int): Boolean =
        layer.transform.type?.isFlagSet(transform) ?: false

    fun LayerTraceEntry.getVisibleLayersByName(
        component: FlickerComponentName
    ): List<Layer> = visibleLayers.filter { it.name.contains(component.toLayerName()) }

    fun isLayerVisible(
        component: FlickerComponentName
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        isLayerVisible(component.toLayerName())

    fun hasLayersAnimating(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("hasLayersAnimating") {
            it.layerState.isAnimating()
        }

    fun isPipWindowLayerSizeMatch(
        layerId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isPipWindowLayerSizeMatch") {
            val pipWindow = it.wmState.pinnedWindows.firstOrNull { it.layerId == layerId }
                ?: error("Unable to find window with layerId $layerId")
            val windowHeight = pipWindow.frame.height.toFloat()
            val windowWidth = pipWindow.frame.width.toFloat()

            val pipLayer = it.layerState.getLayerById(layerId)
            val layerHeight = pipLayer?.sourceBounds?.height
                ?: error("Unable to find layer with id $layerId")
            val layerWidth = pipLayer.sourceBounds.width

            windowHeight == layerHeight && windowWidth == layerWidth
        }

    fun hasPipWindow(): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("hasPipWindow") {
            it.wmState.hasPipWindow()
        }

    fun isImeShown(
        displayId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        ConditionList(listOf(
            isImeOnDisplay(displayId),
            isLayerVisible(FlickerComponentName.IME),
            isImeSurfaceShown(),
            isWindowSurfaceShown(FlickerComponentName.IME.toWindowName())
        ))

    private fun isImeOnDisplay(
        displayId: Int
    ): Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isImeOnDisplay[$displayId]") {
            it.wmState.inputMethodWindowState?.displayId == displayId
        }

    private fun isImeSurfaceShown():
        Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("isImeSurfaceShown") {
            it.wmState.inputMethodWindowState?.isSurfaceShown == true
        }

    fun isAppLaunchEnded(taskId: Int):
        Condition<DeviceStateDump<WindowManagerState, LayerTraceEntry>> =
        Condition("containsVisibleAppLaunchWindow[$taskId]") { dump ->
            val windowStates = dump.wmState.getRootTask(taskId)?.activities?.flatMap {
                it.children.filterIsInstance<WindowState>()
            }
            windowStates != null && windowStates.none { window ->
                window.attributes.type == PlatformConsts.TYPE_APPLICATION_STARTING &&
                    window.isVisible
            }
        }
}