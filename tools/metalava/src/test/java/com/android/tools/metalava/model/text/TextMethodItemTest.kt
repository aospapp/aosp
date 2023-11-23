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

package com.android.tools.metalava.model.text

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TextMethodItemTest {
    @Test
    fun `text method item return type is non-null`() {
        val codebase = ApiFile.parseApi(
            "test",
            """
            package test.pkg {
              public class Foo {
                ctor public Foo();
                method public void bar();
              }
            }
            """.trimIndent(),
            false
        )

        val cls = codebase.findClass("test.pkg.Foo")!!
        val ctorItem = cls.findMethod("Foo", "")!!
        val methodItem = cls.findMethod("bar", "")!!

        assertNotNull(ctorItem.returnType())
        assertEquals("test.pkg.Foo", ctorItem.returnType().toString(), "Return type of the constructor item must be the containing class.")
        assertNotNull(methodItem.returnType())
        assertEquals("void", methodItem.returnType().toString(), "Return type of an method item should match the expected value.")
    }
}
