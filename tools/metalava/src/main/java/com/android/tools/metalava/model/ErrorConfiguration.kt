/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.metalava.model

import com.android.tools.metalava.Severity
import com.android.tools.metalava.doclava1.Errors

/** An error configuration is a set of overrides for severities for various [Errors.Error] */
class ErrorConfiguration {
    private val overrides = mutableMapOf<Errors.Error, Severity>()

    /** Returns the severity of the given issue */
    fun getSeverity(error: Errors.Error): Severity {
        return overrides[error] ?: error.level
    }

    private fun setSeverity(error: Errors.Error, severity: Severity) {
        overrides[error] = severity
    }

    /** Set the severity of the given issue to [Severity.ERROR] */
    fun error(error: Errors.Error) {
        setSeverity(error, Severity.ERROR)
    }

    /** Set the severity of the given issue to [Severity.HIDDEN] */
    fun hide(error: Errors.Error) {
        setSeverity(error, Severity.HIDDEN)
    }
}

/** Default error configuration: uses all the severities initialized in the [Errors] class */
val defaultConfiguration = ErrorConfiguration()

/** Current configuration to apply when reporting errors */
var configuration = defaultConfiguration
