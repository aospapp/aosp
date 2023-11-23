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

/**
 * Represents the InputMethodManagerProto in IME traces
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
data class InputMethodManager(
    val curId: String,
    val fullscreenMode: Boolean,
    val displayId: Int,
    val active: Boolean,
    val servedConnecting: Boolean,
) {
    override fun toString(): String {
        return "${this::class.simpleName}: {$curId, fullscreenMode: $fullscreenMode, " +
            "displayId: $displayId, active: $active, servedConnecting: $servedConnecting}"
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InputMethodManager) return false
        if (curId != other.curId) return false
        if (fullscreenMode != other.fullscreenMode) return false
        if (displayId != other.displayId) return false
        if (active != other.active) return false
        if (servedConnecting != other.servedConnecting) return false
        return true
    }
    override fun hashCode(): Int {
        var result = curId.hashCode()
        result = 31 * result + fullscreenMode.hashCode()
        result = 31 * result + displayId
        result = 31 * result + active.hashCode()
        result = 31 * result + servedConnecting.hashCode()
        return result
    }
}
