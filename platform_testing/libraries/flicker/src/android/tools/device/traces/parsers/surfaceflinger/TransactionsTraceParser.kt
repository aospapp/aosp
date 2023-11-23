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

package android.tools.device.traces.parsers.surfaceflinger

import android.surfaceflinger.proto.Transactions
import android.surfaceflinger.proto.Transactions.TransactionState
import android.surfaceflinger.proto.Transactions.TransactionTraceFile
import android.tools.common.CrossPlatform
import android.tools.common.Timestamp
import android.tools.common.parsers.AbstractTraceParser
import android.tools.common.traces.surfaceflinger.Transaction
import android.tools.common.traces.surfaceflinger.TransactionsTrace
import android.tools.common.traces.surfaceflinger.TransactionsTraceEntry
import android.tools.common.traces.wm.TransitionsTrace

/** Parser for [TransitionsTrace] objects */
class TransactionsTraceParser :
    AbstractTraceParser<
        TransactionTraceFile,
        Transactions.TransactionTraceEntry,
        TransactionsTraceEntry,
        TransactionsTrace
    >() {
    private var timestampOffset = 0L
    override val traceName: String = "Transactions trace"

    override fun onBeforeParse(input: TransactionTraceFile) {
        timestampOffset = input.realToElapsedTimeOffsetNanos
    }

    override fun getTimestamp(entry: Transactions.TransactionTraceEntry): Timestamp {
        require(timestampOffset != 0L)
        return CrossPlatform.timestamp.from(
            elapsedNanos = entry.elapsedRealtimeNanos,
            unixNanos = entry.elapsedRealtimeNanos + timestampOffset
        )
    }

    override fun createTrace(entries: List<TransactionsTraceEntry>): TransactionsTrace =
        TransactionsTrace(entries.toTypedArray())

    override fun getEntries(input: TransactionTraceFile): List<Transactions.TransactionTraceEntry> =
        input.entryList

    override fun doDecodeByteArray(bytes: ByteArray): TransactionTraceFile =
        TransactionTraceFile.parseFrom(bytes)

    override fun doParseEntry(entry: Transactions.TransactionTraceEntry): TransactionsTraceEntry {
        val transactions = parseTransactionsProto(entry.transactionsList)
        val transactionsTraceEntry =
            TransactionsTraceEntry(
                CrossPlatform.timestamp.from(
                    elapsedNanos = entry.elapsedRealtimeNanos,
                    elapsedOffsetNanos = timestampOffset
                ),
                entry.vsyncId,
                transactions
            )
        transactions.forEach { it.appliedInEntry = transactionsTraceEntry }
        return transactionsTraceEntry
    }

    private fun parseTransactionsProto(
        transactionStates: List<TransactionState>
    ): Array<Transaction> {
        val transactions = mutableListOf<Transaction>()
        for (state in transactionStates) {
            val transaction =
                Transaction(
                    state.pid,
                    state.uid,
                    state.vsyncId,
                    state.postTime,
                    state.transactionId,
                    state.mergedTransactionIdsList.toTypedArray()
                )
            transactions.add(transaction)
        }

        return transactions.toTypedArray()
    }
}
