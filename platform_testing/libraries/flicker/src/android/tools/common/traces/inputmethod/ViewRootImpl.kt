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
package android.tools.common.traces.inputmethod

import android.tools.common.datatypes.Rect
import android.tools.common.traces.wm.DisplayCutout
import android.tools.common.traces.wm.WindowLayoutParams

/**
 * Represents the ViewRootImplProto in IME traces
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
data class ViewRootImpl(
    val view: String,
    val displayId: Int,
    val appVisible: Boolean,
    val width: Int,
    val height: Int,
    val isAnimating: Boolean,
    val visibleRect: Rect,
    val isDrawing: Boolean,
    val added: Boolean,
    val winFrame: Rect,
    val pendingDisplayCutout: DisplayCutout,
    val lastWindowInsets: String,
    val softInputMode: String,
    val scrollY: Int,
    val curScrollY: Int,
    val removed: Boolean,
    val windowAttributes: WindowLayoutParams,
) {
    override fun toString(): String {
        return "${this::class.simpleName}: {$view, displayId: $displayId," +
            "appVisible: $appVisible, visibleRect: $visibleRect," +
            "isAnimating: $isAnimating, isDrawing: $isDrawing, softInputMode: $softInputMode}"
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewRootImpl) return false
        if (view != other.view) return false
        if (displayId != other.displayId) return false
        if (appVisible != other.appVisible) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (isAnimating != other.isAnimating) return false
        if (visibleRect != other.visibleRect) return false
        if (isDrawing != other.isDrawing) return false
        if (added != other.added) return false
        if (winFrame != other.winFrame) return false
        if (pendingDisplayCutout != other.pendingDisplayCutout) return false
        if (lastWindowInsets != other.lastWindowInsets) return false
        if (softInputMode != other.softInputMode) return false
        if (scrollY != other.scrollY) return false
        if (curScrollY != other.curScrollY) return false
        if (removed != other.removed) return false
        if (windowAttributes != other.windowAttributes) return false
        return true
    }
    override fun hashCode(): Int {
        var result = view.hashCode()
        result = 31 * result + displayId
        result = 31 * result + appVisible.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + isAnimating.hashCode()
        result = 31 * result + visibleRect.hashCode()
        result = 31 * result + isDrawing.hashCode()
        result = 31 * result + added.hashCode()
        result = 31 * result + winFrame.hashCode()
        result = 31 * result + pendingDisplayCutout.hashCode()
        result = 31 * result + lastWindowInsets.hashCode()
        result = 31 * result + softInputMode.hashCode()
        result = 31 * result + scrollY
        result = 31 * result + curScrollY
        result = 31 * result + removed.hashCode()
        result = 31 * result + windowAttributes.hashCode()
        return result
    }
}
