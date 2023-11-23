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

import android.tools.common.datatypes.Rect
import android.tools.common.withCache
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents the configuration of a WM window
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
open class WindowConfiguration(
    val appBounds: Rect = Rect.EMPTY,
    val bounds: Rect = Rect.EMPTY,
    val maxBounds: Rect = Rect.EMPTY,
    val windowingMode: Int = 0,
    val activityType: Int = 0
) {
    @JsName("isEmpty")
    val isEmpty: Boolean
        get() =
            appBounds.isEmpty &&
                bounds.isEmpty &&
                maxBounds.isEmpty &&
                windowingMode == 0 &&
                activityType == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WindowConfiguration) return false

        if (windowingMode != other.windowingMode) return false
        if (activityType != other.activityType) return false
        if (appBounds != other.appBounds) return false
        if (bounds != other.bounds) return false
        if (maxBounds != other.maxBounds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = windowingMode
        result = 31 * result + activityType
        result = 31 * result + appBounds.hashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + maxBounds.hashCode()
        return result
    }

    companion object {
        @JsName("EMPTY")
        val EMPTY: WindowConfiguration
            get() = withCache { WindowConfiguration() }
        @JsName("from")
        fun from(
            appBounds: Rect?,
            bounds: Rect?,
            maxBounds: Rect?,
            windowingMode: Int,
            activityType: Int
        ): WindowConfiguration = withCache {
            WindowConfiguration(
                appBounds ?: Rect.EMPTY,
                bounds ?: Rect.EMPTY,
                maxBounds ?: Rect.EMPTY,
                windowingMode,
                activityType
            )
        }
    }
}
