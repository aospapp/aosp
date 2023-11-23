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

import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents a task fragment in the window manager hierarchy
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
class TaskFragment(
    override val activityType: Int,
    @JsName("displayId") val displayId: Int,
    @JsName("minWidth") val minWidth: Int,
    @JsName("minHeight") val minHeight: Int,
    windowContainer: WindowContainer
) : WindowContainer(windowContainer) {
    @JsName("tasks")
    val tasks: Array<Task>
        get() = this.children.reversed().filterIsInstance<Task>().toTypedArray()
    @JsName("taskFragments")
    val taskFragments: Array<TaskFragment>
        get() = this.children.reversed().filterIsInstance<TaskFragment>().toTypedArray()
    @JsName("activities")
    val activities: Array<Activity>
        get() = this.children.reversed().filterIsInstance<Activity>().toTypedArray()

    @JsName("getActivity")
    fun getActivity(predicate: (Activity) -> Boolean): Activity? {
        var activity: Activity? = activities.firstOrNull { predicate(it) }
        if (activity != null) {
            return activity
        }
        for (task in tasks) {
            activity = task.getActivity(predicate)
            if (activity != null) {
                return activity
            }
        }
        for (taskFragment in taskFragments) {
            activity = taskFragment.getActivity(predicate)
            if (activity != null) {
                return activity
            }
        }
        return null
    }

    override fun toString(): String {
        return "${this::class.simpleName}: {$token $title} bounds=$bounds"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskFragment) return false

        if (activityType != other.activityType) return false
        if (displayId != other.displayId) return false
        if (minWidth != other.minWidth) return false
        if (minHeight != other.minHeight) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + activityType
        result = 31 * result + displayId
        result = 31 * result + minWidth
        result = 31 * result + minHeight
        return result
    }
}
