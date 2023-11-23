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
 * Wrapper for PointProto (frameworks/base/core/proto/android/graphics/point.proto)
 *
 * This class is used by flicker and Winscope
 */
@JsExport
class Point private constructor(val x: Int, val y: Int) {
    @JsName("prettyPrint") fun prettyPrint(): String = "($x, $y)"

    override fun toString(): String = prettyPrint()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Point) return false

        if (x != other.x) return false
        if (y != other.y) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        return result
    }

    companion object {
        @JsName("EMPTY")
        val EMPTY: Point
            get() = withCache { Point(x = 0, y = 0) }

        @JsName("from") fun from(x: Int, y: Int): Point = withCache { Point(x, y) }
    }
}
