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

package android.tools.device.traces.parsers

import android.tools.common.CrossPlatform
import android.tools.common.Timestamp
import android.tools.common.parsers.AbstractTraceParser
import android.tools.common.traces.wm.WindowManagerState
import android.tools.common.traces.wm.WindowManagerTrace

class MockTraceParser(private val data: WindowManagerTrace) :
    AbstractTraceParser<
        WindowManagerTrace, WindowManagerState, WindowManagerState, WindowManagerTrace
    >() {
    override val traceName: String = "In memory trace"

    override fun createTrace(entries: List<WindowManagerState>): WindowManagerTrace =
        WindowManagerTrace(entries.toTypedArray())

    override fun doDecodeByteArray(bytes: ByteArray): WindowManagerTrace = data
    override fun doParseEntry(entry: WindowManagerState): WindowManagerState = entry
    override fun getEntries(input: WindowManagerTrace): List<WindowManagerState> =
        input.entries.toList()
    override fun getTimestamp(entry: WindowManagerState): Timestamp =
        CrossPlatform.timestamp.from(elapsedNanos = entry.elapsedTimestamp)
    override fun onBeforeParse(input: WindowManagerTrace) {}
}
