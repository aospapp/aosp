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

package android.tools.common.traces

import android.tools.common.traces.events.EventLog
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.surfaceflinger.TransactionsTrace
import android.tools.common.traces.wm.TransitionsTrace
import android.tools.common.traces.wm.WindowManagerTrace

/**
 * Represents a state dump containing the [WindowManagerTrace] and the [LayersTrace] both parsed and
 * in raw (byte) data.
 *
 * @param wmTrace Parsed [WindowManagerTrace]
 * @param layersTrace Parsed [LayersTrace]
 * @param transactionsTrace Parse [TransactionsTrace]
 * @param transitionsTrace Parsed [TransitionsTrace]
 * @param eventLog Parsed [EventLog]
 */
class DeviceTraceDump(
    val wmTrace: WindowManagerTrace?,
    val layersTrace: LayersTrace?,
    val transactionsTrace: TransactionsTrace? = null,
    val transitionsTrace: TransitionsTrace? = null,
    val eventLog: EventLog? = null,
) {
    /** A deviceTraceDump is considered valid if at least one of the layers/wm traces is non-null */
    val isValid: Boolean
        get() {
            if (wmTrace == null && layersTrace == null) {
                return false
            }
            return true
        }
}
