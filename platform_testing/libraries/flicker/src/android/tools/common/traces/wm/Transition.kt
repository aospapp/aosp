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

import android.tools.common.CrossPlatform
import android.tools.common.ITraceEntry
import android.tools.common.Timestamp
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.surfaceflinger.Transaction
import android.tools.common.traces.surfaceflinger.TransactionsTrace
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
class Transition(
    @JsName("id") val id: Int,
    internal val wmData: WmTransitionData = WmTransitionData(),
    internal val shellData: ShellTransitionData = ShellTransitionData(),
) : ITraceEntry {
    init {
        require(
            wmData.createTime != null ||
                wmData.sendTime != null ||
                wmData.abortTime != null ||
                wmData.finishTime != null ||
                shellData.dispatchTime != null ||
                shellData.mergeRequestTime != null ||
                shellData.mergeTime != null ||
                shellData.abortTime != null
        ) {
            "Transition requires at least one non-null timestamp"
        }
    }

    override val timestamp =
        wmData.createTime
            ?: wmData.sendTime ?: shellData.dispatchTime ?: shellData.mergeRequestTime
                ?: shellData.mergeTime ?: shellData.abortTime ?: wmData.finishTime
                ?: wmData.abortTime ?: error("Missing non-null timestamp")

    @JsName("createTime")
    val createTime: Timestamp = wmData.createTime ?: CrossPlatform.timestamp.min()
    @JsName("sendTime") val sendTime: Timestamp = wmData.sendTime ?: CrossPlatform.timestamp.min()
    @JsName("abortTime") val abortTime: Timestamp? = wmData.abortTime
    @JsName("finishTime")
    val finishTime: Timestamp =
        wmData.finishTime ?: wmData.abortTime ?: CrossPlatform.timestamp.max()

    @JsName("dispatchTime")
    val dispatchTime: Timestamp = shellData.dispatchTime ?: CrossPlatform.timestamp.min()
    @JsName("mergeRequestTime") val mergeRequestTime: Timestamp? = shellData.mergeRequestTime
    @JsName("mergeTime") val mergeTime: Timestamp? = shellData.mergeTime
    @JsName("shellAbortTime") val shellAbortTime: Timestamp? = shellData.abortTime

    @JsName("startTransactionId")
    val startTransactionId: Long = wmData.startTransactionId?.toLong() ?: -1L
    @JsName("finishTransactionId")
    val finishTransactionId: Long = wmData.finishTransactionId?.toLong() ?: -1L
    @JsName("type") val type: TransitionType = wmData.type ?: TransitionType.UNDEFINED
    @JsName("changes") val changes: Array<TransitionChange> = wmData.changes ?: emptyArray()
    @JsName("mergedInto") val mergedInto = shellData.mergedInto
    @JsName("handler") val handler = shellData.handler

    @JsName("played") val played: Boolean = wmData.finishTime != null
    @JsName("aborted")
    val aborted: Boolean = wmData.abortTime != null || shellData.abortTime != null

    @JsName("getStartTransaction")
    fun getStartTransaction(transactionsTrace: TransactionsTrace): Transaction? {
        val matches =
            transactionsTrace.allTransactions.filter {
                it.id == this.startTransactionId ||
                    it.mergedTransactionIds.contains(this.startTransactionId)
            }
        require(matches.size <= 1) {
            "Too many transactions matches found for Transaction#${this.startTransactionId}."
        }
        return matches.firstOrNull()
    }

    @JsName("getFinishTransaction")
    fun getFinishTransaction(transactionsTrace: TransactionsTrace): Transaction? {
        val matches =
            transactionsTrace.allTransactions.filter {
                it.id == this.finishTransactionId ||
                    it.mergedTransactionIds.contains(this.finishTransactionId)
            }
        require(matches.size <= 1) {
            "Too many transactions matches found for Transaction#${this.finishTransactionId}."
        }
        return matches.firstOrNull()
    }

    @JsName("isIncomplete")
    val isIncomplete: Boolean
        get() = !played || aborted

    @JsName("merge")
    fun merge(transition: Transition): Transition {
        require(transition.mergedInto == this.id) {
            "Can't merge transition with mergedInto id ${transition.mergedInto} " +
                "into transition with id ${this.id}"
        }

        val finishTransition =
            if (transition.finishTime > this.finishTime) {
                transition
            } else {
                this
            }

        return Transition(
            id = this.id,
            wmData =
                WmTransitionData(
                    createTime = wmData.createTime,
                    sendTime = wmData.sendTime,
                    abortTime = wmData.abortTime,
                    finishTime = finishTransition.wmData.finishTime,
                    startTransactionId = wmData.startTransactionId,
                    finishTransactionId = finishTransition.wmData.finishTransactionId,
                    type = wmData.type,
                    changes =
                        (wmData.changes ?: emptyArray())
                            .toMutableList()
                            .apply { addAll(transition.wmData.changes ?: emptyArray()) }
                            .toTypedArray()
                ),
            shellData = shellData
        )
    }

    override fun toString(): String = Formatter(null, null).format(this)

    class Formatter(val layersTrace: LayersTrace?, val wmTrace: WindowManagerTrace?) {
        private val changeFormatter = TransitionChange.Formatter(layersTrace, wmTrace)

        fun format(transition: Transition): String = buildString {
            appendLine("Transition#${transition.id}(")
            appendLine("type=${transition.type},")
            appendLine("aborted=${transition.aborted},")
            appendLine("played=${transition.played},")
            appendLine("createTime=${transition.createTime},")
            appendLine("sendTime=${transition.sendTime},")
            appendLine("dispatchTime=${transition.dispatchTime},")
            appendLine("mergeRequestTime=${transition.mergeRequestTime},")
            appendLine("mergeTime=${transition.mergeTime},")
            appendLine("shellAbortTime=${transition.shellAbortTime},")
            appendLine("finishTime=${transition.finishTime},")
            appendLine("startTransactionId=${transition.startTransactionId},")
            appendLine("finishTransactionId=${transition.finishTransactionId},")
            appendLine("changes=[")
            appendLine(
                transition.changes
                    .joinToString(",\n") { changeFormatter.format(it) }
                    .prependIndent()
            )
            appendLine("]")
            appendLine(")")
        }
    }

    companion object {
        @JsName("mergePartialTransitions")
        fun mergePartialTransitions(transition1: Transition, transition2: Transition): Transition {
            require(transition1.id == transition2.id) {
                "Can't merge transitions with mismatching ids"
            }

            return Transition(
                id = transition1.id,
                transition1.wmData.merge(transition2.wmData),
                transition1.shellData.merge(transition2.shellData)
            )
        }
    }
}
