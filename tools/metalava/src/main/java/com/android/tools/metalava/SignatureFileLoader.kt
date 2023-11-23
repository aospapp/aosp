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

package com.android.tools.metalava

import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.text.ApiFile
import com.android.tools.metalava.model.text.ApiParseException
import java.io.File

object SignatureFileLoader {
    private val map = mutableMapOf<File, Codebase>()

    fun load(file: File, kotlinStyleNulls: Boolean = false): Codebase {
        return map[file] ?: run {
            val loaded = loadFiles(listOf(file), kotlinStyleNulls)
            map[file] = loaded
            loaded
        }
    }

    fun loadFiles(files: List<File>, kotlinStyleNulls: Boolean = false): Codebase {
        require(files.isNotEmpty()) { "files must not be empty" }

        try {
            val codebase = ApiFile.parseApi(files, kotlinStyleNulls)

            // Unlike loadFromSources, analyzer methods are not required for text based codebase
            // because all methods in the API text file belong to an API surface.
            val analyzer = ApiAnalyzer(codebase)
            analyzer.addConstructors { _ -> true }
            return codebase
        } catch (ex: ApiParseException) {
            throw DriverException("Unable to parse signature file: ${ex.message}")
        }
    }
}
