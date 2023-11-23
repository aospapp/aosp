/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm.traces.common

data class Color(val r: Float, val g: Float, val b: Float, val a: Float) {
    val isEmpty: Boolean
        get() = a == 0f || r < 0 || g < 0 || b < 0

    val isNotEmpty: Boolean
        get() = !isEmpty

    fun prettyPrint(): String = prettyPrint(this)

    override fun toString(): String = if (isEmpty) "[empty]" else prettyPrint()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Color) return false

        if (r != other.r) return false
        if (g != other.g) return false
        if (b != other.b) return false
        if (a != other.a) return false

        return true
    }

    override fun hashCode(): Int {
        var result = r.hashCode()
        result = 31 * result + g.hashCode()
        result = 31 * result + b.hashCode()
        result = 31 * result + a.hashCode()
        return result
    }

    companion object {
        val EMPTY = Color(r = -1f, g = -1f, b = -1f, a = 0f)

        fun prettyPrint(color: Color): String {
            val r = FloatFormatter.format(color.r)
            val g = FloatFormatter.format(color.g)
            val b = FloatFormatter.format(color.b)
            val a = FloatFormatter.format(color.a)
            return "r:$r g:$g b:$b a:$a"
        }
    }
}