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
class Matrix33
private constructor(
    dsdx: Float = 0F,
    dtdx: Float = 0F,
    @JsName("tx") val tx: Float = 0F,
    dsdy: Float = 0F,
    dtdy: Float = 0F,
    @JsName("ty") val ty: Float = 0F
) : Matrix22(dsdx, dtdx, dsdy, dtdy) {
    override fun prettyPrint(): String {
        val parentPrint = super.prettyPrint()
        val tx = FloatFormatter.format(dsdx)
        val ty = FloatFormatter.format(dtdx)
        return "$parentPrint   tx:$tx   ty:$ty"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix33) return false
        if (!super.equals(other)) return false

        if (tx != other.tx) return false
        if (ty != other.ty) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + tx.hashCode()
        result = 31 * result + ty.hashCode()
        return result
    }

    companion object {
        val EMPTY: Matrix33
            get() = withCache { from(dsdx = 0f, dtdx = 0f, tx = 0f, dsdy = 0f, dtdy = 0f, ty = 0f) }

        @JsName("identity")
        fun identity(x: Float, y: Float): Matrix33 = withCache {
            from(dsdx = 1f, dtdx = 0f, x, dsdy = 0f, dtdy = 1f, y)
        }

        @JsName("rot270")
        fun rot270(x: Float, y: Float): Matrix33 = withCache {
            from(dsdx = 0f, dtdx = -1f, x, dsdy = 1f, dtdy = 0f, y)
        }

        @JsName("rot180")
        fun rot180(x: Float, y: Float): Matrix33 = withCache {
            from(dsdx = -1f, dtdx = 0f, x, dsdy = 0f, dtdy = -1f, y)
        }

        @JsName("rot90")
        fun rot90(x: Float, y: Float): Matrix33 = withCache {
            from(dsdx = 0f, dtdx = 1f, x, dsdy = -1f, dtdy = 0f, y)
        }

        @JsName("from")
        fun from(
            dsdx: Float,
            dtdx: Float,
            tx: Float,
            dsdy: Float,
            dtdy: Float,
            ty: Float
        ): Matrix33 = withCache { Matrix33(dsdx, dtdx, tx, dsdy, dtdy, ty) }
    }
}
