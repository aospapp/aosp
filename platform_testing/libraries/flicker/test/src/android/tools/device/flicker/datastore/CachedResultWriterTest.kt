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
import android.tools.assertThrows
import android.tools.newTestCachedResultWriter
import com.google.common.truth.Truth
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Tests for [CachedResultWriterTest] */
@SuppressLint("VisibleForTests")
class CachedResultWriterTest {
    @Test
    fun writeToStore() {
        val writer = newTestCachedResultWriter()
        val expected = writer.write()
        Truth.assertWithMessage("Has key in store")
            .that(DataStore.containsResult(TEST_SCENARIO))
            .isTrue()
        val actual = DataStore.getResult(TEST_SCENARIO)
        Truth.assertWithMessage("Has key in store").that(expected).isEqualTo(actual)
    }

    @Test
    fun writeToStoreFailsWhenWriteTwice() {
        val writer = newTestCachedResultWriter()
        val failure =
            assertThrows<IllegalArgumentException> {
                writer.write()
                writer.write()
            }
        Truth.assertWithMessage("Has key in store")
            .that(DataStore.containsResult(TEST_SCENARIO))
            .isTrue()
        assertExceptionMessage(failure, TEST_SCENARIO.toString())
        assertExceptionMessage(failure, "already in data store")
    }

    companion object {
        @Rule @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
