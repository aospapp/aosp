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
 * Wrapper for Color3 (frameworks/native/services/surfaceflinger/layerproto/transactions.proto)
 *
 * This class is used by flicker and Winscope
 */
@JsExport
open class Color3(val r: Float, val g: Float, val b: Float) {
    @JsName("isEmpty")
    open val isEmpty: Boolean
        get() = r < 0 || g < 0 || b < 0

    @JsName("isNotEmpty")
    open val isNotEmpty: Boolean
        get() = !isEmpty

    @JsName("prettyPrint")
    open fun prettyPrint(): String {
        val r = FloatFormatter.format(r)
        val g = FloatFormatter.format(g)
        val b = FloatFormatter.format(b)
        return "r:$r g:$g b:$b"
    }

    override fun toString(): String = if (isEmpty) "[empty]" else prettyPrint()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Color3) return false

        if (r != other.r) return false
        if (g != other.g) return false
        if (b != other.b) return false

        return true
    }

    override fun hashCode(): Int {
        var result = r.hashCode()
        result = 31 * result + g.hashCode()
        result = 31 * result + b.hashCode()
        return result
    }

    companion object {
        @JsName("EMPTY")
        val EMPTY: Color3
            get() = withCache { Color3(r = -1f, g = -1f, b = -1f) }
    }
}
