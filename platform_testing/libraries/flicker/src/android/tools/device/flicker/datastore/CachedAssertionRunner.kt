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

import android.tools.common.IScenario
import android.tools.common.flicker.assertions.SubjectsParser
import android.tools.common.io.RunStatus
import android.tools.device.flicker.assertions.BaseAssertionRunner
import android.tools.device.traces.TRACE_CONFIG_REQUIRE_CHANGES

/**
 * Helper class to run an assertion on a flicker artifact from a [DataStore]
 *
 * @param scenario flicker scenario existing in the [DataStore]
 * @param resultReader helper class to read the flicker artifact
 * @param subjectsParser helper class to convert a result into flicker subjects
 */
class CachedAssertionRunner(
    private val scenario: IScenario,
    resultReader: CachedResultReader = CachedResultReader(scenario, TRACE_CONFIG_REQUIRE_CHANGES),
    subjectsParser: SubjectsParser = SubjectsParser(resultReader)
) : BaseAssertionRunner(resultReader, subjectsParser) {
    override fun doUpdateStatus(newStatus: RunStatus) {
        val result = DataStore.getResult(scenario)
        result.updateStatus(newStatus)
        DataStore.replaceResult(scenario, result)
    }
}
