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
package com.android.healthconnect.controller.utils

import android.content.Context
import android.text.format.DateFormat.*
import com.android.healthconnect.controller.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject

/** Formatter for printing time and time ranges. */
class LocalDateTimeFormatter @Inject constructor(@ApplicationContext private val context: Context) {

    private val timeFormat by lazy { getTimeFormat(context) }
    private val longDateFormat by lazy { getLongDateFormat(context) }

    /** Returns localized time. */
    fun formatTime(instant: Instant): String {
        return timeFormat.format(instant.toEpochMilli())
    }

    /** Returns localized long versions of date. */
    fun formatLongDate(instant: Instant): String {
        return longDateFormat.format(instant.toEpochMilli())
    }

    /** Returns localized time range. */
    fun formatTimeRange(start: Instant, end: Instant): String {
        return context.getString(R.string.time_range, formatTime(start), formatTime(end))
    }

    /** Returns accessible and localized time range. */
    fun formatTimeRangeA11y(start: Instant, end: Instant): String {
        return context.getString(R.string.time_range_long, formatTime(start), formatTime(end))
    }
}
