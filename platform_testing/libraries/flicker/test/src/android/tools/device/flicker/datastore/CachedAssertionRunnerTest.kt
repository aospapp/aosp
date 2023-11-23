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

package android.tools.device.flicker.datastore

import android.annotation.SuppressLint
import android.tools.CleanFlickerEnvironmentRule
import android.tools.TEST_SCENARIO
import android.tools.assertExceptionMessage
import android.tools.common.Tag
import android.tools.common.flicker.assertions.AssertionData
import android.tools.common.flicker.subject.FlickerSubject
import android.tools.common.flicker.subject.events.EventLogSubject
import android.tools.common.io.RunStatus
import android.tools.device.traces.monitors.events.EventLogMonitor
import android.tools.newTestResultWriter
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Tests for [CachedAssertionRunner] */
@SuppressLint("VisibleForTests")
class CachedAssertionRunnerTest {
    private var executionCount = 0

    private val assertionSuccess = newAssertionData { executionCount++ }
    private val assertionFailure = newAssertionData {
        executionCount++
        error(Consts.FAILURE)
    }

    @Before
    fun setup() {
        executionCount = 0
        val writer = newTestResultWriter()
        val monitor = EventLogMonitor()
        monitor.start()
        monitor.stop(writer)
        val result = writer.write()
        DataStore.addResult(TEST_SCENARIO, result)
    }

    @Test
    fun executes() {
        val runner = CachedAssertionRunner(TEST_SCENARIO)
        val firstAssertionResult = runner.runAssertion(assertionSuccess)
        val lastAssertionResult = runner.runAssertion(assertionSuccess)
        val result = DataStore.getResult(TEST_SCENARIO)

        Truth.assertWithMessage("Executed").that(executionCount).isEqualTo(2)
        Truth.assertWithMessage("Run status")
            .that(result.runStatus)
            .isEqualTo(RunStatus.ASSERTION_SUCCESS)
        Truth.assertWithMessage("Expected exception").that(firstAssertionResult).isNull()
        Truth.assertWithMessage("Expected exception").that(lastAssertionResult).isNull()
    }

    @Test
    fun executesFailure() {
        val runner = CachedAssertionRunner(TEST_SCENARIO)
        val firstAssertionResult = runner.runAssertion(assertionFailure)
        val lastAssertionResult = runner.runAssertion(assertionFailure)
        val result = DataStore.getResult(TEST_SCENARIO)

        Truth.assertWithMessage("Executed").that(executionCount).isEqualTo(2)
        Truth.assertWithMessage("Run status")
            .that(result.runStatus)
            .isEqualTo(RunStatus.ASSERTION_FAILED)

        assertExceptionMessage(firstAssertionResult, Consts.FAILURE)
        assertExceptionMessage(lastAssertionResult, Consts.FAILURE)
        Truth.assertWithMessage("Same exception")
            .that(firstAssertionResult)
            .hasMessageThat()
            .isEqualTo(lastAssertionResult?.message)
    }

    @Test
    fun updatesRunStatusFailureFirst() {
        val runner = CachedAssertionRunner(TEST_SCENARIO)
        val firstAssertionResult = runner.runAssertion(assertionFailure)
        val lastAssertionResult = runner.runAssertion(assertionSuccess)
        val result = DataStore.getResult(TEST_SCENARIO)

        Truth.assertWithMessage("Executed").that(executionCount).isEqualTo(2)
        assertExceptionMessage(firstAssertionResult, Consts.FAILURE)
        Truth.assertWithMessage("Expected exception").that(lastAssertionResult).isNull()
        Truth.assertWithMessage("Run status")
            .that(result.runStatus)
            .isEqualTo(RunStatus.ASSERTION_FAILED)
    }

    @Test
    fun updatesRunStatusFailureLast() {
        val runner = CachedAssertionRunner(TEST_SCENARIO)
        val firstAssertionResult = runner.runAssertion(assertionSuccess)
        val lastAssertionResult = runner.runAssertion(assertionFailure)
        val result = DataStore.getResult(TEST_SCENARIO)

        Truth.assertWithMessage("Executed").that(executionCount).isEqualTo(2)
        Truth.assertWithMessage("Expected exception").that(firstAssertionResult).isNull()
        assertExceptionMessage(lastAssertionResult, Consts.FAILURE)
        Truth.assertWithMessage("Run status")
            .that(result.runStatus)
            .isEqualTo(RunStatus.ASSERTION_FAILED)
    }

    companion object {
        private fun newAssertionData(assertion: (FlickerSubject) -> Unit) =
            AssertionData(Tag.ALL, EventLogSubject::class, assertion)

        @Rule @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
