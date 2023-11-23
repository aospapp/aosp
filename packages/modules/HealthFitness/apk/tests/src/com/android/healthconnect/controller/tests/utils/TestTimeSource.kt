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

import com.android.healthconnect.controller.utils.TimeSource
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset.UTC

/** Time source for testing purposes. */
object TestTimeSource : TimeSource {
    override fun currentTimeMillis(): Long = NOW.toEpochMilli()

    override fun deviceZoneOffset(): ZoneId = UTC

    override fun currentLocalDateTime(): LocalDateTime =
        Instant.ofEpochMilli(currentTimeMillis()).atZone(deviceZoneOffset()).toLocalDateTime()
}
