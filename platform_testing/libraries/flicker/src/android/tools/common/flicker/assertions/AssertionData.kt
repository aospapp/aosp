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

import android.tools.common.flicker.subject.FlickerSubject
import kotlin.reflect.KClass

/** Class containing basic data about an assertion */
data class AssertionData(
    /** Segment of the trace where the assertion will be applied (e.g., start, end). */
    val tag: String,
    /** Expected run result type */
    val expectedSubjectClass: KClass<out FlickerSubject>,
    /** Assertion command */
    val assertion: FlickerSubject.() -> Unit
) {
    /**
     * Extracts the data from the result and executes the assertion
     *
     * @param run Run to be asserted
     */
    fun checkAssertion(run: SubjectsParser) {
        val subject = run.getSubjectOfType(tag, expectedSubjectClass)
        subject?.let { assertion(it) }
    }

    override fun toString(): String = buildString {
        append("AssertionData(tag='")
        append(tag)
        append("', expectedSubjectClass='")
        append(expectedSubjectClass.simpleName)
        append("', assertion='")
        append(assertion)
        append(")")
    }
}
