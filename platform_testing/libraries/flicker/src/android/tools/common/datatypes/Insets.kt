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

package android.tools.common.datatypes

import android.tools.common.withCache
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Wrapper for RectProto objects representing insets
 *
 * This class is used by flicker and Winscope
 */
@JsExport
class Insets private constructor(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) :
    Rect(left, top, right, bottom) {
    override val isEmpty: Boolean
        get() = left == 0 && top == 0 && right == 0 && bottom == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Insets) return false
        if (!super.equals(other)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + isEmpty.hashCode()
        return result
    }

    companion object {
        @JsName("EMPTY")
        val EMPTY: Insets
            get() = withCache { Insets() }

        @JsName("from")
        fun from(left: Int, top: Int, right: Int, bottom: Int): Insets = withCache {
            Insets(left, top, right, bottom)
        }
    }
}
