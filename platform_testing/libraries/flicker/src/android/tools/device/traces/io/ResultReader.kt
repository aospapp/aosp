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

package android.tools.device.traces.io

import android.tools.common.CrossPlatform
import android.tools.common.Tag
import android.tools.common.Timestamp
import android.tools.common.io.FLICKER_IO_TAG
import android.tools.common.io.IArtifact
import android.tools.common.io.IReader
import android.tools.common.io.ResultArtifactDescriptor
import android.tools.common.io.TraceType
import android.tools.common.parsers.events.EventLogParser
import android.tools.common.traces.events.CujTrace
import android.tools.common.traces.events.EventLog
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.surfaceflinger.TransactionsTrace
import android.tools.common.traces.wm.TransitionsTrace
import android.tools.common.traces.wm.WindowManagerTrace
import android.tools.device.traces.TraceConfig
import android.tools.device.traces.TraceConfigs
import android.tools.device.traces.parsers.surfaceflinger.LayersTraceParser
import android.tools.device.traces.parsers.surfaceflinger.TransactionsTraceParser
import android.tools.device.traces.parsers.wm.TransitionTraceParser
import android.tools.device.traces.parsers.wm.WindowManagerDumpParser
import android.tools.device.traces.parsers.wm.WindowManagerTraceParser
import androidx.annotation.VisibleForTesting
import java.io.IOException

/**
 * Helper class to read results from a flicker artifact
 *
 * @param _result to read from
 * @param traceConfig
 */
open class ResultReader(_result: IResultData, internal val traceConfig: TraceConfigs) : IReader {
    @VisibleForTesting
    var result = _result
        internal set
    override val artifact: IArtifact = result.artifact
    override val artifactPath: String
        get() = result.artifact.absolutePath
    override val runStatus
        get() = result.runStatus
    internal val transitionTimeRange
        get() = result.transitionTimeRange
    override val isFailure
        get() = runStatus.isFailure
    override val executionError
        get() = result.executionError

    override fun readBytes(traceType: TraceType, tag: String): ByteArray? =
        artifact.readBytes(ResultArtifactDescriptor(traceType, tag))

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readWmState(tag: String): WindowManagerTrace? {
        return CrossPlatform.log.withTracing("readWmState#$tag") {
            val descriptor = ResultArtifactDescriptor(TraceType.WM_DUMP, tag)
            CrossPlatform.log.d(
                FLICKER_IO_TAG,
                "Reading WM trace descriptor=$descriptor from $result"
            )
            val traceData = artifact.readBytes(descriptor)
            traceData?.let { WindowManagerDumpParser().parse(it, clearCache = true) }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readWmTrace(): WindowManagerTrace? {
        return CrossPlatform.log.withTracing("readWmTrace") {
            val descriptor = ResultArtifactDescriptor(TraceType.WM)
            artifact.readBytes(descriptor)?.let {
                val trace =
                    WindowManagerTraceParser()
                        .parse(
                            it,
                            from = transitionTimeRange.start,
                            to = transitionTimeRange.end,
                            addInitialEntry = true,
                            clearCache = true
                        )
                val minimumEntries = minimumTraceEntriesForConfig(traceConfig.wmTrace)
                require(trace.entries.size >= minimumEntries) {
                    "WM trace contained ${trace.entries.size} entries, " +
                        "expected at least $minimumEntries... :: " +
                        "transition starts at ${transitionTimeRange.start} and " +
                        "ends at ${transitionTimeRange.end}."
                }
                trace
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readLayersTrace(): LayersTrace? {
        return CrossPlatform.log.withTracing("readLayersTrace") {
            val descriptor = ResultArtifactDescriptor(TraceType.SF)
            artifact.readBytes(descriptor)?.let {
                val trace =
                    LayersTraceParser()
                        .parse(
                            it,
                            transitionTimeRange.start,
                            transitionTimeRange.end,
                            addInitialEntry = true,
                            clearCache = true
                        )
                val minimumEntries = minimumTraceEntriesForConfig(traceConfig.layersTrace)
                require(trace.entries.size >= minimumEntries) {
                    "Layers trace contained ${trace.entries.size} entries, " +
                        "expected at least $minimumEntries... :: " +
                        "transition starts at ${transitionTimeRange.start} and " +
                        "ends at ${transitionTimeRange.end}."
                }
                trace
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readLayersDump(tag: String): LayersTrace? {
        return CrossPlatform.log.withTracing("readLayersDump#$tag") {
            val descriptor = ResultArtifactDescriptor(TraceType.SF_DUMP, tag)
            val traceData = artifact.readBytes(descriptor)
            traceData?.let { LayersTraceParser().parse(it, clearCache = true) }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readTransactionsTrace(): TransactionsTrace? =
        CrossPlatform.log.withTracing("readTransactionsTrace") {
            doReadTransactionsTrace(from = transitionTimeRange.start, to = transitionTimeRange.end)
        }

    private fun doReadTransactionsTrace(from: Timestamp, to: Timestamp): TransactionsTrace? {
        val traceData = artifact.readBytes(ResultArtifactDescriptor(TraceType.TRANSACTION))
        return traceData?.let {
            val trace = TransactionsTraceParser().parse(it, from, to, addInitialEntry = true)
            require(trace.entries.isNotEmpty()) { "Transactions trace cannot be empty" }
            trace
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readTransitionsTrace(): TransitionsTrace? {
        return CrossPlatform.log.withTracing("readTransitionsTrace") {
            val wmSideTraceData =
                artifact.readBytes(ResultArtifactDescriptor(TraceType.WM_TRANSITION))
            val shellSideTraceData =
                artifact.readBytes(ResultArtifactDescriptor(TraceType.SHELL_TRANSITION))

            if (wmSideTraceData == null || shellSideTraceData == null) {
                null
            } else {
                val trace =
                    TransitionTraceParser()
                        .parse(
                            wmSideTraceData,
                            shellSideTraceData,
                            from = transitionTimeRange.start,
                            to = transitionTimeRange.end
                        )
                if (!traceConfig.transitionsTrace.allowNoChange) {
                    require(trace.entries.isNotEmpty()) { "Transitions trace cannot be empty" }
                }
                trace
            }
        }
    }

    private fun minimumTraceEntriesForConfig(config: TraceConfig): Int {
        return if (config.allowNoChange) 1 else 2
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readEventLogTrace(): EventLog? {
        return CrossPlatform.log.withTracing("readEventLogTrace") {
            val descriptor = ResultArtifactDescriptor(TraceType.EVENT_LOG)
            artifact.readBytes(descriptor)?.let {
                EventLogParser()
                    .parseSlice(it, from = transitionTimeRange.start, to = transitionTimeRange.end)
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException if the artifact file doesn't exist or can't be read
     */
    @Throws(IOException::class)
    override fun readCujTrace(): CujTrace? = readEventLogTrace()?.cujTrace

    /** @return an [IReader] for the subsection of the trace we are reading in this reader */
    override fun slice(startTimestamp: Timestamp, endTimestamp: Timestamp): ResultReader {
        val slicedResult = result.slice(startTimestamp, endTimestamp)
        return ResultReader(slicedResult, traceConfig)
    }

    override fun toString(): String = "$result"

    /** @return the number of files in the artifact */
    @VisibleForTesting fun countFiles(): Int = artifact.traceCount()

    /** @return if a file with type [traceType] linked to a [tag] exists in the artifact */
    fun hasTraceFile(traceType: TraceType, tag: String = Tag.ALL): Boolean {
        val descriptor = ResultArtifactDescriptor(traceType, tag)
        return artifact.hasTrace(descriptor)
    }
}
