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

@JsExport
class WindowManagerTraceEntryBuilder(
    _elapsedTimestamp: String,
    private val policy: WindowManagerPolicy?,
    private val focusedApp: String,
    private val focusedDisplayId: Int,
    private val focusedWindow: String,
    private val inputMethodWindowAppToken: String,
    private val isHomeRecentsComponent: Boolean,
    private val isDisplayFrozen: Boolean,
    private val pendingActivities: Array<String>,
    private val root: RootWindowContainer,
    private val keyguardControllerState: KeyguardControllerState,
    private val where: String = "",
    realToElapsedTimeOffsetNs: String? = null,
) {
    // Necessary for compatibility with JS number type
    private val elapsedTimestamp: Long = _elapsedTimestamp.toLong()
    private val realTimestamp: Long? =
        if (realToElapsedTimeOffsetNs != null && realToElapsedTimeOffsetNs.toLong() != 0L) {
            realToElapsedTimeOffsetNs.toLong() + _elapsedTimestamp.toLong()
        } else {
            null
        }

    /** Constructs the window manager trace entry. */
    @JsName("build")
    fun build(): WindowManagerState {
        return WindowManagerState(
            elapsedTimestamp,
            realTimestamp,
            where,
            policy,
            focusedApp,
            focusedDisplayId,
            focusedWindow,
            inputMethodWindowAppToken,
            isHomeRecentsComponent,
            isDisplayFrozen,
            pendingActivities,
            root,
            keyguardControllerState
        )
    }
}
