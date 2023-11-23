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

package android.tools.common.flicker.subject.events

import android.tools.common.CrossPlatform
import android.tools.common.flicker.subject.FlickerSubject
import android.tools.common.flicker.subject.exceptions.ExceptionMessageBuilder
import android.tools.common.flicker.subject.exceptions.IncorrectFocusException
import android.tools.common.io.IReader
import android.tools.common.traces.events.EventLog
import android.tools.common.traces.events.FocusEvent

/** Truth subject for [FocusEvent] objects. */
class EventLogSubject(val eventLog: EventLog, override val reader: IReader) : FlickerSubject() {
    override val timestamp = CrossPlatform.timestamp.empty()

    private val _focusChanges by lazy {
        val focusList = mutableListOf<String>()
        eventLog.focusEvents.firstOrNull { !it.hasFocus() }?.let { focusList.add(it.window) }
        focusList + eventLog.focusEvents.filter { it.hasFocus() }.map { it.window }
    }

    private val exceptionMessageBuilder
        get() = ExceptionMessageBuilder().forSubject(this)

    fun focusChanges(vararg windows: String) = apply {
        if (windows.isNotEmpty()) {
            val focusChanges =
                _focusChanges.dropWhile { !it.contains(windows.first()) }.take(windows.size)

            val builder =
                exceptionMessageBuilder.setExpected(windows.joinToString()).setActual(focusChanges)

            if (focusChanges.isEmpty()) {
                val errorMsgBuilder = builder.setMessage("Focus did not change")
                throw IncorrectFocusException(errorMsgBuilder)
            }

            val success =
                windows.size <= focusChanges.size &&
                    focusChanges.zip(windows).all { (focus, search) -> focus.contains(search) }

            if (!success) {
                val errorMsgBuilder = builder.setMessage("Incorrect focus change")
                throw IncorrectFocusException(errorMsgBuilder)
            }
        }
    }

    fun focusDoesNotChange() = apply {
        if (_focusChanges.isNotEmpty()) {
            val errorMsgBuilder =
                exceptionMessageBuilder
                    .setMessage("Focus changes")
                    .setExpected("")
                    .setActual(_focusChanges)
            throw IncorrectFocusException(errorMsgBuilder)
        }
    }
}
