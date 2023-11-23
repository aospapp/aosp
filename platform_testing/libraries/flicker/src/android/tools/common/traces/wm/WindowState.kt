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

import android.tools.common.PlatformConsts
import android.tools.common.datatypes.Rect
import android.tools.common.datatypes.Region
import android.tools.common.datatypes.Size
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents a window in the window manager hierarchy
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
class WindowState(
    @JsName("attributes") val attributes: WindowLayoutParams,
    @JsName("displayId") val displayId: Int,
    @JsName("stackId") val stackId: Int,
    @JsName("layer") val layer: Int,
    @JsName("isSurfaceShown") val isSurfaceShown: Boolean,
    @JsName("windowType") val windowType: Int,
    @JsName("requestedSize") val requestedSize: Size,
    @JsName("surfacePosition") val surfacePosition: Rect?,
    @JsName("frame") val frame: Rect,
    @JsName("containingFrame") val containingFrame: Rect,
    val parentFrame: Rect,
    val contentFrame: Rect,
    val contentInsets: Rect,
    val surfaceInsets: Rect,
    val givenContentInsets: Rect,
    @JsName("crop") val crop: Rect,
    windowContainer: WindowContainer,
    val isAppWindow: Boolean
) : WindowContainer(windowContainer, getWindowTitle(windowContainer.title)) {
    override val isVisible: Boolean = windowContainer.isVisible && attributes.alpha > 0

    override val isFullscreen: Boolean
        get() = this.attributes.flags.and(PlatformConsts.FLAG_FULLSCREEN) > 0
    val isStartingWindow: Boolean = windowType == PlatformConsts.WINDOW_TYPE_STARTING
    val isExitingWindow: Boolean = windowType == PlatformConsts.WINDOW_TYPE_EXITING
    val isDebuggerWindow: Boolean = windowType == PlatformConsts.WINDOW_TYPE_DEBUGGER
    val isValidNavBarType: Boolean = attributes.isValidNavBarType

    @JsName("frameRegion") val frameRegion: Region = Region.from(frame)

    private fun getWindowTypeSuffix(windowType: Int): String =
        when (windowType) {
            PlatformConsts.WINDOW_TYPE_STARTING -> " STARTING"
            PlatformConsts.WINDOW_TYPE_EXITING -> " EXITING"
            PlatformConsts.WINDOW_TYPE_DEBUGGER -> " DEBUGGER"
            else -> ""
        }

    override fun toString(): String =
        "${this::class.simpleName}: " +
            "{$token $title${getWindowTypeSuffix(windowType)}} " +
            "type=${attributes.type} cf=$containingFrame pf=$parentFrame"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WindowState) return false

        if (name != other.name) return false
        if (attributes != other.attributes) return false
        if (displayId != other.displayId) return false
        if (stackId != other.stackId) return false
        if (layer != other.layer) return false
        if (isSurfaceShown != other.isSurfaceShown) return false
        if (windowType != other.windowType) return false
        if (requestedSize != other.requestedSize) return false
        if (surfacePosition != other.surfacePosition) return false
        if (frame != other.frame) return false
        if (containingFrame != other.containingFrame) return false
        if (parentFrame != other.parentFrame) return false
        if (contentFrame != other.contentFrame) return false
        if (contentInsets != other.contentInsets) return false
        if (surfaceInsets != other.surfaceInsets) return false
        if (givenContentInsets != other.givenContentInsets) return false
        if (crop != other.crop) return false
        if (isAppWindow != other.isAppWindow) return false

        return true
    }

    override fun hashCode(): Int {
        var result = attributes.hashCode()
        result = 31 * result + displayId
        result = 31 * result + stackId
        result = 31 * result + layer
        result = 31 * result + isSurfaceShown.hashCode()
        result = 31 * result + windowType
        result = 31 * result + frame.hashCode()
        result = 31 * result + containingFrame.hashCode()
        result = 31 * result + parentFrame.hashCode()
        result = 31 * result + contentFrame.hashCode()
        result = 31 * result + contentInsets.hashCode()
        result = 31 * result + surfaceInsets.hashCode()
        result = 31 * result + givenContentInsets.hashCode()
        result = 31 * result + crop.hashCode()
        result = 31 * result + isAppWindow.hashCode()
        result = 31 * result + isStartingWindow.hashCode()
        result = 31 * result + isExitingWindow.hashCode()
        result = 31 * result + isDebuggerWindow.hashCode()
        result = 31 * result + isValidNavBarType.hashCode()
        result = 31 * result + frameRegion.hashCode()
        return result
    }

    companion object {
        @JsName("getWindowTitle")
        private fun getWindowTitle(title: String): String {
            return when {
                // Existing code depends on the prefix being removed
                title.startsWith(PlatformConsts.STARTING_WINDOW_PREFIX) ->
                    title.substring(PlatformConsts.STARTING_WINDOW_PREFIX.length)
                title.startsWith(PlatformConsts.DEBUGGER_WINDOW_PREFIX) ->
                    title.substring(PlatformConsts.DEBUGGER_WINDOW_PREFIX.length)
                else -> title
            }
        }
    }
}
