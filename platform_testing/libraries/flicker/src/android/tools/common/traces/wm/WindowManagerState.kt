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

import android.tools.common.CrossPlatform
import android.tools.common.ITraceEntry
import android.tools.common.PlatformConsts
import android.tools.common.Rotation
import android.tools.common.traces.component.IComponentMatcher
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents a single WindowManager trace entry.
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 *
 * The timestamp constructor must be a string due to lack of Kotlin/KotlinJS Long compatibility
 */
@JsExport
class WindowManagerState(
    @JsName("elapsedTimestamp") val elapsedTimestamp: Long,
    @JsName("clockTimestamp") val clockTimestamp: Long?,
    @JsName("where") val where: String,
    val policy: WindowManagerPolicy?,
    val focusedApp: String,
    val focusedDisplayId: Int,
    private val _focusedWindow: String,
    val inputMethodWindowAppToken: String,
    val isHomeRecentsComponent: Boolean,
    val isDisplayFrozen: Boolean,
    private val _pendingActivities: Array<String>,
    val root: RootWindowContainer,
    val keyguardControllerState: KeyguardControllerState
) : ITraceEntry {
    override val timestamp =
        CrossPlatform.timestamp.from(elapsedNanos = elapsedTimestamp, unixNanos = clockTimestamp)
    @JsName("isVisible") val isVisible: Boolean = true
    @JsName("stableId")
    val stableId: String
        get() = this::class.simpleName ?: error("Unable to determine class")
    val isTablet: Boolean
        get() = displays.any { it.isTablet }

    @JsName("windowContainers")
    val windowContainers: Array<WindowContainer>
        get() = root.collectDescendants()

    @JsName("children")
    val children: Array<WindowContainer>
        get() = root.children.reversedArray()

    /** Displays in z-order with the top most at the front of the list, starting with primary. */
    @JsName("displays")
    val displays: Array<DisplayContent>
        get() = windowContainers.filterIsInstance<DisplayContent>().toTypedArray()

    /**
     * Root tasks in z-order with the top most at the front of the list, starting with primary
     * display.
     */
    val rootTasks: Array<Task>
        get() = displays.flatMap { it.rootTasks.toList() }.toTypedArray()

    /** TaskFragments in z-order with the top most at the front of the list. */
    val taskFragments: Array<TaskFragment>
        get() = windowContainers.filterIsInstance<TaskFragment>().toTypedArray()

    /** Windows in z-order with the top most at the front of the list. */
    @JsName("windowStates")
    val windowStates: Array<WindowState>
        get() = windowContainers.filterIsInstance<WindowState>().toTypedArray()

    @Deprecated("Please use windowStates instead", replaceWith = ReplaceWith("windowStates"))
    val windows: Array<WindowState>
        get() = windowStates

    val appWindows: Array<WindowState>
        get() = windowStates.filter { it.isAppWindow }.toTypedArray()
    val nonAppWindows: Array<WindowState>
        get() = windowStates.filterNot { it.isAppWindow }.toTypedArray()
    val aboveAppWindows: Array<WindowState>
        get() = windowStates.takeWhile { !appWindows.contains(it) }.toTypedArray()
    val belowAppWindows: Array<WindowState>
        get() =
            windowStates.dropWhile { !appWindows.contains(it) }.drop(appWindows.size).toTypedArray()
    @JsName("visibleWindows")
    val visibleWindows: Array<WindowState>
        get() =
            windowStates
                .filter {
                    val activities = getActivitiesForWindowState(it)
                    val windowIsVisible = it.isVisible
                    val activityIsVisible = activities.any { activity -> activity.isVisible }

                    // for invisible checks it suffices if activity or window is invisible
                    windowIsVisible && (activityIsVisible || activities.isEmpty())
                }
                .toTypedArray()
    val visibleAppWindows: Array<WindowState>
        get() = visibleWindows.filter { it.isAppWindow }.toTypedArray()
    val topVisibleAppWindow: WindowState?
        get() = visibleAppWindows.firstOrNull()
    val pinnedWindows: Array<WindowState>
        get() =
            visibleWindows
                .filter { it.windowingMode == PlatformConsts.WINDOWING_MODE_PINNED }
                .toTypedArray()
    val pendingActivities: Array<Activity>
        get() = _pendingActivities.mapNotNull { getActivityByName(it) }.toTypedArray()
    @JsName("focusedWindow")
    val focusedWindow: WindowState?
        get() = visibleWindows.firstOrNull { it.name == _focusedWindow }

    val isKeyguardShowing: Boolean
        get() = keyguardControllerState.isKeyguardShowing
    val isAodShowing: Boolean
        get() = keyguardControllerState.isAodShowing
    /**
     * Checks if the device state supports rotation, i.e., if the rotation sensor is enabled (e.g.,
     * launcher) and if the rotation not fixed
     */
    val canRotate: Boolean
        get() = policy?.isFixedOrientation != true && policy?.isOrientationNoSensor != true
    val focusedDisplay: DisplayContent?
        get() = getDisplay(focusedDisplayId)
    val focusedStackId: Int
        get() = focusedDisplay?.focusedRootTaskId ?: -1
    @JsName("focusedActivity")
    val focusedActivity: Activity?
        get() {
            val focusedDisplay = focusedDisplay
            val focusedWindow = focusedWindow
            return when {
                focusedDisplay != null && focusedDisplay.resumedActivity.isNotEmpty() ->
                    getActivityByName(focusedDisplay.resumedActivity)
                focusedWindow != null ->
                    getActivitiesForWindowState(focusedWindow, focusedDisplayId).firstOrNull()
                else -> null
            }
        }
    val resumedActivities: Array<Activity>
        get() =
            rootTasks
                .flatMap { it.resumedActivities.toList() }
                .mapNotNull { getActivityByName(it) }
                .toTypedArray()
    val resumedActivitiesCount: Int
        get() = resumedActivities.size
    val stackCount: Int
        get() = rootTasks.size
    val homeTask: Task?
        get() = getStackByActivityType(PlatformConsts.ACTIVITY_TYPE_HOME)?.topTask
    val recentsTask: Task?
        get() = getStackByActivityType(PlatformConsts.ACTIVITY_TYPE_RECENTS)?.topTask
    val homeActivity: Activity?
        get() = homeTask?.activities?.lastOrNull()
    val isHomeActivityVisible: Boolean
        get() {
            val activity = homeActivity
            return activity != null && activity.isVisible
        }
    val recentsActivity: Activity?
        get() = recentsTask?.activities?.lastOrNull()
    val isRecentsActivityVisible: Boolean
        get() {
            val activity = recentsActivity
            return activity != null && activity.isVisible
        }
    val frontWindow: WindowState?
        get() = windowStates.firstOrNull()
    val inputMethodWindowState: WindowState?
        get() = getWindowStateForAppToken(inputMethodWindowAppToken)

    fun getDefaultDisplay(): DisplayContent? =
        displays.firstOrNull { it.id == PlatformConsts.DEFAULT_DISPLAY }

    fun getDisplay(displayId: Int): DisplayContent? = displays.firstOrNull { it.id == displayId }

    fun countStacks(windowingMode: Int, activityType: Int): Int {
        var count = 0
        for (stack in rootTasks) {
            if (
                activityType != PlatformConsts.ACTIVITY_TYPE_UNDEFINED &&
                    activityType != stack.activityType
            ) {
                continue
            }
            if (
                windowingMode != PlatformConsts.WINDOWING_MODE_UNDEFINED &&
                    windowingMode != stack.windowingMode
            ) {
                continue
            }
            ++count
        }
        return count
    }

    fun getRootTask(taskId: Int): Task? = rootTasks.firstOrNull { it.rootTaskId == taskId }

    fun getRotation(displayId: Int): Rotation =
        getDisplay(displayId)?.rotation ?: error("Default display not found")

    fun getOrientation(displayId: Int): Int =
        getDisplay(displayId)?.lastOrientation ?: error("Default display not found")

    fun getStackByActivityType(activityType: Int): Task? =
        rootTasks.firstOrNull { it.activityType == activityType }

    fun getStandardStackByWindowingMode(windowingMode: Int): Task? =
        rootTasks.firstOrNull {
            it.activityType == PlatformConsts.ACTIVITY_TYPE_STANDARD &&
                it.windowingMode == windowingMode
        }

    fun getActivitiesForWindowState(
        windowState: WindowState,
        displayId: Int = PlatformConsts.DEFAULT_DISPLAY
    ): Array<Activity> {
        return displays
            .firstOrNull { it.id == displayId }
            ?.rootTasks
            ?.mapNotNull { stack ->
                stack.getActivity { activity -> activity.hasWindowState(windowState) }
            }
            ?.toTypedArray()
            ?: emptyArray()
    }

    /**
     * Get the all activities on display with id [displayId], containing a matching
     * [componentMatcher]
     *
     * @param componentMatcher Components to search
     * @param displayId display where to search the activity
     */
    fun getActivitiesForWindow(
        componentMatcher: IComponentMatcher,
        displayId: Int = PlatformConsts.DEFAULT_DISPLAY
    ): Array<Activity> {
        return displays
            .firstOrNull { it.id == displayId }
            ?.rootTasks
            ?.mapNotNull { stack ->
                stack.getActivity { activity -> activity.hasWindow(componentMatcher) }
            }
            ?.toTypedArray()
            ?: emptyArray()
    }

    /**
     * @param componentMatcher Components to search
     * @return if any activity matches [componentMatcher]
     */
    fun containsActivity(componentMatcher: IComponentMatcher): Boolean =
        rootTasks.any { it.containsActivity(componentMatcher) }

    /**
     * @param componentMatcher Components to search
     * @return the first [Activity] matching [componentMatcher], or null otherwise
     */
    fun getActivity(componentMatcher: IComponentMatcher): Activity? =
        rootTasks.firstNotNullOfOrNull { it.getActivity(componentMatcher) }

    private fun getActivityByName(activityName: String): Activity? =
        rootTasks.firstNotNullOfOrNull { task ->
            task.getActivity { activity -> activity.title.contains(activityName) }
        }

    /**
     * @param componentMatcher Components to search
     * @return if any activity matching [componentMatcher] is visible
     */
    fun isActivityVisible(componentMatcher: IComponentMatcher): Boolean =
        getActivity(componentMatcher)?.isVisible ?: false

    /**
     * @param componentMatcher Components to search
     * @param activityState expected activity state
     * @return if any activity matching [componentMatcher] has state of [activityState]
     */
    fun hasActivityState(componentMatcher: IComponentMatcher, activityState: String): Boolean =
        rootTasks.any { it.getActivity(componentMatcher)?.state == activityState }

    /**
     * @param componentMatcher Components to search
     * @return if any pending activities match [componentMatcher]
     */
    fun pendingActivityContain(componentMatcher: IComponentMatcher): Boolean =
        componentMatcher.activityMatchesAnyOf(pendingActivities)

    /**
     * @param componentMatcher Components to search
     * @return the visible [WindowState]s matching [componentMatcher]
     */
    fun getMatchingVisibleWindowState(componentMatcher: IComponentMatcher): Array<WindowState> {
        return windowStates
            .filter { it.isSurfaceShown && componentMatcher.windowMatchesAnyOf(it) }
            .toTypedArray()
    }

    /** @return the [WindowState] for the nav bar in the display with id [displayId] */
    fun getNavBarWindow(displayId: Int): WindowState? {
        val navWindow = windowStates.filter { it.isValidNavBarType && it.displayId == displayId }

        // We may need some time to wait for nav bar showing.
        // It's Ok to get 0 nav bar here.
        if (navWindow.size > 1) {
            throw IllegalStateException("There should be at most one navigation bar on a display")
        }
        return navWindow.firstOrNull()
    }

    private fun getWindowStateForAppToken(appToken: String): WindowState? =
        windowStates.firstOrNull { it.token == appToken }

    /**
     * Checks if there exists a [WindowState] matching [componentMatcher]
     *
     * @param componentMatcher Components to search
     */
    fun containsWindow(componentMatcher: IComponentMatcher): Boolean =
        componentMatcher.windowMatchesAnyOf(windowStates.asList())

    /**
     * Check if at least one [WindowState] matching [componentMatcher] is visible
     *
     * @param componentMatcher Components to search
     */
    fun isWindowSurfaceShown(componentMatcher: IComponentMatcher): Boolean =
        getMatchingVisibleWindowState(componentMatcher).isNotEmpty()

    /** Checks if the state has any window in PIP mode */
    fun hasPipWindow(): Boolean = pinnedWindows.isNotEmpty()

    /**
     * Checks that a [WindowState] matching [componentMatcher] is in PIP mode
     *
     * @param componentMatcher Components to search
     */
    fun isInPipMode(componentMatcher: IComponentMatcher): Boolean =
        componentMatcher.windowMatchesAnyOf(pinnedWindows.asList())

    fun getZOrder(w: WindowState): Int = windowStates.size - windowStates.indexOf(w)

    fun defaultMinimalTaskSize(displayId: Int): Int =
        dpToPx(PlatformConsts.DEFAULT_RESIZABLE_TASK_SIZE_DP.toFloat(), getDisplay(displayId)!!.dpi)

    fun defaultMinimalDisplaySizeForSplitScreen(displayId: Int): Int {
        return dpToPx(
            PlatformConsts.DEFAULT_MINIMAL_SPLIT_SCREEN_DISPLAY_SIZE_DP.toFloat(),
            getDisplay(displayId)!!.dpi
        )
    }

    @JsName("getIsIncompleteReason")
    fun getIsIncompleteReason(): String {
        return buildString {
            if (rootTasks.isEmpty()) {
                append("No stacks found...")
            }
            if (focusedStackId == -1) {
                append("No focused stack found...")
            }
            if (focusedActivity == null) {
                append("No focused activity found...")
            }
            if (resumedActivities.isEmpty()) {
                append("No resumed activities found...")
            }
            if (windowStates.isEmpty()) {
                append("No Windows found...")
            }
            if (focusedWindow == null) {
                append("No Focused Window...")
            }
            if (focusedApp.isEmpty()) {
                append("No Focused App...")
            }
            if (keyguardControllerState.isKeyguardShowing) {
                append("Keyguard showing...")
            }
        }
    }

    @JsName("isComplete") fun isComplete(): Boolean = !isIncomplete()
    @JsName("isIncomplete")
    fun isIncomplete(): Boolean {
        return rootTasks.isEmpty() ||
            focusedStackId == -1 ||
            windowStates.isEmpty() ||
            // overview screen has no focused window
            ((focusedApp.isEmpty() || focusedWindow == null) && homeActivity == null) ||
            (focusedActivity == null || resumedActivities.isEmpty()) &&
                !keyguardControllerState.isKeyguardShowing
    }

    fun asTrace(): WindowManagerTrace = WindowManagerTrace(arrayOf(this))

    override fun toString(): String {
        return timestamp.toString()
    }

    companion object {
        fun dpToPx(dp: Float, densityDpi: Int): Int {
            return (dp * densityDpi / PlatformConsts.DENSITY_DEFAULT + 0.5f).toInt()
        }
    }
    override fun equals(other: Any?): Boolean {
        return other is WindowManagerState && other.timestamp == this.timestamp
    }

    override fun hashCode(): Int {
        var result = where.hashCode()
        result = 31 * result + (policy?.hashCode() ?: 0)
        result = 31 * result + focusedApp.hashCode()
        result = 31 * result + focusedDisplayId
        result = 31 * result + focusedWindow.hashCode()
        result = 31 * result + inputMethodWindowAppToken.hashCode()
        result = 31 * result + isHomeRecentsComponent.hashCode()
        result = 31 * result + isDisplayFrozen.hashCode()
        result = 31 * result + pendingActivities.contentHashCode()
        result = 31 * result + root.hashCode()
        result = 31 * result + keyguardControllerState.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isVisible.hashCode()
        return result
    }
}
