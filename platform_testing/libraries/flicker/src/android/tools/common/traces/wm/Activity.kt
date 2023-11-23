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

import android.tools.common.traces.component.IComponentMatcher
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents an activity in the window manager hierarchy
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
class Activity(
    name: String,
    @JsName("state") val state: String,
    visible: Boolean,
    @JsName("frontOfTask") val frontOfTask: Boolean,
    @JsName("procId") val procId: Int,
    @JsName("isTranslucent") val isTranslucent: Boolean,
    windowContainer: WindowContainer
) : WindowContainer(windowContainer, name, visible) {
    /**
     * Checks if the activity contains a [WindowState] matching [componentMatcher]
     *
     * @param componentMatcher Components to search
     */
    fun getWindows(componentMatcher: IComponentMatcher): Array<WindowState> = getWindows {
        componentMatcher.windowMatchesAnyOf(it)
    }

    /**
     * Checks if the activity contains a [WindowState] matching [componentMatcher]
     *
     * @param componentMatcher Components to search
     */
    @JsName("hasWindow")
    fun hasWindow(componentMatcher: IComponentMatcher): Boolean =
        getWindows(componentMatcher).isNotEmpty()

    @JsName("hasWindowState")
    internal fun hasWindowState(windowState: WindowState): Boolean =
        getWindows { windowState == it }.isNotEmpty()

    private fun getWindows(predicate: (WindowState) -> Boolean) =
        collectDescendants<WindowState> { predicate(it) }

    override fun toString(): String {
        return "${this::class.simpleName}: {$token $title} state=$state visible=$isVisible"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Activity) return false

        if (state != other.state) return false
        if (frontOfTask != other.frontOfTask) return false
        if (procId != other.procId) return false
        if (isTranslucent != other.isTranslucent) return false
        if (orientation != other.orientation) return false
        if (title != other.title) return false
        if (token != other.token) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + frontOfTask.hashCode()
        result = 31 * result + procId
        result = 31 * result + isTranslucent.hashCode()
        return result
    }
}
