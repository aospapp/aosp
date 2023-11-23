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

import android.tools.common.ITraceEntry
import android.tools.common.Timestamp
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
data class Cuj(
    @JsName("cuj") val cuj: CujType,
    @JsName("startTimestamp") val startTimestamp: Timestamp,
    @JsName("endTimestamp") val endTimestamp: Timestamp,
    @JsName("canceled") val canceled: Boolean
) : ITraceEntry {
    override val timestamp: Timestamp = startTimestamp
}
