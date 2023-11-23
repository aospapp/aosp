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

import android.tools.common.withCache
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents the configuration of a WM container
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
class Configuration
private constructor(
    @JsName("windowConfiguration") val windowConfiguration: WindowConfiguration? = null,
    @JsName("densityDpi") val densityDpi: Int = 0,
    @JsName("orientation") val orientation: Int = 0,
    @JsName("screenHeightDp") val screenHeightDp: Int = 0,
    @JsName("screenWidthDp") val screenWidthDp: Int = 0,
    @JsName("smallestScreenWidthDp") val smallestScreenWidthDp: Int = 0,
    @JsName("screenLayout") val screenLayout: Int = 0,
    @JsName("uiMode") val uiMode: Int = 0
) {
    @JsName("isEmpty")
    val isEmpty: Boolean
        get() =
            (windowConfiguration == null) &&
                densityDpi == 0 &&
                orientation == 0 &&
                screenHeightDp == 0 &&
                screenWidthDp == 0 &&
                smallestScreenWidthDp == 0 &&
                screenLayout == 0 &&
                uiMode == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Configuration) return false

        if (windowConfiguration != other.windowConfiguration) return false
        if (densityDpi != other.densityDpi) return false
        if (orientation != other.orientation) return false
        if (screenHeightDp != other.screenHeightDp) return false
        if (screenWidthDp != other.screenWidthDp) return false
        if (smallestScreenWidthDp != other.smallestScreenWidthDp) return false
        if (screenLayout != other.screenLayout) return false
        if (uiMode != other.uiMode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = windowConfiguration?.hashCode() ?: 0
        result = 31 * result + densityDpi
        result = 31 * result + orientation
        result = 31 * result + screenHeightDp
        result = 31 * result + screenWidthDp
        result = 31 * result + smallestScreenWidthDp
        result = 31 * result + screenLayout
        result = 31 * result + uiMode
        return result
    }

    companion object {
        @JsName("EMPTY")
        val EMPTY: Configuration
            get() = withCache { Configuration() }

        @JsName("from")
        fun from(
            windowConfiguration: WindowConfiguration?,
            densityDpi: Int,
            orientation: Int,
            screenHeightDp: Int,
            screenWidthDp: Int,
            smallestScreenWidthDp: Int,
            screenLayout: Int,
            uiMode: Int
        ): Configuration = withCache {
            Configuration(
                windowConfiguration,
                densityDpi,
                orientation,
                screenHeightDp,
                screenWidthDp,
                smallestScreenWidthDp,
                screenLayout,
                uiMode
            )
        }
    }
}
