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

package android.tools.device.traces.parsers.wm

import android.tools.common.CrossPlatform
import android.tools.common.Timestamp
import android.tools.common.parsers.AbstractTraceParser
import android.tools.common.traces.wm.Transition
import android.tools.common.traces.wm.TransitionChange
import android.tools.common.traces.wm.TransitionType
import android.tools.common.traces.wm.TransitionsTrace
import android.tools.common.traces.wm.WmTransitionData
import com.android.server.wm.shell.nano.TransitionTraceProto

/** Parser for [TransitionsTrace] objects */
class WmTransitionTraceParser :
    AbstractTraceParser<
        TransitionTraceProto,
        com.android.server.wm.shell.nano.Transition,
        Transition,
        TransitionsTrace
    >() {
    override val traceName: String = "Transition trace (WM)"

    override fun createTrace(entries: List<Transition>): TransitionsTrace {
        return TransitionsTrace(entries.toTypedArray())
    }

    override fun doDecodeByteArray(bytes: ByteArray): TransitionTraceProto =
        TransitionTraceProto.parseFrom(bytes)

    override fun shouldParseEntry(entry: com.android.server.wm.shell.nano.Transition): Boolean {
        return true
    }

    override fun getEntries(
        input: TransitionTraceProto
    ): List<com.android.server.wm.shell.nano.Transition> = input.transitions.toList()

    override fun getTimestamp(entry: com.android.server.wm.shell.nano.Transition): Timestamp {
        requireValidTimestamp(entry)

        if (entry.createTimeNs != 0L) {
            return CrossPlatform.timestamp.from(elapsedNanos = entry.createTimeNs)
        }
        if (entry.sendTimeNs != 0L) {
            return CrossPlatform.timestamp.from(elapsedNanos = entry.sendTimeNs)
        }
        if (entry.abortTimeNs != 0L) {
            return CrossPlatform.timestamp.from(elapsedNanos = entry.abortTimeNs)
        }
        if (entry.finishTimeNs != 0L) {
            return CrossPlatform.timestamp.from(elapsedNanos = entry.finishTimeNs)
        }

        error("No valid timestamp available in entry")
    }

    override fun onBeforeParse(input: TransitionTraceProto) {}

    override fun doParse(
        input: TransitionTraceProto,
        from: Timestamp,
        to: Timestamp,
        addInitialEntry: Boolean
    ): TransitionsTrace {
        val uncompressedTransitionsTrace = super.doParse(input, from, to, addInitialEntry)
        return uncompressedTransitionsTrace.asCompressed()
    }

    override fun doParseEntry(entry: com.android.server.wm.shell.nano.Transition): Transition {
        require(entry.id != 0) { "Entry needs a non null id" }
        requireValidTimestamp(entry)

        val changes =
            if (entry.targets.isEmpty()) null
            else
                entry.targets
                    .map {
                        TransitionChange(
                            TransitionType.fromInt(it.mode),
                            it.layerId,
                            it.windowId,
                        )
                    }
                    .toTypedArray()

        return Transition(
            entry.id,
            wmData =
                WmTransitionData(
                    createTime =
                        if (entry.createTimeNs == 0L) null
                        else CrossPlatform.timestamp.from(elapsedNanos = entry.createTimeNs),
                    sendTime =
                        if (entry.sendTimeNs == 0L) null
                        else CrossPlatform.timestamp.from(elapsedNanos = entry.sendTimeNs),
                    abortTime =
                        if (entry.abortTimeNs == 0L) null
                        else CrossPlatform.timestamp.from(elapsedNanos = entry.abortTimeNs),
                    finishTime =
                        if (entry.finishTimeNs == 0L) null
                        else CrossPlatform.timestamp.from(elapsedNanos = entry.finishTimeNs),
                    startTransactionId =
                        if (entry.startTransactionId == 0L) null
                        else entry.startTransactionId.toString(),
                    finishTransactionId =
                        if (entry.finishTransactionId == 0L) null
                        else entry.finishTransactionId.toString(),
                    type = if (entry.type == 0) null else TransitionType.fromInt(entry.type),
                    changes = changes,
                ),
        )
    }
    companion object {
        private fun requireValidTimestamp(entry: com.android.server.wm.shell.nano.Transition) {
            require(
                entry.createTimeNs != 0L ||
                    entry.sendTimeNs != 0L ||
                    entry.abortTimeNs != 0L ||
                    entry.finishTimeNs != 0L
            ) {
                "Requires at least one non-null timestamp"
            }
        }
    }
}
