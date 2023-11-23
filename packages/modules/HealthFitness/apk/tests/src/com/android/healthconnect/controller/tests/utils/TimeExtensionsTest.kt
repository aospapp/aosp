/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * ```
 *      http://www.apache.org/licenses/LICENSE-2.0
 * ```
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.healthconnect.controller.tests.utils

import com.android.healthconnect.controller.utils.getInstant
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Test

class TimeExtensionsTest {

    @Test
    fun getInstant_returnsCorrectInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")))

        assertThat(getInstant(2022, 10, 23).toEpochMilli()).isEqualTo(1666483200000)
    }
}
