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

import android.tools.common.FloatFormatter
import android.tools.common.withCache
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Representation of a matrix 3x3 used for layer transforms
 *
 * ```
 *          |dsdx dsdy  tx|
 * ```
 *
 * matrix = |dtdx dtdy ty|
 *
 * ```
 *          |0    0     1 |
 * ```
 */
@JsExport
open class Matrix22(
    @JsName("dsdx") val dsdx: Float,
    @JsName("dtdx") val dtdx: Float,
    @JsName("dsdy") val dsdy: Float,
    @JsName("dtdy") val dtdy: Float
) {
    @JsName("prettyPrint")
    open fun prettyPrint(): String {
        val dsdx = FloatFormatter.format(dsdx)
        val dtdx = FloatFormatter.format(dtdx)
        val dsdy = FloatFormatter.format(dsdy)
        val dtdy = FloatFormatter.format(dtdy)
        return "dsdx:$dsdx   dtdx:$dtdx   dsdy:$dsdy   dtdy:$dtdy"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix22) return false

        if (dsdx != other.dsdx) return false
        if (dtdx != other.dtdx) return false
        if (dsdy != other.dsdy) return false
        if (dtdy != other.dtdy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dsdx.hashCode()
        result = 31 * result + dtdx.hashCode()
        result = 31 * result + dsdy.hashCode()
        result = 31 * result + dtdy.hashCode()
        return result
    }

    override fun toString(): String = prettyPrint()

    companion object {
        @JsName("EMPTY")
        val EMPTY: Matrix22
            get() = withCache { Matrix22(0f, 0f, 0f, 0f) }
    }
}
