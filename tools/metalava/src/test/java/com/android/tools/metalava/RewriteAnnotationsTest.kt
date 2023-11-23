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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.text.Charsets.UTF_8

class RewriteAnnotationsTest : DriverTest() {
    @Test
    fun `Test copying private annotations from one of the stubs`() {
        val source = File("stub-annotations")
        assertTrue(source.path, source.isDirectory)
        val target = temporaryFolder.newFolder()
        runDriver(
            ARG_NO_COLOR,
            ARG_NO_BANNER,

            ARG_COPY_ANNOTATIONS,
            source.path,
            target.path,

            ARG_CLASS_PATH,
            getAndroidJar().path
        )
        // Source retention explicitly listed: Shouldn't exist
        val nullable = File(target, "android/annotation/SdkConstant.java")
        assertFalse("${nullable.path} exists", nullable.isFile)

        // Source retention androidx: Shouldn't exist
        val nonNull = File(target, "androidx/annotation/NonNull.java")
        assertFalse("${nonNull.path} exists", nonNull.isFile)

        // Class retention: Should be converted

        val recentlyNull = File(target, "androidx/annotation/RecentlyNullable.java")
        assertTrue("${recentlyNull.path} doesn't exist", recentlyNull.isFile)
        assertEquals(
            """
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
            package androidx.annotation;

            import static java.lang.annotation.ElementType.FIELD;
            import static java.lang.annotation.ElementType.METHOD;
            import static java.lang.annotation.ElementType.PARAMETER;
            import static java.lang.annotation.ElementType.TYPE_USE;
            import static java.lang.annotation.RetentionPolicy.CLASS;

            import java.lang.annotation.Retention;
            import java.lang.annotation.Target;

            /** Stub only annotation. Do not use directly. */
            @Retention(CLASS)
            @Target({METHOD, PARAMETER, FIELD})
            @interface RecentlyNullable {}
            """.trimIndent().trim(),
            recentlyNull.readText(UTF_8).trim().replace("\r\n", "\n")
        )
    }

    @Test
    fun `Test stub-annotations containing unknown annotation`() {
        val source = temporaryFolder.newFolder()
        File("stub-annotations").copyRecursively(source)
        assertTrue(source.path, source.isDirectory)
        val target = temporaryFolder.newFolder()

        val fooSource =
            """
            package android.annotation;

            import static java.lang.annotation.ElementType.FIELD;
            import static java.lang.annotation.ElementType.METHOD;
            import static java.lang.annotation.ElementType.PARAMETER;
            import static java.lang.annotation.RetentionPolicy.SOURCE;

            import java.lang.annotation.Retention;
            import java.lang.annotation.Target;

            /** Stub only annotation. Do not use directly. */
            @Retention(SOURCE)
            @Target({METHOD, PARAMETER, FIELD})
            public @interface Foo {}
            """

        File(source, "src/main/java/android/annotation/Unknown.java").writeText(fooSource)
        assertThrows(IllegalStateException::class.java) {
            runDriver(
                ARG_NO_COLOR,
                ARG_NO_BANNER,

                ARG_COPY_ANNOTATIONS,
                source.path,
                target.path,

                ARG_CLASS_PATH,
                getAndroidJar().path
            )
        }
    }
}
