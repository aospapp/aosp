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

package android.tools.common.io

import android.tools.CleanFlickerEnvironmentRule
import android.tools.assertThrows
import com.google.common.truth.Truth
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** Tests for [TraceType] */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TraceTypeTest {
    @Test
    fun canParseTraceTypes() {
        assertFileName(TraceType.SF)
        assertFileName(TraceType.WM)
        assertFileName(TraceType.TRANSACTION)
        assertFileName(TraceType.WM_TRANSITION)
        assertFileName(TraceType.SHELL_TRANSITION)
        assertFileName(TraceType.SCREEN_RECORDING)
    }

    @Test
    fun canParseDumpTypes() {
        assertFileName(TraceType.SF_DUMP)
        assertFileName(TraceType.WM_DUMP)
        assertFileName(
            TraceType.SF_DUMP,
            TraceType.fromFileName("prefix${TraceType.SF_DUMP.fileName}")
        )
        assertFileName(
            TraceType.WM_DUMP,
            TraceType.fromFileName("prefix${TraceType.WM_DUMP.fileName}")
        )
    }

    @Test
    fun failParseInvalidTypes() {
        assertFailure("prefix${TraceType.SF.fileName}")
        assertFailure("prefix${TraceType.WM.fileName}")
        assertFailure("prefix${TraceType.TRANSACTION.fileName}")
        assertFailure("prefix${TraceType.WM_TRANSITION.fileName}")
        assertFailure("prefix${TraceType.SCREEN_RECORDING.fileName}")
        assertFailure("${TraceType.SF_DUMP.fileName}suffix")
        assertFailure("${TraceType.WM_DUMP.fileName}suffix")
    }

    private fun assertFailure(fileName: String) {
        assertThrows<IllegalStateException> { TraceType.fromFileName(fileName) }
    }

    private fun assertFileName(
        type: TraceType,
        newInstance: TraceType = TraceType.fromFileName(type.fileName)
    ) {
        Truth.assertWithMessage("Trace type matches file name").that(newInstance).isEqualTo(type)
    }

    companion object {
        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
