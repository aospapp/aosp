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

import android.tools.common.CrossPlatform
import android.tools.common.Timestamp
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents a CUJ Event from the [EventLog]
 *
 * {@inheritDoc}
 */
@JsExport
class CujEvent(
    timestamp: Timestamp,
    val cuj: CujType,
    processId: Int,
    uid: String,
    threadId: Int,
    eventTag: String,
    val cujTag: String?,
) : Event(timestamp, processId, uid, threadId, eventTag) {

    val type: Type = eventTag.asCujType()

    override fun toString(): String {
        return "CujEvent(" +
            "timestamp=$timestamp, " +
            "cuj=$cuj, " +
            "processId=$processId, " +
            "uid=$uid, " +
            "threadId=$threadId, " +
            "tag=$tag" +
            ")"
    }

    companion object {
        @JsName("fromData")
        fun fromData(
            processId: Int,
            uid: String,
            threadId: Int,
            eventTag: String,
            data: String
        ): CujEvent {
            return CujEvent(
                CrossPlatform.timestamp.from(
                    elapsedNanos = getElapsedTimestampFromData(data, eventTag.asCujType()),
                    systemUptimeNanos = getSystemUptimeNanosFromData(data, eventTag.asCujType()),
                    unixNanos = getUnixTimestampFromData(data, eventTag.asCujType())
                ),
                getCujMarkerFromData(data, eventTag.asCujType()),
                processId,
                uid,
                threadId,
                eventTag,
                getCujTagFromData(data, eventTag.asCujType())
            )
        }

        private fun getCujMarkerFromData(data: String, cujType: Type): CujType {
            val dataEntries = getDataEntries(data, cujType)
            val eventId = dataEntries[0].toInt()
            return CujType.from(eventId)
        }

        private fun getUnixTimestampFromData(data: String, cujType: Type): Long {
            val dataEntries = getDataEntries(data, cujType)
            return dataEntries[1].toLong()
        }

        private fun getElapsedTimestampFromData(data: String, cujType: Type): Long {
            val dataEntries = getDataEntries(data, cujType)
            return dataEntries[2].toLong()
        }

        private fun getSystemUptimeNanosFromData(data: String, cujType: Type): Long {
            val dataEntries = getDataEntries(data, cujType)
            return dataEntries[3].toLong()
        }

        private fun getCujTagFromData(data: String, cujType: Type): String? {
            val dataEntries = getDataEntries(data, cujType)
            return when (cujType) {
                Type.START -> dataEntries[4]
                else -> null
            }
        }

        private fun isNumeric(toCheck: String): Boolean {
            return toCheck.all { char -> char.isDigit() }
        }

        private fun getDataEntries(data: String, cujType: Type): List<String> {
            when (cujType) {
                Type.START -> {
                    val (rawTimestamp, uid, pid, tid, _) =
                        data.replace("[", "").replace("]", "").split(",")
                    // Not using a Regex because it's not supported by Kotlin/Closure
                    require(
                        isNumeric(rawTimestamp) &&
                            isNumeric(uid) &&
                            isNumeric(pid) &&
                            isNumeric(tid)
                    ) {
                        "Data \"$data\" didn't match expected format"
                    }
                }
                else -> {
                    // Not using a Regex because it's not supported by Kotlin/Closure
                    val (rawTimestamp, uid, pid, tid, _) =
                        data.replace("[", "").replace("]", "").split(",")
                    require(
                        isNumeric(rawTimestamp) &&
                            isNumeric(uid) &&
                            isNumeric(pid) &&
                            isNumeric(tid)
                    ) {
                        "Data \"$data\" didn't match expected format"
                    }
                }
            }

            return data.slice(1..data.length - 2).split(",")
        }

        enum class Type {
            START,
            END,
            CANCEL
        }

        const val JANK_CUJ_BEGIN_TAG = "jank_cuj_events_begin_request"
        const val JANK_CUJ_END_TAG = "jank_cuj_events_end_request"
        const val JANK_CUJ_CANCEL_TAG = "jank_cuj_events_cancel_request"

        fun String.asCujType(): Type {
            return when (this) {
                JANK_CUJ_BEGIN_TAG -> Type.START
                JANK_CUJ_END_TAG -> Type.END
                JANK_CUJ_CANCEL_TAG -> Type.CANCEL
                else -> error("Unhandled tag type")
            }
        }
    }
}
