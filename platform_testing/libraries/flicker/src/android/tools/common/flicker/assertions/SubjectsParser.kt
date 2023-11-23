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

package android.tools.common.flicker.assertions

import android.tools.common.Tag
import android.tools.common.flicker.subject.FlickerSubject
import android.tools.common.flicker.subject.events.EventLogSubject
import android.tools.common.flicker.subject.layers.LayerTraceEntrySubject
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.flicker.subject.wm.WindowManagerStateSubject
import android.tools.common.flicker.subject.wm.WindowManagerTraceSubject
import android.tools.common.io.IReader
import android.tools.common.traces.events.FocusEvent
import android.tools.common.traces.surfaceflinger.LayerTraceEntry
import android.tools.common.traces.surfaceflinger.LayersTrace
import android.tools.common.traces.wm.WindowManagerState
import android.tools.common.traces.wm.WindowManagerTrace
import kotlin.reflect.KClass

/**
 * Helper class to read traces from a [resultReader] and parse them into subjects for assertion
 *
 * @param resultReader to read the result artifacts
 */
open class SubjectsParser(private val resultReader: IReader) {
    fun getSubjectOfType(
        tag: String,
        expectedSubjectClass: KClass<out FlickerSubject>
    ): FlickerSubject? {
        return when {
            tag == Tag.ALL && expectedSubjectClass == WindowManagerTraceSubject::class ->
                wmTraceSubject
            tag == Tag.ALL && expectedSubjectClass == LayersTraceSubject::class ->
                layersTraceSubject
            expectedSubjectClass == EventLogSubject::class -> eventLogSubject
            expectedSubjectClass == WindowManagerStateSubject::class -> getWmStateSubject(tag)
            expectedSubjectClass == LayerTraceEntrySubject::class -> getLayerTraceEntrySubject(tag)
            else -> error("Unknown expected subject type $expectedSubjectClass")
        }
    }

    /** Truth subject that corresponds to a [WindowManagerTrace] */
    private val wmTraceSubject: WindowManagerTraceSubject?
        get() = doGetWmTraceSubject()

    protected open fun doGetWmTraceSubject(): WindowManagerTraceSubject? {
        val trace = resultReader.readWmTrace() ?: return null
        return WindowManagerTraceSubject(trace, resultReader)
    }

    /** Truth subject that corresponds to a [LayersTrace] */
    private val layersTraceSubject: LayersTraceSubject?
        get() = doGetLayersTraceSubject()

    protected open fun doGetLayersTraceSubject(): LayersTraceSubject? {
        val trace = resultReader.readLayersTrace() ?: return null
        return LayersTraceSubject(trace, resultReader)
    }

    /** Truth subject that corresponds to a [WindowManagerState] */
    private fun getWmStateSubject(tag: String): WindowManagerStateSubject? =
        doGetWmStateSubject(tag)

    protected open fun doGetWmStateSubject(tag: String): WindowManagerStateSubject? {
        return when (tag) {
            Tag.START -> wmTraceSubject?.subjects?.firstOrNull()
            Tag.END -> wmTraceSubject?.subjects?.lastOrNull()
            else -> {
                val trace = resultReader.readWmState(tag) ?: return null
                WindowManagerStateSubject(trace.entries.first(), resultReader)
            }
        }
    }

    /** Truth subject that corresponds to a [LayerTraceEntry] */
    private fun getLayerTraceEntrySubject(tag: String): LayerTraceEntrySubject? =
        doGetLayerTraceEntrySubject(tag)

    protected open fun doGetLayerTraceEntrySubject(tag: String): LayerTraceEntrySubject? {
        return when (tag) {
            Tag.START -> layersTraceSubject?.subjects?.firstOrNull()
            Tag.END -> layersTraceSubject?.subjects?.lastOrNull()
            else -> {
                val trace = resultReader.readLayersDump(tag) ?: return null
                return LayersTraceSubject(trace, resultReader).first()
            }
        }
    }

    /** Truth subject that corresponds to a list of [FocusEvent] */
    val eventLogSubject: EventLogSubject?
        get() = doGetEventLogSubject()

    protected open fun doGetEventLogSubject(): EventLogSubject? {
        val trace = resultReader.readEventLogTrace() ?: return null
        return EventLogSubject(trace, resultReader)
    }
}
