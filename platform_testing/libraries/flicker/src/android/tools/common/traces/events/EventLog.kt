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

package android.tools.common.traces.events

import android.tools.common.ITrace
import android.tools.common.Timestamp
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents the data from the Android EventLog and contains a collection of parsed events of
 * interest.
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
class EventLog(override val entries: Array<Event>) : ITrace<Event> {
    val focusEvents: Array<FocusEvent> =
        entries
            .filterIsInstance<FocusEvent>()
            .filter { it.type !== FocusEvent.Type.REQUESTED }
            .toTypedArray()

    val cujEvents: Array<CujEvent> = entries.filterIsInstance<CujEvent>().toTypedArray()

    @JsName("cujTrace") val cujTrace: CujTrace = CujTrace.from(cujEvents)

    companion object {
        const val MAGIC_NUMBER = "EventLog"
    }

    override fun slice(startTimestamp: Timestamp, endTimestamp: Timestamp): EventLog {
        return EventLog(
            entries
                .dropWhile { it.timestamp < startTimestamp }
                .dropLastWhile { it.timestamp > endTimestamp }
                .toTypedArray()
        )
    }
}
