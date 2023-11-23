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

package android.tools.common.flicker.subject.inputmethod

import android.tools.common.flicker.subject.FlickerSubject
import android.tools.common.io.IReader
import android.tools.common.traces.inputmethod.InputMethodManagerServiceEntry
import android.tools.common.traces.inputmethod.InputMethodManagerServiceTrace

/**
 * Truth subject for [InputMethodManagerServiceEntry] objects, used to make assertions over
 * behaviors that occur on a single InputMethodManagerServiceEntry state.
 *
 * Example:
 * ```
 *  val trace = InputMethodManagerServiceTraceParser.parseFromTrace(myTraceFile)
 *  val subject = InputMethodManagerServiceTraceSubject(trace).first()
 *      ...
 *      .invoke { myCustomAssertion(this) }
 *  ```
 */
class InputMethodManagerServiceEntrySubject(
    val entry: InputMethodManagerServiceEntry,
    val trace: InputMethodManagerServiceTrace?,
    override val reader: IReader? = null
) : FlickerSubject(), IInputMethodManagerServiceSubject<InputMethodManagerServiceEntrySubject> {
    override val timestamp = entry.timestamp

    /** Executes a custom [assertion] on the current subject */
    operator fun invoke(
        assertion: (InputMethodManagerServiceEntry) -> Unit
    ): InputMethodManagerServiceEntrySubject = apply { assertion(this.entry) }

    /** {@inheritDoc} */
    override fun isEmpty(): InputMethodManagerServiceEntrySubject = apply {
        check { "InputMethodManagerServiceEntry" }.that(entry).isNull()
    }

    /** {@inheritDoc} */
    override fun isNotEmpty(): InputMethodManagerServiceEntrySubject = apply {
        check { "InputMethodManagerServiceEntry" }.that(entry).isNotNull()
    }

    override fun toString() = "InputMethodManagerServiceEntrySubject($entry)"
}
