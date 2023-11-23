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

package android.tools.device.flicker.assertions

import android.tools.common.flicker.assertions.AssertionData
import android.tools.common.flicker.assertions.SubjectsParser
import android.tools.common.io.IReader
import android.tools.common.io.RunStatus

/**
 * Helper class to run an assertions
 *
 * @param resultReader helper class to read the flicker artifact
 * @param subjectsParser helper class to convert a result into flicker subjects
 */
abstract class BaseAssertionRunner(
    private val resultReader: IReader,
    private val subjectsParser: SubjectsParser = SubjectsParser(resultReader)
) {
    /**
     * Executes [assertion] on the subjects parsed by [subjectsParser] and update its execution
     * status
     *
     * @param assertion to run
     * @return the transition execution error (if any) , assertion error (if any), null otherwise
     */
    fun runAssertion(assertion: AssertionData): Throwable? {
        return resultReader.executionError ?: doRunAssertion(assertion)
    }

    private fun doRunAssertion(assertion: AssertionData): Throwable? {
        return try {
            assertion.checkAssertion(subjectsParser)
            updateResultStatus(error = null)
            null
        } catch (error: Throwable) {
            updateResultStatus(error)
            error
        }
    }

    private fun updateResultStatus(error: Throwable?) {
        val newStatus =
            if (error == null) RunStatus.ASSERTION_SUCCESS else RunStatus.ASSERTION_FAILED

        if (resultReader.isFailure || resultReader.runStatus == newStatus) return

        doUpdateStatus(newStatus)
    }

    protected abstract fun doUpdateStatus(newStatus: RunStatus)
}
