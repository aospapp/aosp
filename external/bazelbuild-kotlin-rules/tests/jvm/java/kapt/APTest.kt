/*
 * * Copyright 2022 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kapt

import com.google.testing.compile.Compilation
import com.google.testing.compile.CompilationSubject.assertThat
import com.google.testing.compile.Compiler.javac
import com.google.testing.compile.JavaFileObjects
import javax.tools.JavaFileObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Sample test for [AP] that uses `com.google.testing.compile` (b/199411692). */
@RunWith(JUnit4::class)
class APTest {
  @Test
  fun testKTest() {
    // This is the stub file kapt generates for KTest.kt as of kotlinc 1.5.31, with the original
    // Kotlin module name shortened.
    // Alternatively our test could run kapt to get a fresh stub file, but on the other hand we
    // can test with a particular stub file this way, which may be useful for some regression tests.
    val testStub = JavaFileObjects.forSourceString(
      /* fullyQualifiedName= */ "kapt.KTest",
      """
        |package kapt;
    
        |import java.lang.System;
    
        |@kotlin.Metadata(mv = {1, 5, 1}, k = 1, d1 = {"\u0000\f\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\b\u0007\u0018\u00002\u00020\u0001B\u0005\u00a2\u0006\u0002\u0010\u0002\u00a8\u0006\u0003"}, d2 = {"Lkapt/KTest;", "", "()V", "shortened.java.kapt_test_kapt"})
        |@Count(value = 3, clazz = KTest_2_1_0.class)
        |public final class KTest {
        |    
        |    public KTest() {
        |        super();
        |    }
        |}
        """.trimMargin()
    )
    val compilation: Compilation = javac().withProcessors(AP()).compile(testStub)
    assertThat(compilation).succeededWithoutWarnings()
    assertThat(compilation).generatedSourceFile("kapt.KTest_2")
    assertThat(compilation).generatedSourceFile("kapt.KTest_2_1")
    assertThat(compilation).generatedSourceFile("kapt.KTest_2_1_0")
  }
}
