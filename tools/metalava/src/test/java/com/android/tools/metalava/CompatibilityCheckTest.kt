/*
 * Copyright (C) 2017 The Android Open Source Project
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

import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.text.Charsets.UTF_8

class
CompatibilityCheckTest : DriverTest() {
    @Test
    fun `Change between class and interface`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:2: error: Class test.pkg.MyTest1 changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:4: error: Class test.pkg.MyTest2 changed class/interface declaration [ChangedClass]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class MyTest1 {
                  }
                  public interface MyTest2 {
                  }
                  public class MyTest3 {
                  }
                  public interface MyTest4 {
                  }
                }
                """,
            // MyTest1 and MyTest2 reversed from class to interface or vice versa, MyTest3 and MyTest4 unchanged
            signatureSource = """
                package test.pkg {
                  public interface MyTest1 {
                  }
                  public class MyTest2 {
                  }
                  public class MyTest3 {
                  }
                  public interface MyTest4 {
                  }
                }
                """
        )
    }

    @Test
    fun `Interfaces should not be dropped`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:2: error: Class test.pkg.MyTest1 changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:4: error: Class test.pkg.MyTest2 changed class/interface declaration [ChangedClass]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class MyTest1 {
                  }
                  public interface MyTest2 {
                  }
                  public class MyTest3 {
                  }
                  public interface MyTest4 {
                  }
                }
                """,
            // MyTest1 and MyTest2 reversed from class to interface or vice versa, MyTest3 and MyTest4 unchanged
            signatureSource = """
                package test.pkg {
                  public interface MyTest1 {
                  }
                  public class MyTest2 {
                  }
                  public class MyTest3 {
                  }
                  public interface MyTest4 {
                  }
                }
                """
        )
    }

    @Test
    fun `Ensure warnings for removed APIs`() {
        check(
            expectedIssues = """
                TESTROOT/released-api.txt:3: error: Removed method test.pkg.MyTest1.method(Float) [RemovedMethod]
                TESTROOT/released-api.txt:4: error: Removed field test.pkg.MyTest1.field [RemovedField]
                TESTROOT/released-api.txt:6: error: Removed class test.pkg.MyTest2 [RemovedClass]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class MyTest1 {
                    method public Double method(Float);
                    field public Double field;
                  }
                  public class MyTest2 {
                    method public Double method(Float);
                    field public Double field;
                  }
                }
                package test.pkg.other {
                }
                """,
            signatureSource = """
                package test.pkg {
                  public class MyTest1 {
                  }
                }
                """
        )
    }

    @Test
    fun `Flag invalid nullness changes`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:5: error: Attempted to remove @Nullable annotation from method test.pkg.MyTest.convert3(Float) [InvalidNullConversion]
                TESTROOT/load-api.txt:5: error: Attempted to remove @Nullable annotation from parameter arg1 in test.pkg.MyTest.convert3(Float arg1) [InvalidNullConversion]
                TESTROOT/load-api.txt:6: error: Attempted to remove @NonNull annotation from method test.pkg.MyTest.convert4(Float) [InvalidNullConversion]
                TESTROOT/load-api.txt:6: error: Attempted to remove @NonNull annotation from parameter arg1 in test.pkg.MyTest.convert4(Float arg1) [InvalidNullConversion]
                TESTROOT/load-api.txt:7: error: Attempted to change parameter from @Nullable to @NonNull: incompatible change for parameter arg1 in test.pkg.MyTest.convert5(Float arg1) [InvalidNullConversion]
                TESTROOT/load-api.txt:8: error: Attempted to change method return from @NonNull to @Nullable: incompatible change for method test.pkg.MyTest.convert6(Float) [InvalidNullConversion]
                """,
            outputKotlinStyleNulls = false,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class MyTest {
                    method public Double convert1(Float);
                    method public Double convert2(Float);
                    method @Nullable public Double convert3(@Nullable Float);
                    method @NonNull public Double convert4(@NonNull Float);
                    method @Nullable public Double convert5(@Nullable Float);
                    method @NonNull public Double convert6(@NonNull Float);
                    // booleans cannot reasonably be annotated with @Nullable/@NonNull but
                    // the compiler accepts it and we had a few of these accidentally annotated
                    // that way in API 28, such as Boolean.getBoolean. Make sure we don't flag
                    // these as incompatible changes when they're dropped.
                    method public void convert7(@NonNull boolean);
                  }
                }
                """,
            // Changes: +nullness, -nullness, nullable->nonnull, nonnull->nullable
            signatureSource = """
                package test.pkg {
                  public class MyTest {
                    method @Nullable public Double convert1(@Nullable Float);
                    method @NonNull public Double convert2(@NonNull Float);
                    method public Double convert3(Float);
                    method public Double convert4(Float);
                    method @NonNull public Double convert5(@NonNull Float);
                    method @Nullable public Double convert6(@Nullable Float);
                    method public void convert7(boolean);
                  }
                }
                """
        )
    }

    @Test
    fun `Kotlin Nullness`() {
        check(
            expectedIssues = """
                src/test/pkg/Outer.kt:5: error: Attempted to change method return from @NonNull to @Nullable: incompatible change for method test.pkg.Outer.method2(String,String) [InvalidNullConversion]
                src/test/pkg/Outer.kt:5: error: Attempted to change parameter from @Nullable to @NonNull: incompatible change for parameter string in test.pkg.Outer.method2(String string, String maybeString) [InvalidNullConversion]
                src/test/pkg/Outer.kt:6: error: Attempted to change parameter from @Nullable to @NonNull: incompatible change for parameter string in test.pkg.Outer.method3(String maybeString, String string) [InvalidNullConversion]
                src/test/pkg/Outer.kt:8: error: Attempted to change method return from @NonNull to @Nullable: incompatible change for method test.pkg.Outer.Inner.method2(String,String) [InvalidNullConversion]
                src/test/pkg/Outer.kt:8: error: Attempted to change parameter from @Nullable to @NonNull: incompatible change for parameter string in test.pkg.Outer.Inner.method2(String string, String maybeString) [InvalidNullConversion]
                src/test/pkg/Outer.kt:9: error: Attempted to change parameter from @Nullable to @NonNull: incompatible change for parameter string in test.pkg.Outer.Inner.method3(String maybeString, String string) [InvalidNullConversion]
                """,
            inputKotlinStyleNulls = true,
            outputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                    package test.pkg {
                      public final class Outer {
                        ctor public Outer();
                        method public final String? method1(String, String?);
                        method public final String method2(String?, String);
                        method public final String? method3(String, String?);
                      }
                      public static final class Outer.Inner {
                        ctor public Outer.Inner();
                        method public final String method2(String?, String);
                        method public final String? method3(String, String?);
                      }
                    }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    class Outer {
                        fun method1(string: String, maybeString: String?): String? = null
                        fun method2(string: String, maybeString: String?): String? = null
                        fun method3(maybeString: String?, string : String): String = ""
                        class Inner {
                            fun method2(string: String, maybeString: String?): String? = null
                            fun method3(maybeString: String?, string : String): String = ""
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Java Parameter Name Change`() {
        check(
            expectedIssues = """
                src/test/pkg/JavaClass.java:6: error: Attempted to remove parameter name from parameter newName in test.pkg.JavaClass.method1 [ParameterNameChange]
                src/test/pkg/JavaClass.java:7: error: Attempted to change parameter name from secondParameter to newName in method test.pkg.JavaClass.method2 [ParameterNameChange]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class JavaClass {
                    ctor public JavaClass();
                    method public String method1(String parameterName);
                    method public String method2(String firstParameter, String secondParameter);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    @Suppress("all")
                    package test.pkg;
                    import androidx.annotation.ParameterName;

                    public class JavaClass {
                        public String method1(String newName) { return null; }
                        public String method2(@ParameterName("firstParameter") String s, @ParameterName("newName") String prevName) { return null; }
                    }
                    """
                ),
                supportParameterName
            ),
            extraArguments = arrayOf(ARG_HIDE_PACKAGE, "androidx.annotation")
        )
    }

    @Test
    fun `Kotlin Parameter Name Change`() {
        check(
            expectedIssues = """
                src/test/pkg/KotlinClass.kt:4: error: Attempted to change parameter name from prevName to newName in method test.pkg.KotlinClass.method1 [ParameterNameChange]
                """,
            inputKotlinStyleNulls = true,
            outputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class KotlinClass {
                    ctor public KotlinClass();
                    method public final String? method1(String prevName);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    class KotlinClass {
                        fun method1(newName: String): String? = null
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Kotlin Coroutines`() {
        check(
            expectedIssues = "",
            inputKotlinStyleNulls = true,
            outputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class TestKt {
                    ctor public TestKt();
                    method public static suspend inline java.lang.Object hello(kotlin.coroutines.experimental.Continuation<? super kotlin.Unit>);
                  }
                }
                """,
            signatureSource = """
                package test.pkg {
                  public final class TestKt {
                    ctor public TestKt();
                    method public static suspend inline Object hello(@NonNull kotlin.coroutines.Continuation<? super kotlin.Unit> p);
                  }
                }
                """
        )
    }

    @Test
    fun `Remove operator`() {
        check(
            expectedIssues = """
                src/test/pkg/Foo.kt:4: error: Cannot remove `operator` modifier from method test.pkg.Foo.plus(String): Incompatible change [OperatorRemoval]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo();
                    method public final operator void plus(String s);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    class Foo {
                        fun plus(s: String) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Remove vararg`() {
        check(
            expectedIssues = """
                src/test/pkg/test.kt:3: error: Changing from varargs to array is an incompatible change: parameter x in test.pkg.TestKt.method2(int[] x) [VarargRemoval]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class TestKt {
                    method public static final void method1(int[] x);
                    method public static final void method2(int... x);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg
                    fun method1(vararg x: Int) { }
                    fun method2(x: IntArray) { }
                    """
                )
            )
        )
    }

    @Test
    fun `Add final`() {
        // Adding final on class or method is incompatible; adding it on a parameter is fine.
        // Field is iffy.
        check(
            expectedIssues = """
                src/test/pkg/Java.java:4: error: Method test.pkg.Java.method has added 'final' qualifier [AddedFinal]
                src/test/pkg/Kotlin.kt:4: error: Method test.pkg.Kotlin.method has added 'final' qualifier [AddedFinal]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Java {
                    method public void method(int);
                  }
                  public class Kotlin {
                    ctor public Kotlin();
                    method public void method(String s);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    open class Kotlin {
                        fun method(s: String) { }
                    }
                    """
                ),
                java(
                    """
                        package test.pkg;
                        public class Java {
                            private Java() { }
                            public final void method(final int parameter) { }
                        }
                        """
                )
            )
        )
    }

    @Test
    fun `Inherited final`() {
        // Make sure that we correctly compare effectively final (inherited from surrounding class)
        // between the signature file codebase and the real codebase
        check(
            expectedIssues = """
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class Cls extends test.pkg.Parent {
                  }
                  public class Parent {
                    method public void method(int);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                        package test.pkg;
                        public final class Cls extends Parent {
                            private Cls() { }
                            @Override public void method(final int parameter) { }
                        }
                        """
                ),
                java(
                    """
                        package test.pkg;
                        public class Parent {
                            private Parent() { }
                            public void method(final int parameter) { }
                        }
                        """
                )
            )
        )
    }

    @Test
    fun `Implicit concrete`() {
        // Doclava signature files sometimes leave out overridden methods of
        // abstract methods. We don't want to list these as having changed
        // their abstractness.
        check(
            expectedIssues = """
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class Cls extends test.pkg.Parent {
                  }
                  public class Parent {
                    method public abstract void method(int);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                        package test.pkg;
                        public final class Cls extends Parent {
                            private Cls() { }
                            @Override public void method(final int parameter) { }
                        }
                        """
                ),
                java(
                    """
                        package test.pkg;
                        public class Parent {
                            private Parent() { }
                            public abstract void method(final int parameter);
                        }
                        """
                )
            )
        )
    }

    @Test
    fun `Implicit modifiers from inherited super classes`() {
        check(
            expectedIssues = """
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class Cls implements test.pkg.Interface {
                    method public void method(int);
                    method public final void method2(int);
                  }
                  public interface Interface {
                    method public void method2(int);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                        package test.pkg;
                        public final class Cls extends HiddenParent implements Interface {
                            private Cls() { }
                            @Override public void method(final int parameter) { }
                        }
                        """
                ),
                java(
                    """
                        package test.pkg;
                        class HiddenParent {
                            private HiddenParent() { }
                            public abstract void method(final int parameter) { }
                            public final void method2(final int parameter) { }
                        }
                        """
                ),
                java(
                    """
                        package test.pkg;
                        public interface Interface {
                            void method2(final int parameter) { }
                        }
                        """
                )
            )
        )
    }

    @Test
    fun `Wildcard comparisons`() {
        // Doclava signature files sometimes leave out overridden methods of
        // abstract methods. We don't want to list these as having changed
        // their abstractness.
        check(
            expectedIssues = """
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class AbstractMap<K, V> implements java.util.Map {
                    method public java.util.Set<K> keySet();
                    method public V put(K, V);
                    method public void putAll(java.util.Map<? extends K, ? extends V>);
                  }
                  public abstract class EnumMap<K extends java.lang.Enum<K>, V> extends test.pkg.AbstractMap  {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                        package test.pkg;
                        @SuppressWarnings({"ConstantConditions", "NullableProblems"})
                        public abstract class AbstractMap<K, V> implements java.util.Map {
                            private AbstractMap() { }
                            public V put(K k, V v) { return null; }
                            public java.util.Set<K> keySet() { return null; }
                            public void putAll(java.util.Map<? extends K, ? extends V> x) { }
                        }
                        """
                ),
                java(
                    """
                        package test.pkg;
                        public abstract class EnumMap<K extends java.lang.Enum<K>, V> extends test.pkg.AbstractMap  {
                            private EnumMap() { }
                            public V put(K k, V v) { return null; }
                        }
                        """
                )
            )
        )
    }

    @Test
    fun `Added constructor`() {
        // Regression test for issue 116619591
        check(
            expectedIssues = "",
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class AbstractMap<K, V> implements java.util.Map {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                        package test.pkg;
                        @SuppressWarnings({"ConstantConditions", "NullableProblems"})
                        public abstract class AbstractMap<K, V> implements java.util.Map {
                        }
                        """
                )
            )
        )
    }

    @Test
    fun `Remove infix`() {
        check(
            expectedIssues = """
                src/test/pkg/Foo.kt:5: error: Cannot remove `infix` modifier from method test.pkg.Foo.add2(String): Incompatible change [InfixRemoval]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo();
                    method public final void add1(String s);
                    method public final infix void add2(String s);
                    method public final infix void add3(String s);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    class Foo {
                        infix fun add1(s: String) { }
                        fun add2(s: String) { }
                        infix fun add3(s: String) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Add seal`() {
        check(
            expectedIssues = """
                src/test/pkg/Foo.kt:2: error: Cannot add 'sealed' modifier to class test.pkg.Foo: Incompatible change [AddSealed]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Foo {
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg
                    sealed class Foo
                    """
                )
            )
        )
    }

    @Test
    fun `Remove default parameter`() {
        check(
            expectedIssues = """
                src/test/pkg/Foo.kt:3: error: Attempted to remove default value from parameter s1 in test.pkg.Foo [DefaultValueChange] [See https://s.android.com/api-guidelines#default-value-removal]
                src/test/pkg/Foo.kt:7: error: Attempted to remove default value from parameter s1 in test.pkg.Foo.method4 [DefaultValueChange] [See https://s.android.com/api-guidelines#default-value-removal]

                """,
            inputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo(String? s1 = null);
                    method public final void method1(boolean b, String? s1);
                    method public final void method2(boolean b, String? s1);
                    method public final void method3(boolean b, String? s1 = "null");
                    method public final void method4(boolean b, String? s1 = "null");
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    class Foo(s1: String?) {
                        fun method1(b: Boolean, s1: String?) { }         // No change
                        fun method2(b: Boolean, s1: String? = null) { }  // Adding: OK
                        fun method3(b: Boolean, s1: String? = null) { }  // No change
                        fun method4(b: Boolean, s1: String?) { }         // Removed
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Remove optional parameter`() {
        check(
            expectedIssues = """
                src/test/pkg/Foo.kt:3: error: Attempted to remove default value from parameter s1 in test.pkg.Foo [DefaultValueChange] [See https://s.android.com/api-guidelines#default-value-removal]
                src/test/pkg/Foo.kt:7: error: Attempted to remove default value from parameter s1 in test.pkg.Foo.method4 [DefaultValueChange] [See https://s.android.com/api-guidelines#default-value-removal]
                """,
            inputKotlinStyleNulls = true,
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo(optional String? s1);
                    method public final void method1(boolean b, String? s1);
                    method public final void method2(boolean b, String? s1);
                    method public final void method3(boolean b, optional String? s1);
                    method public final void method4(boolean b, optional String? s1);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    class Foo(s1: String?) {                             // Removed
                        fun method1(b: Boolean, s1: String?) { }         // No change
                        fun method2(b: Boolean, s1: String? = null) { }  // Adding: OK
                        fun method3(b: Boolean, s1: String? = null) { }  // No change
                        fun method4(b: Boolean, s1: String?) { }         // Removed
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Removing method or field when still available via inheritance is OK`() {
        check(
            expectedIssues = """
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Child extends test.pkg.Parent {
                    ctor public Child();
                    field public int field1;
                    method public void method1();
                  }
                  public class Parent {
                    ctor public Parent();
                    field public int field1;
                    field public int field2;
                    method public void method1();
                    method public void method2();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class Parent {
                        public int field1 = 0;
                        public int field2 = 0;
                        public void method1() { }
                        public void method2() { }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public class Child extends Parent {
                        public int field1 = 0;
                        @Override public void method1() { } // NO CHANGE
                        //@Override public void method2() { } // REMOVED OK: Still inherited
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Change field constant value, change field type`() {
        check(
            expectedIssues = """
                src/test/pkg/Parent.java:5: error: Field test.pkg.Parent.field2 has changed value from 2 to 42 [ChangedValue]
                src/test/pkg/Parent.java:6: error: Field test.pkg.Parent.field3 has changed type from int to char [ChangedType]
                src/test/pkg/Parent.java:7: error: Field test.pkg.Parent.field4 has added 'final' qualifier [AddedFinal]
                src/test/pkg/Parent.java:8: error: Field test.pkg.Parent.field5 has changed 'static' qualifier [ChangedStatic]
                src/test/pkg/Parent.java:10: error: Field test.pkg.Parent.field7 has changed 'volatile' qualifier [ChangedVolatile]
                src/test/pkg/Parent.java:20: error: Field test.pkg.Parent.field94 has changed value from 1 to 42 [ChangedValue]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Parent {
                    ctor public Parent();
                    field public static final int field1 = 1; // 0x1
                    field public static final int field2 = 2; // 0x2
                    field public int field3;
                    field public int field4 = 4; // 0x4
                    field public int field5;
                    field public int field6;
                    field public int field7;
                    field public deprecated int field8;
                    field public int field9;
                    field public static final int field91 = 1; // 0x1
                    field public static final int field92 = 1; // 0x1
                    field public static final int field93 = 1; // 0x1
                    field public static final int field94 = 1; // 0x1
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;
                    import android.annotation.SuppressLint;
                    public class Parent {
                        public static final int field1 = 1;  // UNCHANGED
                        public static final int field2 = 42; // CHANGED VALUE
                        public char field3 = 3;              // CHANGED TYPE
                        public final int field4 = 4;         // ADDED FINAL
                        public static int field5 = 5;        // ADDED STATIC
                        public transient int field6 = 6;     // ADDED TRANSIENT
                        public volatile int field7 = 7;      // ADDED VOLATILE
                        public int field8 = 8;               // REMOVED DEPRECATED
                        /** @deprecated */ @Deprecated public int field9 = 8;  // ADDED DEPRECATED
                        @SuppressLint("ChangedValue")
                        public static final int field91 = 42;// CHANGED VALUE: Suppressed
                        @SuppressLint("ChangedValue:Field test.pkg.Parent.field92 has changed value from 1 to 42")
                        public static final int field92 = 42;// CHANGED VALUE: Suppressed with same message
                        @SuppressLint("ChangedValue: Field test.pkg.Parent.field93 has changed value from 1 to 42")
                        public static final int field93 = 42;// CHANGED VALUE: Suppressed with same message
                        @SuppressLint("ChangedValue:Field test.pkg.Parent.field94 has changed value from 10 to 1")
                        public static final int field94 = 42;// CHANGED VALUE: Suppressed but with different message
                    }
                    """
                ),
                suppressLintSource
            ),
            extraArguments = arrayOf(ARG_HIDE_PACKAGE, "android.annotation")
        )
    }

    @Test
    fun `Change annotation default method value change`() {
        check(
            inputKotlinStyleNulls = true,
            expectedIssues = """
                src/test/pkg/ExportedProperty.java:15: error: Method test.pkg.ExportedProperty.category has changed value from "" to nothing [ChangedValue]
                src/test/pkg/ExportedProperty.java:14: error: Method test.pkg.ExportedProperty.floating has changed value from 1.0f to 1.1f [ChangedValue]
                src/test/pkg/ExportedProperty.java:13: error: Method test.pkg.ExportedProperty.prefix has changed value from "" to "hello" [ChangedValue]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public @interface ExportedProperty {
                    method public abstract boolean resolveId() default false;
                    method public abstract float floating() default 1.0f;
                    method public abstract String! prefix() default "";
                    method public abstract String! category() default "";
                    method public abstract boolean formatToHexString();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    import static java.lang.annotation.RetentionPolicy.SOURCE;

                    @Target({ElementType.FIELD, ElementType.METHOD})
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface ExportedProperty {
                        boolean resolveId() default false;            // UNCHANGED
                        String prefix() default "hello";              // CHANGED VALUE
                        float floating() default 1.1f;                // CHANGED VALUE
                        String category();                            // REMOVED VALUE
                        boolean formatToHexString() default false;    // ADDED VALUE
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible class change -- class to interface`() {
        check(
            expectedIssues = """
                src/test/pkg/Parent.java:3: error: Class test.pkg.Parent changed class/interface declaration [ChangedClass]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Parent {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public interface Parent {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible class change -- change implemented interfaces`() {
        check(
            expectedIssues = """
                src/test/pkg/Parent.java:3: error: Class test.pkg.Parent no longer implements java.io.Closeable [RemovedInterface]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class Parent implements java.io.Closeable, java.util.Map {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class Parent implements java.util.Map, java.util.List {
                        private Parent() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible class change -- change qualifiers`() {
        check(
            expectedIssues = """
                src/test/pkg/Parent.java:3: error: Class test.pkg.Parent changed 'abstract' qualifier [ChangedAbstract]
                src/test/pkg/Parent.java:3: error: Class test.pkg.Parent changed 'static' qualifier [ChangedStatic]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Parent {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract static class Parent {
                        private Parent() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible class change -- final`() {
        check(
            expectedIssues = """
                src/test/pkg/Class1.java:3: error: Class test.pkg.Class1 added 'final' qualifier [AddedFinal]
                TESTROOT/released-api.txt:3: error: Removed constructor test.pkg.Class1() [RemovedMethod]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Class1 {
                      ctor public Class1();
                  }
                  public class Class2 {
                  }
                  public final class Class3 {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public final class Class1 {
                        private Class1() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public final class Class2 {
                        private Class2() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public class Class3 {
                        private Class3() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible class change -- visibility`() {
        check(
            expectedIssues = """
                src/test/pkg/Class1.java:3: error: Class test.pkg.Class1 changed visibility from protected to public [ChangedScope]
                src/test/pkg/Class2.java:3: error: Class test.pkg.Class2 changed visibility from public to protected [ChangedScope]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  protected class Class1 {
                  }
                  public class Class2 {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class Class1 {
                        private Class1() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    protected class Class2 {
                        private Class2() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible class change -- superclass`() {
        check(
            expectedIssues = """
                src/test/pkg/Class3.java:3: error: Class test.pkg.Class3 superclass changed from java.lang.Char to java.lang.Number [ChangedSuperclass]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class Class1 {
                  }
                  public abstract class Class2 extends java.lang.Number {
                  }
                  public abstract class Class3 extends java.lang.Char {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class Class1 extends java.lang.Short {
                        private Class1() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public abstract class Class2 extends java.lang.Float {
                        private Class2() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public abstract class Class3 extends java.lang.Number {
                        private Class3() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `allow adding first type parameter`() {
        check(
            checkCompatibilityApiReleased = """
                package test.pkg {
                    public class Foo {
                    }
                }
            """,
            signatureSource = """
                package test.pkg {
                    public class Foo<T> {
                    }
                }
            """
        )
    }

    @Test
    fun `disallow removing type parameter`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:2: error: Class test.pkg.Foo changed number of type parameters from 1 to 0 [ChangedType]
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                    public class Foo<T> {
                    }
                }
            """,
            signatureSource = """
                package test.pkg {
                    public class Foo {
                    }
                }
            """
        )
    }

    @Test
    fun `disallow changing number of type parameters`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:2: error: Class test.pkg.Foo changed number of type parameters from 1 to 2 [ChangedType]
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                    public class Foo<A> {
                    }
                }
            """,
            signatureSource = """
                package test.pkg {
                    public class Foo<A,B> {
                    }
                }
            """
        )
    }

    @Test
    fun `Incompatible method change -- modifiers`() {
        check(
            expectedIssues = """
                src/test/pkg/MyClass.java:5: error: Method test.pkg.MyClass.myMethod2 has changed 'abstract' qualifier [ChangedAbstract]
                src/test/pkg/MyClass.java:6: error: Method test.pkg.MyClass.myMethod3 has changed 'static' qualifier [ChangedStatic]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyClass {
                      method public void myMethod2();
                      method public void myMethod3();
                      method deprecated public void myMethod4();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass {
                        private MyClass() {}
                        public native abstract void myMethod2(); // Note that Errors.CHANGE_NATIVE is hidden by default
                        public static void myMethod3() {}
                        public void myMethod4() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible method change -- final`() {
        check(
            expectedIssues = """
                src/test/pkg/Outer.java:7: error: Method test.pkg.Outer.Class1.method1 has added 'final' qualifier [AddedFinal]
                src/test/pkg/Outer.java:19: error: Method test.pkg.Outer.Class4.method4 has removed 'final' qualifier [RemovedFinal]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class Outer {
                  }
                  public class Outer.Class1 {
                    method public void method1();
                  }
                  public final class Outer.Class2 {
                    method public void method2();
                  }
                  public final class Outer.Class3 {
                    method public void method3();
                  }
                  public class Outer.Class4 {
                    method public final void method4();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class Outer {
                        private Outer() {}
                        public class Class1 {
                            private Class1() {}
                            public final void method1() { } // Added final
                        }
                        public final class Class2 {
                            private Class2() {}
                            public final void method2() { } // Added final but class is effectively final so no change
                        }
                        public final class Class3 {
                            private Class3() {}
                            public void method3() { } // Removed final but is still effectively final
                        }
                        public class Class4 {
                            private Class4() {}
                            public void method4() { } // Removed final
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible method change -- visibility`() {
        check(
            expectedIssues = """
                src/test/pkg/MyClass.java:6: error: Method test.pkg.MyClass.myMethod2 changed visibility from public to protected [ChangedScope]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyClass {
                      method protected void myMethod1();
                      method public void myMethod2();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass {
                        private MyClass() {}
                        public void myMethod1() {}
                        protected void myMethod2() {}
                    }
                    """
                )
            )
        )
    }

    @Ignore("TODO(aurimas) reenable once this is default on")
    @Test
    fun `Incompatible method change -- throws list`() {
        check(
            expectedIssues = """
                src/test/pkg/MyClass.java:7: error: Method test.pkg.MyClass.method1 added thrown exception java.io.IOException [ChangedThrows]
                src/test/pkg/MyClass.java:8: error: Method test.pkg.MyClass.method2 no longer throws exception java.io.IOException [ChangedThrows]
                src/test/pkg/MyClass.java:9: error: Method test.pkg.MyClass.method3 no longer throws exception java.io.IOException [ChangedThrows]
                src/test/pkg/MyClass.java:9: error: Method test.pkg.MyClass.method3 no longer throws exception java.lang.NumberFormatException [ChangedThrows]
                src/test/pkg/MyClass.java:9: error: Method test.pkg.MyClass.method3 added thrown exception java.lang.UnsupportedOperationException [ChangedThrows]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyClass {
                      method public void finalize() throws java.lang.Throwable;
                      method public void method1();
                      method public void method2() throws java.io.IOException;
                      method public void method3() throws java.io.IOException, java.lang.NumberFormatException;
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    @SuppressWarnings("RedundantThrows")
                    public abstract class MyClass {
                        private MyClass() {}
                        public void finalize() {}
                        public void method1() throws java.io.IOException {}
                        public void method2() {}
                        public void method3() throws java.lang.UnsupportedOperationException {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible method change -- return types`() {
        check(
            expectedIssues = """
                src/test/pkg/MyClass.java:5: error: Method test.pkg.MyClass.method1 has changed return type from float to int [ChangedType]
                src/test/pkg/MyClass.java:6: error: Method test.pkg.MyClass.method2 has changed return type from java.util.List<Number> to java.util.List<java.lang.Integer> [ChangedType]
                src/test/pkg/MyClass.java:7: error: Method test.pkg.MyClass.method3 has changed return type from java.util.List<Integer> to java.util.List<java.lang.Number> [ChangedType]
                src/test/pkg/MyClass.java:8: error: Method test.pkg.MyClass.method4 has changed return type from String to String[] [ChangedType]
                src/test/pkg/MyClass.java:9: error: Method test.pkg.MyClass.method5 has changed return type from String[] to String[][] [ChangedType]
                src/test/pkg/MyClass.java:11: error: Method test.pkg.MyClass.method7 has changed return type from T to Number [ChangedType]
                src/test/pkg/MyClass.java:13: error: Method test.pkg.MyClass.method9 has changed return type from X (extends java.lang.Throwable) to U (extends java.lang.Number) [ChangedType]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyClass<T extends Number> {
                      method public float method1();
                      method public java.util.List<Number> method2();
                      method public java.util.List<Integer> method3();
                      method public String method4();
                      method public String[] method5();
                      method public <X extends java.lang.Throwable> T method6(java.util.function.Supplier<? extends X>);
                      method public <X extends java.lang.Throwable> T method7(java.util.function.Supplier<? extends X>);
                      method public <X extends java.lang.Throwable> Number method8(java.util.function.Supplier<? extends X>);
                      method public <X extends java.lang.Throwable> X method9(java.util.function.Supplier<? extends X>);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass<U extends Number> { // Changing type variable name is fine/compatible
                        private MyClass() {}
                        public int method1() { return 0; }
                        public java.util.List<Integer> method2() { return null; }
                        public java.util.List<Number> method3() { return null; }
                        public String[] method4() { return null; }
                        public String[][] method5() { return null; }
                        public <X extends java.lang.Throwable> U method6(java.util.function.Supplier<? extends X> arg) { return null; }
                        public <X extends java.lang.Throwable> Number method7(java.util.function.Supplier<? extends X> arg) { return null; }
                        public <X extends java.lang.Throwable> U method8(java.util.function.Supplier<? extends X> arg) { return null; }
                        public <X extends java.lang.Throwable> U method9(java.util.function.Supplier<? extends X> arg) { return null; }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Incompatible field change -- visibility and removing final`() {
        check(
            expectedIssues = """
                src/test/pkg/MyClass.java:6: error: Field test.pkg.MyClass.myField2 changed visibility from public to protected [ChangedScope]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyClass {
                      field protected int myField1;
                      field public int myField2;
                      field public final int myField3;
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass {
                        private MyClass() {}
                        public int myField1 = 1;
                        protected int myField2 = 1;
                        public int myField3 = 1;
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Adding classes, interfaces and packages, and removing these`() {
        check(
            expectedIssues = """
                TESTROOT/released-api.txt:2: error: Removed class test.pkg.MyOldClass [RemovedClass]
                TESTROOT/released-api.txt:5: error: Removed package test.pkg3 [RemovedPackage]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyOldClass {
                  }
                }
                package test.pkg3 {
                  public abstract class MyOldClass {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass {
                        private MyClass() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public interface MyInterface {
                    }
                    """
                ),
                java(
                    """
                    package test.pkg2;

                    public abstract class MyClass2 {
                        private MyClass2() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test removing public constructor`() {
        check(
            expectedIssues = """
                TESTROOT/released-api.txt:3: error: Removed constructor test.pkg.MyClass() [RemovedMethod]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyClass {
                    ctor public MyClass();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass {
                        private MyClass() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test type variables from text signature files`() {
        check(
            expectedIssues = """
                src/test/pkg/MyClass.java:8: error: Method test.pkg.MyClass.myMethod4 has changed return type from S (extends java.lang.Object) to S (extends java.lang.Float) [ChangedType]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public abstract class MyClass<T extends test.pkg.Number,T_SPLITR> {
                    method public T myMethod1();
                    method public <S extends test.pkg.Number> S myMethod2();
                    method public <S> S myMethod3();
                    method public <S> S myMethod4();
                    method public java.util.List<byte[]> myMethod5();
                    method public T_SPLITR[] myMethod6();
                  }
                  public class Number {
                    ctor public Number();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass<T extends Number,T_SPLITR> {
                        private MyClass() {}
                        public T myMethod1() { return null; }
                        public <S extends Number> S myMethod2() { return null; }
                        public <S> S myMethod3() { return null; }
                        public <S extends Float> S myMethod4() { return null; }
                        public java.util.List<byte[]> myMethod5() { return null; }
                        public T_SPLITR[] myMethod6() { return null; }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;
                    public class Number {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test Kotlin extensions`() {
        check(
            inputKotlinStyleNulls = true,
            outputKotlinStyleNulls = true,
            expectedIssues = "",
            checkCompatibilityApiReleased = """
                package androidx.content {
                  public final class ContentValuesKt {
                    method public static android.content.ContentValues contentValuesOf(kotlin.Pair<String,?>... pairs);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    "src/androidx/content/ContentValues.kt",
                    """
                    package androidx.content

                    import android.content.ContentValues

                    fun contentValuesOf(vararg pairs: Pair<String, Any?>) = ContentValues(pairs.size).apply {
                        for ((key, value) in pairs) {
                            when (value) {
                                null -> putNull(key)
                                is String -> put(key, value)
                                is Int -> put(key, value)
                                is Long -> put(key, value)
                                is Boolean -> put(key, value)
                                is Float -> put(key, value)
                                is Double -> put(key, value)
                                is ByteArray -> put(key, value)
                                is Byte -> put(key, value)
                                is Short -> put(key, value)
                                else -> {
                                    val valueType = value.javaClass.canonicalName
                                    throw IllegalArgumentException("Illegal value type")
                                }
                            }
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test Kotlin type bounds`() {
        check(
            inputKotlinStyleNulls = false,
            outputKotlinStyleNulls = true,
            expectedIssues = "",
            checkCompatibilityApiReleased = """
                package androidx.navigation {
                  public final class NavDestination {
                    ctor public NavDestination();
                  }
                  public class NavDestinationBuilder<D extends androidx.navigation.NavDestination> {
                    ctor public NavDestinationBuilder(int id);
                    method public D build();
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package androidx.navigation

                    open class NavDestinationBuilder<out D : NavDestination>(
                            id: Int
                    ) {
                        open fun build(): D {
                            TODO()
                        }
                    }

                    class NavDestination
                    """
                )
            )
        )
    }

    @Test
    fun `Test inherited methods`() {
        check(
            expectedIssues = """
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Child1 extends test.pkg.Parent {
                  }
                  public class Child2 extends test.pkg.Parent {
                    method public void method0(java.lang.String, int);
                    method public void method4(java.lang.String, int);
                  }
                  public class Child3 extends test.pkg.Parent {
                    method public void method1(java.lang.String, int);
                    method public void method2(java.lang.String, int);
                  }
                  public class Parent {
                    method public void method1(java.lang.String, int);
                    method public void method2(java.lang.String, int);
                    method public void method3(java.lang.String, int);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class Child1 extends Parent {
                        private Child1() {}
                        @Override
                        public void method1(String first, int second) {
                        }
                        @Override
                        public void method2(String first, int second) {
                        }
                        @Override
                        public void method3(String first, int second) {
                        }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public class Child2 extends Parent {
                        private Child2() {}
                        @Override
                        public void method0(String first, int second) {
                        }
                        @Override
                        public void method1(String first, int second) {
                        }
                        @Override
                        public void method2(String first, int second) {
                        }
                        @Override
                        public void method3(String first, int second) {
                        }
                        @Override
                        public void method4(String first, int second) {
                        }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public class Child3 extends Parent {
                        private Child3() {}
                        @Override
                        public void method1(String first, int second) {
                        }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;
                    public class Parent {
                        private Parent() { }
                        public void method1(String first, int second) {
                        }
                        public void method2(String first, int second) {
                        }
                        public void method3(String first, int second) {
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Partial text file which references inner classes not listed elsewhere`() {
        // This happens in system and test files where we only include APIs that differ
        // from the base API. When parsing these code bases we need to gracefully handle
        // references to inner classes.
        check(
            includeSystemApiAnnotations = true,
            expectedIssues = """
                TESTROOT/released-api.txt:4: error: Removed method test.pkg.Bar.Inner1.Inner2.removedMethod() [RemovedMethod]
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package other.pkg;

                    public class MyClass {
                        public class MyInterface {
                            public void test() { }
                        }
                    }
                    """
                ).indented(),
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;

                    public class Bar {
                        public class Inner1 {
                            private Inner1() { }
                            @SuppressWarnings("JavaDoc")
                            public class Inner2 {
                                private Inner2() { }

                                /**
                                 * @hide
                                 */
                                @SystemApi
                                public void method() { }

                                /**
                                 * @hide
                                 */
                                @SystemApi
                                public void addedMethod() { }
                            }
                        }
                    }
                    """
                ),
                systemApiSource
            ),

            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.SystemApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),

            checkCompatibilityApiReleased =
            """
                package test.pkg {
                  public class Bar.Inner1.Inner2 {
                    method public void method();
                    method public void removedMethod();
                  }
                }
                """
        )
    }

    @Test
    fun `Incompatible Changes in Released System API `() {
        // Incompatible changes to a released System API should be detected
        // In this case removing final and changing value of constant
        check(
            includeSystemApiAnnotations = true,
            expectedIssues = """
                src/android/rolecontrollerservice/RoleControllerService.java:8: error: Method android.rolecontrollerservice.RoleControllerService.sendNetworkScore has removed 'final' qualifier [RemovedFinal]
                src/android/rolecontrollerservice/RoleControllerService.java:9: error: Field android.rolecontrollerservice.RoleControllerService.APP_RETURN_UNWANTED has changed value from 1 to 0 [ChangedValue]
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.rolecontrollerservice;
                    import android.annotation.SystemApi;

                    /** @hide */
                    @SystemApi
                    public abstract class RoleControllerService {
                        public abstract void onGrantDefaultRoles();
                        public void sendNetworkScore();
                        public static final int APP_RETURN_UNWANTED = 0;
                    }
                    """
                ),
                systemApiSource
            ),

            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.TestApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),

            checkCompatibilityApiReleased =
            """
                package android.rolecontrollerservice {
                  public abstract class RoleControllerService {
                    ctor public RoleControllerService();
                    method public abstract void onGrantDefaultRoles();
                    method public final void sendNetworkScore();
                    field public static final int APP_RETURN_UNWANTED = 1;
                  }
                }
                """
        )
    }

    @Test
    fun `Incompatible changes to released API signature codebase`() {
        // Incompatible changes to a released System API should be detected
        // in case of partial files
        check(
            expectedIssues = """
                TESTROOT/released-api.txt:5: error: Removed method test.pkg.Foo.method2() [RemovedMethod]
                """,
            signatureSource = """
                // Signature format: 3.0
                package test.pkg {
                  public final class Foo {
                    ctor public Foo();
                    method public void method1();
                  }
                }
                """,

            checkCompatibilityApiReleased =
            """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo();
                    method public void method1();
                    method public void method2();
                    method public void method3();
                  }
                }
                """,
            checkCompatibilityBaseApi =
            """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo();
                    method public void method3();
                  }
                }
                """,
        )
    }

    @Test
    fun `Partial text file which adds methods to show-annotation API`() {
        // This happens in system and test files where we only include APIs that differ
        // from the base IDE. When parsing these code bases we need to gracefully handle
        // references to inner classes.
        check(
            includeSystemApiAnnotations = true,
            expectedIssues = """
                TESTROOT/released-api.txt:4: error: Removed method android.rolecontrollerservice.RoleControllerService.onClearRoleHolders() [RemovedMethod]
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.rolecontrollerservice;

                    public class Service {
                    }
                    """
                ).indented(),
                java(
                    """
                    package android.rolecontrollerservice;
                    import android.annotation.SystemApi;

                    /** @hide */
                    @SystemApi
                    public abstract class RoleControllerService extends Service {
                        public abstract void onGrantDefaultRoles();
                    }
                    """
                ),
                systemApiSource
            ),

            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.TestApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),

            checkCompatibilityApiReleased =
            """
                package android.rolecontrollerservice {
                  public abstract class RoleControllerService extends android.rolecontrollerservice.Service {
                    ctor public RoleControllerService();
                    method public abstract void onClearRoleHolders();
                  }
                }
                """
        )
    }

    @Test
    fun `Partial text file where type previously did not exist`() {
        check(
            expectedIssues = """
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;

                    /**
                     * @hide
                     */
                    @SystemApi
                    public class SampleException1 extends java.lang.Exception {
                    }
                    """
                ).indented(),
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;

                    /**
                     * @hide
                     */
                    @SystemApi
                    public class SampleException2 extends java.lang.Throwable {
                    }
                    """
                ).indented(),
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;

                    /**
                     * @hide
                     */
                    @SystemApi
                    public class Utils {
                        public void method1() throws SampleException1 { }
                        public void method2() throws SampleException2 { }
                    }
                    """
                ),
                systemApiSource
            ),

            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.SystemApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),

            checkCompatibilityApiReleased =
            """
                package test.pkg {
                  public class Utils {
                    ctor public Utils();
                    // We don't define SampleException1 or SampleException in this file,
                    // in this partial signature, so we don't need to validate that they
                    // have not been changed
                    method public void method1() throws test.pkg.SampleException1;
                    method public void method2() throws test.pkg.SampleException2;
                  }
                }
                """
        )
    }

    @Test
    fun `Regression test for bug 120847535`() {
        // Regression test for
        // 120847535: check-api doesn't fail on method that is in current.txt, but marked @hide @TestApi
        check(
            expectedIssues = """
                TESTROOT/released-api.txt:6: error: Removed method test.view.ViewTreeObserver.registerFrameCommitCallback(Runnable) [RemovedMethod]
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.view;
                    import android.annotation.TestApi;
                    public final class ViewTreeObserver {
                         /**
                         * @hide
                         */
                        @TestApi
                        public void registerFrameCommitCallback(Runnable callback) {
                        }
                    }
                    """
                ).indented(),
                java(
                    """
                    package test.view;
                    public final class View {
                        private View() { }
                    }
                    """
                ).indented(),
                testApiSource
            ),

            api = """
                package test.view {
                  public final class View {
                  }
                  public final class ViewTreeObserver {
                    ctor public ViewTreeObserver();
                  }
                }
            """,
            extraArguments = arrayOf(
                ARG_HIDE_PACKAGE, "android.annotation",
            ),

            checkCompatibilityApiReleased = """
                package test.view {
                  public final class View {
                  }
                  public final class ViewTreeObserver {
                    ctor public ViewTreeObserver();
                    method public void registerFrameCommitCallback(java.lang.Runnable);
                  }
                }
                """
        )
    }

    @Test
    fun `Test release compatibility checking`() {
        // Different checks are enforced for current vs release API comparisons:
        // we don't flag AddedClasses etc. Removed classes *are* enforced.
        check(
            expectedIssues = """
                src/test/pkg/Class1.java:3: error: Class test.pkg.Class1 added 'final' qualifier [AddedFinal]
                TESTROOT/released-api.txt:3: error: Removed constructor test.pkg.Class1() [RemovedMethod]
                src/test/pkg/MyClass.java:5: error: Method test.pkg.MyClass.myMethod2 has changed 'abstract' qualifier [ChangedAbstract]
                src/test/pkg/MyClass.java:6: error: Method test.pkg.MyClass.myMethod3 has changed 'static' qualifier [ChangedStatic]
                TESTROOT/released-api.txt:14: error: Removed class test.pkg.MyOldClass [RemovedClass]
                TESTROOT/released-api.txt:17: error: Removed package test.pkg3 [RemovedPackage]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Class1 {
                      ctor public Class1();
                  }
                  public class Class2 {
                  }
                  public final class Class3 {
                  }
                  public abstract class MyClass {
                      method public void myMethod2();
                      method public void myMethod3();
                      method deprecated public void myMethod4();
                  }
                  public abstract class MyOldClass {
                  }
                }
                package test.pkg3 {
                  public abstract class MyOldClass {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public final class Class1 {
                        private Class1() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public final class Class2 {
                        private Class2() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public class Class3 {
                        private Class3() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public abstract class MyNewClass {
                        private MyNewClass() {}
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public abstract class MyClass {
                        private MyClass() {}
                        public native abstract void myMethod2(); // Note that Errors.CHANGE_NATIVE is hidden by default
                        public static void myMethod3() {}
                        public void myMethod4() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test remove deprecated API is an error`() {
        // Regression test for b/145745855
        check(
            expectedIssues = """
                TESTROOT/released-api.txt:6: error: Removed deprecated class test.pkg.DeprecatedClass [RemovedDeprecatedClass]
                TESTROOT/released-api.txt:3: error: Removed deprecated constructor test.pkg.SomeClass() [RemovedDeprecatedMethod]
                TESTROOT/released-api.txt:4: error: Removed deprecated method test.pkg.SomeClass.deprecatedMethod() [RemovedDeprecatedMethod]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class SomeClass {
                      ctor deprecated public SomeClass();
                      method deprecated public void deprecatedMethod();
                  }
                  deprecated public class DeprecatedClass {
                      ctor deprecated public DeprecatedClass();
                      method deprecated public void deprecatedMethod();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class SomeClass {
                        private SomeClass() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test check release with base api`() {
        check(
            expectedIssues = "",
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class SomeClass {
                      method public static void publicMethodA();
                      method public static void publicMethodB();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class SomeClass {
                      public static void publicMethodA();
                    }
                    """
                )
            ),
            checkCompatibilityBaseApi = """
                package test.pkg {
                  public class SomeClass {
                      method public static void publicMethodB();
                  }
                }
            """
        )
    }

    @Test
    fun `Test check a class moving from the released api to the base api`() {
        check(
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class SomeClass1 {
                    method public void method1();
                  }
                  public class SomeClass2 {
                    method public void oldMethod();
                  }
                }
                """,
            checkCompatibilityBaseApi = """
                package test.pkg {
                  public class SomeClass2 {
                    method public void newMethod();
                  }
                }
            """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class SomeClass1 {
                        public void method1();
                    }
                    """
                )
            ),
            expectedIssues = """
            TESTROOT/released-api.txt:6: error: Removed method test.pkg.SomeClass2.oldMethod() [RemovedMethod]
            """.trimIndent()
        )
    }

    @Test
    fun `Implicit nullness`() {
        check(
            inputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                // Signature format: 2.0
                package androidx.annotation {
                  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) @java.lang.annotation.Target({java.lang.annotation.ElementType.ANNOTATION_TYPE, java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.CONSTRUCTOR, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.PACKAGE}) public @interface RestrictTo {
                    method public abstract androidx.annotation.RestrictTo.Scope[] value();
                  }

                  public enum RestrictTo.Scope {
                    enum_constant @Deprecated public static final androidx.annotation.RestrictTo.Scope GROUP_ID;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope LIBRARY;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope LIBRARY_GROUP;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope LIBRARY_GROUP_PREFIX;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope SUBCLASSES;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope TESTS;
                  }
                }
                """,

            sourceFiles = arrayOf(
                restrictToSource
            )
        )
    }

    @Test
    fun `Java String constants`() {
        check(
            inputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                package androidx.browser.browseractions {
                  public class BrowserActionsIntent {
                    field public static final String EXTRA_APP_ID = "androidx.browser.browseractions.APP_ID";
                  }
                }
                """,

            sourceFiles = arrayOf(
                java(
                    """
                     package androidx.browser.browseractions;
                     public class BrowserActionsIntent {
                        private BrowserActionsIntent() { }
                        public static final String EXTRA_APP_ID = "androidx.browser.browseractions.APP_ID";

                     }
                    """
                ).indented()
            )
        )
    }

    @Test
    fun `Classes with maps`() {
        check(
            inputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                // Signature format: 2.0
                package androidx.collection {
                  public class SimpleArrayMap<K, V> {
                  }
                }
                """,

            sourceFiles = arrayOf(
                java(
                    """
                    package androidx.collection;

                    public class SimpleArrayMap<K, V> {
                        private SimpleArrayMap() { }
                    }
                    """
                ).indented()
            )
        )
    }

    @Test
    fun `Referencing type parameters in types`() {
        check(
            inputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                // Signature format: 2.0
                package androidx.collection {
                  public class MyMap<Key, Value> {
                    ctor public MyMap();
                    field public Key! myField;
                    method public Key! getReplacement(Key!);
                  }
                }
                """,

            sourceFiles = arrayOf(
                java(
                    """
                    package androidx.collection;

                    public class MyMap<Key, Value> {
                        public Key getReplacement(Key key) { return null; }
                        public Key myField = null;
                    }
                    """
                ).indented()
            )
        )
    }

    @Test
    fun `Comparing annotations with methods with v1 signature files`() {
        check(
            checkCompatibilityApiReleased = """
                package androidx.annotation {
                  public abstract class RestrictTo implements java.lang.annotation.Annotation {
                  }
                  public static final class RestrictTo.Scope extends java.lang.Enum {
                    enum_constant public static final deprecated androidx.annotation.RestrictTo.Scope GROUP_ID;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope LIBRARY;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope LIBRARY_GROUP;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope LIBRARY_GROUP_PREFIX;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope SUBCLASSES;
                    enum_constant public static final androidx.annotation.RestrictTo.Scope TESTS;
                  }
                }
                """,

            sourceFiles = arrayOf(
                restrictToSource
            )
        )
    }

    @Test
    fun `Insignificant type formatting differences`() {
        check(
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class UsageStatsManager {
                    method public java.util.Map<java.lang.String, java.lang.Integer> getAppStandbyBuckets();
                    method public void setAppStandbyBuckets(java.util.Map<java.lang.String, java.lang.Integer>);
                    field public java.util.Map<java.lang.String, java.lang.Integer> map;
                  }
                }
                """,
            signatureSource = """
                package test.pkg {
                  public final class UsageStatsManager {
                    method public java.util.Map<java.lang.String,java.lang.Integer> getAppStandbyBuckets();
                    method public void setAppStandbyBuckets(java.util.Map<java.lang.String,java.lang.Integer>);
                    field public java.util.Map<java.lang.String,java.lang.Integer> map;
                  }
                }
                """
        )
    }

    @Test
    fun `Compare signatures with Kotlin nullability from signature`() {
        check(
            expectedIssues = """
            TESTROOT/load-api.txt:5: error: Attempted to remove @NonNull annotation from parameter str in test.pkg.Foo.method1(int p, Integer int2, int p1, String str, java.lang.String... args) [InvalidNullConversion]
            TESTROOT/load-api.txt:7: error: Attempted to change parameter from @Nullable to @NonNull: incompatible change for parameter str in test.pkg.Foo.method3(String str, int p, int int2) [InvalidNullConversion]
            """.trimIndent(),
            format = FileFormat.V3,
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package test.pkg {
                  public final class Foo {
                    ctor public Foo();
                    method public void method1(int p = 42, Integer? int2 = null, int p1 = 42, String str = "hello world", java.lang.String... args);
                    method public void method2(int p, int int2 = (2 * int) * some.other.pkg.Constants.Misc.SIZE);
                    method public void method3(String? str, int p, int int2 = double(int) + str.length);
                    field public static final test.pkg.Foo.Companion! Companion;
                  }
                }
                """,
            signatureSource = """
                // Signature format: 3.0
                package test.pkg {
                  public final class Foo {
                    ctor public Foo();
                    method public void method1(int p = 42, Integer? int2 = null, int p1 = 42, String! str = "hello world", java.lang.String... args);
                    method public void method2(int p, int int2 = (2 * int) * some.other.pkg.Constants.Misc.SIZE);
                    method public void method3(String str, int p, int int2 = double(int) + str.length);
                    field public static final test.pkg.Foo.Companion! Companion;
                  }
                }
                """
        )
    }

    @Test
    fun `Compare signatures with Kotlin nullability from source`() {
        check(
            expectedIssues = """
            src/test/pkg/test.kt:4: error: Attempted to change parameter from @Nullable to @NonNull: incompatible change for parameter str1 in test.pkg.TestKt.fun1(String str1, String str2, java.util.List<java.lang.String> list) [InvalidNullConversion]
            """.trimIndent(),
            format = FileFormat.V3,
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package test.pkg {
                  public final class TestKt {
                    method public static void fun1(String? str1, String str2, java.util.List<java.lang.String!> list);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                        package test.pkg
                        import java.util.List

                        fun fun1(str1: String, str2: String?, list: List<String?>) { }

                    """.trimIndent()
                )
            )
        )
    }

    @Test
    fun `Adding and removing reified`() {
        check(
            inputKotlinStyleNulls = true,
            expectedIssues = """
                src/test/pkg/test.kt:5: error: Method test.pkg.TestKt.add made type variable T reified: incompatible change [AddedReified]
                src/test/pkg/test.kt:8: error: Method test.pkg.TestKt.two made type variable S reified: incompatible change [AddedReified]
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class TestKt {
                    method public static inline <T> void add(T! t);
                    method public static inline <reified T> void remove(T! t);
                    method public static inline <reified T> void unchanged(T! t);
                    method public static inline <S, reified T> void two(S! s, T! t);
                  }
                }
                """,

            sourceFiles = arrayOf(
                kotlin(
                    """
                    @file:Suppress("NOTHING_TO_INLINE", "RedundantVisibilityModifier", "unused")

                    package test.pkg

                    inline fun <reified T> add(t: T) { }
                    inline fun <T> remove(t: T) { }
                    inline fun <reified T> unchanged(t: T) { }
                    inline fun <reified S, T> two(s: S, t: T) { }
                    """
                ).indented()
            )
        )
    }

    @Test
    fun `Empty prev api with @hide and --show-annotation`() {
        check(
            checkCompatibilityApiReleased = """
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.media;

                    /**
                     * @hide
                     */
                    public class SubtitleController {
                        public interface Listener {
                            void onSubtitleTrackSelected() { }
                        }
                    }
                    """
                ),
                java(
                    """
                    package android.media;
                    import android.annotation.SystemApi;

                    /**
                     * @hide
                     */
                    @SystemApi
                    @SuppressWarnings("HiddenSuperclass")
                    public class MediaPlayer implements SubtitleController.Listener {
                    }
                    """
                ),
                systemApiSource
            ),
            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.SystemApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),
            expectedIssues = ""

        )
    }

    @Test
    fun `Inherited systemApi method in an inner class`() {
        check(
            checkCompatibilityApiReleased = """
                package android.telephony {
                  public class MmTelFeature.Capabilities {
                    method public boolean isCapable(int);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.telephony;

                    /**
                     * @hide
                     */
                    @android.annotation.SystemApi
                    public class MmTelFeature {
                        public static class Capabilities extends ParentCapabilities {
                            @Override
                            boolean isCapable(int argument) { return true; }
                        }
                    }
                    """
                ),
                java(
                    """
                    package android.telephony;

                    /**
                     * @hide
                     */
                    @android.annotation.SystemApi
                    public class Parent {
                        public static class ParentCapabilities {
                            public boolean isCapable(int argument) { return false; }
                        }
                    }
                    """
                ),
                systemApiSource
            ),
            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.SystemApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),
            expectedIssues = ""
        )
    }

    @Test
    fun `Moving removed api back to public api`() {
        check(
            checkCompatibilityRemovedApiReleased = """
                package android.content {
                  public class ContextWrapper {
                    method public void createContextForSplit();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.content;

                    public class ContextWrapper extends Parent {
                        /** @removed */
                        @Override
                        public void getSharedPreferences() { }

                        /** @hide */
                        @Override
                        public void createContextForSplit() { }
                    }
                    """
                ),
                java(
                    """
                    package android.content;

                    public abstract class Parent {
                        /** @hide */
                        @Override
                        public void getSharedPreferences() { }

                        public abstract void createContextForSplit() { }
                    }
                    """
                )
            ),
            expectedIssues = ""
        )
    }

    @Test
    fun `Inherited nullability annotations`() {
        check(
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public final class SAXException extends test.pkg.Parent {
                  }
                  public final class Parent extends test.pkg.Grandparent {
                  }
                  public final class Grandparent {
                    method @Nullable public String getMessage();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public final class SAXException extends Parent {
                        @Override public String getMessage() {
                            return "sample";
                        }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public final class Parent extends Grandparent {
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public final class Grandparent {
                        public String getMessage() {
                            return "sample";
                        }
                    }
                    """
                )
            ),
            mergeJavaStubAnnotations = """
                package test.pkg;

                public class Grandparent implements java.io.Serializable {
                    @libcore.util.Nullable public test.pkg.String getMessage() { throw new RuntimeException("Stub!"); }
                }
            """,
            expectedIssues = """
                """
        )
    }

    @Test
    fun `Inherited @removed fields`() {
        check(
            checkCompatibilityRemovedApiReleased = """
                package android.provider {

                  public static final class StreamItems implements android.provider.BaseColumns {
                    field public static final String _COUNT = "_count";
                    field public static final String _ID = "_id";
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.provider;

                    /**
                     * @removed
                     */
                    public static final class StreamItems implements BaseColumns {
                    }
                    """
                ),
                java(
                    """
                    package android.provider;

                    public interface BaseColumns {
                        public static final String _ID = "_id";
                        public static final String _COUNT = "_count";
                    }
                    """
                )
            ),
            expectedIssues = """
                """
        )
    }

    @Test
    fun `Inherited deprecated protected @removed method`() {
        check(
            checkCompatibilityApiReleased = """
                package android.icu.util {
                  public class SpecificCalendar {
                    method @Deprecated protected void validateField();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.icu.util;
                    import java.text.Format;

                    public class SpecificCalendar extends Calendar {
                        /**
                         * @deprecated for this test
                         * @hide
                         */
                        @Override
                        @Deprecated
                        protected void validateField() {
                        }
                    }
                    """
                ),
                java(
                    """
                    package android.icu.util;

                    public class Calendar {
                        protected void validateField() {
                        }
                    }
                    """
                )
            ),
            expectedIssues = """
                """
        )
    }

    @Test
    fun `Move class from SystemApi to public and then remove a method`() {
        check(
            checkCompatibilityApiReleased = """
                package android.hardware.lights {
                  public static final class LightsRequest.Builder {
                    ctor public LightsRequest.Builder();
                    method public void clearLight();
                    method public void setLight();
                  }

                  public final class LightsManager {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.hardware.lights;

                    import android.annotation.SystemApi;

                    public class LightsRequest {
                        public static class Builder {
                            void clearLight() { }
                        }
                    }
                    """
                ),
                java(
                    """
                    package android.hardware.lights;

                    import android.annotation.SystemApi;

                    /**
                     * @hide
                     */
                    @SystemApi
                    public class LightsManager {
                    }
                    """
                ),
                systemApiSource
            ),
            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.SystemApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),

            expectedIssues = """
                TESTROOT/released-api.txt:5: error: Removed method android.hardware.lights.LightsRequest.Builder.setLight() [RemovedMethod]
                """
        )
    }

    @Test
    fun `Moving a field from SystemApi to public`() {
        check(
            checkCompatibilityApiReleased = """
                package android.content {
                  public class Context {
                    field public static final String BUGREPORT_SERVICE = "bugreport";
                    method public File getPreloadsFileCache();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package android.content;

                    import android.annotation.SystemApi;

                    public class Context {
                        public static final String BUGREPORT_SERVICE = "bugreport";

                        /**
                         * @hide
                         */
                        @SystemApi
                        public File getPreloadsFileCache() { return null; }
                    }
                    """
                ),
                systemApiSource
            ),
            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.SystemApi",
                ARG_HIDE_PACKAGE, "android.annotation",
            ),

            expectedIssues = """
                """
        )
    }

    @Test
    fun `Compare interfaces when Object is redefined`() {
        check(
            checkCompatibilityApiReleased = """
                package java.lang {
                  public class Object {
                    method public final void wait();
                  }
                }
                package test.pkg {
                  public interface SomeInterface {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public interface SomeInterface {
                    }
                    """
                )
            ),
            // it's not quite right to say that java.lang was removed, but it's better than also
            // saying that SomeInterface no longer implements wait()
            expectedIssues = """
                TESTROOT/released-api.txt:1: error: Removed package java.lang [RemovedPackage]
                """
        )
    }

    @Test
    fun `Overriding method without redeclaring nullability`() {
        check(
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Child extends test.pkg.Parent {
                  }
                  public class Parent {
                    method public void sample(@Nullable String);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class Child extends Parent {
                        public void sample(String arg) {
                        }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;

                    public class Parent {
                        public void sample(@Nullable String arg) {
                        }
                    }
                    """
                )
            ),
            // The correct behavior would be for this test to fail, because of the removal of
            // nullability annotations on the child class. However, when we generate signature files,
            // we omit methods having the same signature as super methods, so if we were to generate
            // a signature file for this source, we would generate the given signature file. So,
            // we temporarily allow (and expect) this to pass without errors
            // expectedIssues = "src/test/pkg/Child.java:4: error: Attempted to remove @Nullable annotation from parameter arg in test.pkg.Child.sample(String arg) [InvalidNullConversion]"
            expectedIssues = ""
        )
    }

    @Test
    fun `Final class inherits a method`() {
        check(
            checkCompatibilityApiReleased = """
                package java.security {
                  public abstract class BasicPermission extends java.security.Permission {
                    method public boolean implies(java.security.Permission);
                  }
                  public abstract class Permission {
                    method public abstract boolean implies(java.security.Permission);
                  }
                }
                package javax.security.auth {
                  public final class AuthPermission extends java.security.BasicPermission {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package javax.security.auth;

                    public final class AuthPermission extends java.security.BasicPermission {
                    }
                    """
                ),
                java(
                    """
                    package java.security;

                    public abstract class BasicPermission extends Permission {
                        public boolean implies(Permission p) {
                            return true;
                        }
                    }
                    """
                ),
                java(
                    """
                    package java.security;
                    public abstract class Permission {
                        public abstract boolean implies(Permission permission);
                        }
                    }
                    """
                )
            ),
            expectedIssues = ""
        )
    }

    @Test
    fun `Implementing undefined interface`() {
        check(
            checkCompatibilityApiReleased = """
                package org.apache.http.conn.scheme {
                  @Deprecated public final class PlainSocketFactory implements org.apache.http.conn.scheme.SocketFactory {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package org.apache.http.conn.scheme;

                    /** @deprecated */
                    @Deprecated
                    public final class PlainSocketFactory implements SocketFactory {
                    }
                    """
                )
            ),
            expectedIssues = ""
        )
    }

    @Test
    fun `Inherited abstract method`() {
        check(
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class MeasureFormat {
                      method public test.pkg.MeasureFormat parse();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class MeasureFormat extends UFormat {
                        private MeasureFormat() { }
                        /** @hide */
                        public MeasureFormat parse();
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;

                    public abstract class UFormat {
                        public abstract UFormat parse() {
                        }
                    }
                    """
                ),
                systemApiSource
            ),
            expectedIssues = ""
        )
    }

    @Ignore("Not currently working: we're getting the wrong PSI results; I suspect caching across the two codebases")
    @Test
    fun `Test All Android API levels`() {
        // Checks API across Android SDK versions and makes sure the results are
        // intentional (to help shake out bugs in the API compatibility checker)

        // Expected migration warnings (the map value) when migrating to the target key level from the previous level
        val expected = mapOf(
            5 to "warning: Method android.view.Surface.lockCanvas added thrown exception java.lang.IllegalArgumentException [ChangedThrows]",
            6 to """
                warning: Method android.accounts.AbstractAccountAuthenticator.confirmCredentials added thrown exception android.accounts.NetworkErrorException [ChangedThrows]
                warning: Method android.accounts.AbstractAccountAuthenticator.updateCredentials added thrown exception android.accounts.NetworkErrorException [ChangedThrows]
                warning: Field android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL has changed value from 2008 to 2014 [ChangedValue]
                """,
            7 to """
                error: Removed field android.view.ViewGroup.FLAG_USE_CHILD_DRAWING_ORDER [RemovedField]
                """,

            // setOption getting removed here is wrong! Seems to be a PSI loading bug.
            8 to """
                warning: Constructor android.net.SSLCertificateSocketFactory no longer throws exception java.security.KeyManagementException [ChangedThrows]
                warning: Constructor android.net.SSLCertificateSocketFactory no longer throws exception java.security.NoSuchAlgorithmException [ChangedThrows]
                error: Removed method java.net.DatagramSocketImpl.getOption(int) [RemovedMethod]
                error: Removed method java.net.DatagramSocketImpl.setOption(int,Object) [RemovedMethod]
                warning: Constructor java.nio.charset.Charset no longer throws exception java.nio.charset.IllegalCharsetNameException [ChangedThrows]
                warning: Method java.nio.charset.Charset.forName no longer throws exception java.nio.charset.IllegalCharsetNameException [ChangedThrows]
                warning: Method java.nio.charset.Charset.forName no longer throws exception java.nio.charset.UnsupportedCharsetException [ChangedThrows]
                warning: Method java.nio.charset.Charset.isSupported no longer throws exception java.nio.charset.IllegalCharsetNameException [ChangedThrows]
                warning: Method java.util.regex.Matcher.appendReplacement no longer throws exception java.lang.IllegalStateException [ChangedThrows]
                warning: Method java.util.regex.Matcher.start no longer throws exception java.lang.IllegalStateException [ChangedThrows]
                warning: Method java.util.regex.Pattern.compile no longer throws exception java.util.regex.PatternSyntaxException [ChangedThrows]
                warning: Class javax.xml.XMLConstants added final qualifier [AddedFinal]
                error: Removed constructor javax.xml.XMLConstants() [RemovedMethod]
                warning: Method javax.xml.parsers.DocumentBuilder.isXIncludeAware no longer throws exception java.lang.UnsupportedOperationException [ChangedThrows]
                warning: Method javax.xml.parsers.DocumentBuilderFactory.newInstance no longer throws exception javax.xml.parsers.FactoryConfigurationError [ChangedThrows]
                warning: Method javax.xml.parsers.SAXParser.isXIncludeAware no longer throws exception java.lang.UnsupportedOperationException [ChangedThrows]
                warning: Method javax.xml.parsers.SAXParserFactory.newInstance no longer throws exception javax.xml.parsers.FactoryConfigurationError [ChangedThrows]
                warning: Method org.w3c.dom.Element.getAttributeNS added thrown exception org.w3c.dom.DOMException [ChangedThrows]
                warning: Method org.w3c.dom.Element.getAttributeNodeNS added thrown exception org.w3c.dom.DOMException [ChangedThrows]
                warning: Method org.w3c.dom.Element.getElementsByTagNameNS added thrown exception org.w3c.dom.DOMException [ChangedThrows]
                warning: Method org.w3c.dom.Element.hasAttributeNS added thrown exception org.w3c.dom.DOMException [ChangedThrows]
                warning: Method org.w3c.dom.NamedNodeMap.getNamedItemNS added thrown exception org.w3c.dom.DOMException [ChangedThrows]
                """,

            18 to """
                warning: Class android.os.Looper added final qualifier but was previously uninstantiable and therefore could not be subclassed [AddedFinalUninstantiable]
                warning: Class android.os.MessageQueue added final qualifier but was previously uninstantiable and therefore could not be subclassed [AddedFinalUninstantiable]
                error: Removed field android.os.Process.BLUETOOTH_GID [RemovedField]
                error: Removed class android.renderscript.Program [RemovedClass]
                error: Removed class android.renderscript.ProgramStore [RemovedClass]
                """,
            19 to """
                warning: Method android.app.Notification.Style.build has changed 'abstract' qualifier [ChangedAbstract]
                error: Removed method android.os.Debug.MemoryInfo.getOtherLabel(int) [RemovedMethod]
                error: Removed method android.os.Debug.MemoryInfo.getOtherPrivateDirty(int) [RemovedMethod]
                error: Removed method android.os.Debug.MemoryInfo.getOtherPss(int) [RemovedMethod]
                error: Removed method android.os.Debug.MemoryInfo.getOtherSharedDirty(int) [RemovedMethod]
                warning: Field android.view.animation.Transformation.TYPE_ALPHA has changed value from nothing/not constant to 1 [ChangedValue]
                warning: Field android.view.animation.Transformation.TYPE_ALPHA has added 'final' qualifier [AddedFinal]
                warning: Field android.view.animation.Transformation.TYPE_BOTH has changed value from nothing/not constant to 3 [ChangedValue]
                warning: Field android.view.animation.Transformation.TYPE_BOTH has added 'final' qualifier [AddedFinal]
                warning: Field android.view.animation.Transformation.TYPE_IDENTITY has changed value from nothing/not constant to 0 [ChangedValue]
                warning: Field android.view.animation.Transformation.TYPE_IDENTITY has added 'final' qualifier [AddedFinal]
                warning: Field android.view.animation.Transformation.TYPE_MATRIX has changed value from nothing/not constant to 2 [ChangedValue]
                warning: Field android.view.animation.Transformation.TYPE_MATRIX has added 'final' qualifier [AddedFinal]
                warning: Method java.nio.CharBuffer.subSequence has changed return type from CharSequence to java.nio.CharBuffer [ChangedType]
                """, // The last warning above is not right; seems to be a PSI jar loading bug. It returns the wrong return type!

            20 to """
                error: Removed method android.util.TypedValue.complexToDimensionNoisy(int,android.util.DisplayMetrics) [RemovedMethod]
                warning: Method org.json.JSONObject.keys has changed return type from java.util.Iterator to java.util.Iterator<java.lang.String> [ChangedType]
                warning: Field org.xmlpull.v1.XmlPullParserFactory.features has changed type from java.util.HashMap to java.util.HashMap<java.lang.String, java.lang.Boolean> [ChangedType]
                """,
            26 to """
                warning: Field android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE has changed value from 130 to 230 [ChangedValue]
                warning: Field android.content.pm.PermissionInfo.PROTECTION_MASK_FLAGS has changed value from 4080 to 65520 [ChangedValue]
                """,
            27 to ""
        )

        val suppressLevels = mapOf(
            1 to "AddedPackage,AddedClass,AddedMethod,AddedInterface,AddedField,ChangedDeprecated",
            7 to "AddedPackage,AddedClass,AddedMethod,AddedInterface,AddedField,ChangedDeprecated",
            18 to "AddedPackage,AddedClass,AddedMethod,AddedInterface,AddedField,RemovedMethod,ChangedDeprecated,ChangedThrows,AddedFinal,ChangedType,RemovedDeprecatedClass",
            26 to "AddedPackage,AddedClass,AddedMethod,AddedInterface,AddedField,RemovedMethod,ChangedDeprecated,ChangedThrows,AddedFinal,RemovedClass,RemovedDeprecatedClass",
            27 to "AddedPackage,AddedClass,AddedMethod,AddedInterface,AddedField,RemovedMethod,ChangedDeprecated,ChangedThrows,AddedFinal"
        )

        val loadPrevAsSignature = false

        for (apiLevel in 5..27) {
            if (!expected.containsKey(apiLevel)) {
                continue
            }
            println("Checking compatibility from API level ${apiLevel - 1} to $apiLevel...")
            val current = getAndroidJar(apiLevel)
            val previous = getAndroidJar(apiLevel - 1)
            val previousApi = previous.path

            // PSI based check

            check(
                extraArguments = arrayOf(
                    "--omit-locations",
                    ARG_HIDE,
                    suppressLevels[apiLevel]
                        ?: "AddedPackage,AddedClass,AddedMethod,AddedInterface,AddedField,ChangedDeprecated,RemovedField,RemovedClass,RemovedDeprecatedClass" +
                            (if ((apiLevel == 19 || apiLevel == 20) && loadPrevAsSignature) ",ChangedType" else "")

                ),
                expectedIssues = expected[apiLevel]?.trimIndent() ?: "",
                checkCompatibilityApiReleased = previousApi,
                apiJar = current
            )

            // Signature based check
            if (apiLevel >= 21) {
                // Check signature file checks. We have .txt files for API level 14 and up, but there are a
                // BUNCH of problems in older signature files that make the comparisons not work --
                // missing type variables in class declarations, missing generics in method signatures, etc.
                val signatureFile = File("../../prebuilts/sdk/${apiLevel - 1}/public/api/android.txt")
                if (!(signatureFile.isFile)) {
                    println("Couldn't find $signatureFile: Check that pwd for test is correct. Skipping this test.")
                    return
                }
                val previousSignatureApi = signatureFile.readText(UTF_8)

                check(
                    extraArguments = arrayOf(
                        "--omit-locations",
                        ARG_HIDE,
                        suppressLevels[apiLevel]
                            ?: "AddedPackage,AddedClass,AddedMethod,AddedInterface,AddedField,ChangedDeprecated,RemovedField,RemovedClass,RemovedDeprecatedClass"
                    ),
                    expectedIssues = expected[apiLevel]?.trimIndent() ?: "",
                    checkCompatibilityApiReleased = previousSignatureApi,
                    apiJar = current
                )
            }
        }
    }

    @Test
    fun `Ignore hidden references`() {
        check(
            expectedIssues = """
                """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class MyClass {
                    ctor public MyClass();
                    method public void method1(test.pkg.Hidden);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;

                    public class MyClass {
                        public void method1(Hidden hidden) { }
                    }
                    """
                ),
                java(
                    """
                    package test.pkg;
                    /** @hide */
                    public class Hidden {
                    }
                    """
                )
            ),
            extraArguments = arrayOf(
                ARG_HIDE, "ReferencesHidden",
                ARG_HIDE, "UnavailableSymbol",
                ARG_HIDE, "HiddenTypeParameter"
            )
        )
    }

    @Test
    fun `Empty bundle files`() {
        // Regression test for 124333557
        // Makes sure we properly handle conflicting definitions of a java file in separate source roots
        check(
            expectedIssues = "",
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package com.android.location.provider {
                  public class LocationProviderBase1 {
                    ctor public LocationProviderBase1();
                    method public void onGetStatus(android.os.Bundle!);
                  }
                  public class LocationProviderBase2 {
                    ctor public LocationProviderBase2();
                    method public void onGetStatus(android.os.Bundle!);
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    "src2/com/android/location/provider/LocationProviderBase1.java",
                    """
                    /** Something */
                    package com.android.location.provider;
                    """
                ),
                java(
                    "src/com/android/location/provider/LocationProviderBase1.java",
                    """
                    package com.android.location.provider;
                    import android.os.Bundle;

                    public class LocationProviderBase1 {
                        public void onGetStatus(Bundle bundle) { }
                    }
                    """
                ),
                // Try both combinations (empty java file both first on the source path
                // and second on the source path)
                java(
                    "src/com/android/location/provider/LocationProviderBase2.java",
                    """
                    /** Something */
                    package com.android.location.provider;
                    """
                ),
                java(
                    "src/com/android/location/provider/LocationProviderBase2.java",
                    """
                    package com.android.location.provider;
                    import android.os.Bundle;

                    public class LocationProviderBase2 {
                        public void onGetStatus(Bundle bundle) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check parameterized return type nullability`() {
        // Regression test for 130567941
        check(
            expectedIssues = "",
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package androidx.coordinatorlayout.widget {
                  public class CoordinatorLayout {
                    ctor public CoordinatorLayout();
                    method public java.util.List<android.view.View!> getDependencies();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package androidx.coordinatorlayout.widget;

                    import java.util.List;
                    import androidx.annotation.NonNull;
                    import android.view.View;

                    public class CoordinatorLayout {
                        @NonNull
                        public List<View> getDependencies() {
                            throw Exception("Not implemented");
                        }
                    }
                    """
                ),
                androidxNonNullSource
            ),
            extraArguments = arrayOf(ARG_HIDE_PACKAGE, "androidx.annotation")
        )
    }

    @Test
    fun `Check return type changing package`() {
        // Regression test for 130567941
        check(
            expectedIssues = """
            TESTROOT/load-api.txt:7: error: Method test.pkg.sample.SampleClass.convert1 has changed return type from Number to java.lang.Number [ChangedType]
            """,
            inputKotlinStyleNulls = true,
            outputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package test.pkg.sample {
                  public abstract class SampleClass {
                    method public <Number> Number! convert(Number);
                    method public <Number> Number! convert1(Number);
                  }
                }
                """,
            signatureSource = """
                // Signature format: 3.0
                package test.pkg.sample {
                  public abstract class SampleClass {
                    // Here the generic type parameter applies to both the function argument and the function return type
                    method public <Number> Number! convert(Number);
                    // Here the generic type parameter applies to the function argument but not the function return type
                    method public <Number> java.lang.Number! convert1(Number);
                  }
                }
            """
        )
    }

    @Test
    fun `Check generic type argument when showUnannotated is explicitly enabled`() {
        // Regression test for 130567941
        check(
            expectedIssues = """
            """,
            inputKotlinStyleNulls = true,
            outputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package androidx.versionedparcelable {
                  public abstract class VersionedParcel {
                    method public <T> T![]! readArray();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package androidx.versionedparcelable;

                    public abstract class VersionedParcel {
                        private VersionedParcel() { }

                        public <T> T[] readArray() { return null; }
                    }
                    """
                )
            ),
            extraArguments = arrayOf(ARG_SHOW_UNANNOTATED, ARG_SHOW_ANNOTATION, "androidx.annotation.RestrictTo")
        )
    }

    @Test
    fun `Check using parameterized arrays as type parameters`() {
        check(
            format = FileFormat.V3,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;
                    import java.util.ArrayList;
                    import java.lang.Exception;

                    public class SampleArray<D extends ArrayList> extends ArrayList<D[]> {
                        public D[] get(int index) {
                            throw Exception("Not implemented");
                        }
                    }
                    """
                )
            ),

            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package test.pkg {
                  public class SampleArray<D extends java.util.ArrayList> extends java.util.ArrayList<D[]> {
                    ctor public SampleArray();
                    method public D![]! get(int);
                  }
                }
                """
        )
    }

    @Test
    fun `New default method on annotation`() {
        // Regression test for 134754815
        check(
            expectedIssues = """
            src/androidx/room/Relation.java:5: error: Added method androidx.room.Relation.IHaveNoDefault() [AddedAbstractMethod]
            """,
            inputKotlinStyleNulls = true,
            outputKotlinStyleNulls = true,
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package androidx.room {
                  public @interface Relation {
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package androidx.room;

                    public @interface Relation {
                        String IHaveADefault() default "";
                        String IHaveNoDefault();
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Changing static qualifier on inner classes with no public constructors`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:11: error: Class test.pkg.ParentClass.AnotherBadInnerClass changed 'static' qualifier [ChangedStatic]
                TESTROOT/load-api.txt:8: error: Class test.pkg.ParentClass.BadInnerClass changed 'static' qualifier [ChangedStatic]
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class ParentClass {
                  }
                  public static class ParentClass.OkInnerClass {
                  }
                  public class ParentClass.AnotherOkInnerClass {
                  }
                  public static class ParentClass.BadInnerClass {
                    ctor public BadInnerClass();
                  }
                  public class ParentClass.AnotherBadInnerClass {
                    ctor public AnotherBadInnerClass();
                  }
                }
                """,
            signatureSource = """
                package test.pkg {
                  public class ParentClass {
                  }
                  public class ParentClass.OkInnerClass {
                  }
                  public static class ParentClass.AnotherOkInnerClass {
                  }
                  public class ParentClass.BadInnerClass {
                    ctor public BadInnerClass();
                  }
                  public static class ParentClass.AnotherBadInnerClass {
                    ctor public AnotherBadInnerClass();
                  }
                }
                """
        )
    }

    @Test
    fun `Remove fun modifier from interface`() {
        check(
            expectedIssues = """
                src/test/pkg/FunctionalInterface.kt:3: error: Cannot remove 'fun' modifier from class test.pkg.FunctionalInterface: source incompatible change [FunRemoval]
                """,
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                // Signature format: 4.0
                package test.pkg {
                  public fun interface FunctionalInterface {
                    method public boolean methodOne(int number);
                  }
                }
                """,
            sourceFiles = arrayOf(
                kotlin(
                    """
                    package test.pkg

                    interface FunctionalInterface {
                        fun methodOne(number: Int): Boolean
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Remove fun modifier from interface signature files`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:3: error: Cannot remove 'fun' modifier from class test.pkg.FunctionalInterface: source incompatible change [FunRemoval]
                """,
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                // Signature format: 4.0
                package test.pkg {
                  public fun interface FunctionalInterface {
                    method public boolean methodOne(int number);
                  }
                }
                """,
            signatureSource = """
                // Signature format: 4.0
                package test.pkg {
                  public interface FunctionalInterface {
                    method public boolean methodOne(int number);
                  }
                }
            """.trimIndent()
        )
    }

    @Test
    fun `Adding default value to annotation parameter`() {
        check(
            expectedIssues = "",
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                // Signature format: 4.0
                package androidx.annotation.experimental {
                  public @interface UseExperimental {
                    method public abstract Class<?> markerClass();
                  }
                }
                """,
            sourceFiles = arrayOf(
                java(
                    """
                    package androidx.annotation.experimental;
                    public @interface UseExperimental {
                        Class<?> markerClass() default void.class;
                    }
                """
                )
            )
        )
    }

    @Test
    fun `adding methods to interfaces`() {
        check(
            expectedIssues = """
                src/test/pkg/JavaInterface.java:4: error: Added method test.pkg.JavaInterface.noDefault() [AddedAbstractMethod]
                src/test/pkg/KotlinInterface.kt:5: error: Added method test.pkg.KotlinInterface.hasDefault() [AddedAbstractMethod]
                src/test/pkg/KotlinInterface.kt:4: error: Added method test.pkg.KotlinInterface.noDefault() [AddedAbstractMethod]
            """,
            checkCompatibilityApiReleased = """
                // Signature format: 3.0
                package test.pkg {
                  public interface JavaInterface {
                  }
                  public interface KotlinInterface {
                  }
                }
            """,
            sourceFiles = arrayOf(
                java(
                    """
                        package test.pkg;

                        public interface JavaInterface {
                            void noDefault();
                            default boolean hasDefault() {
                                return true;
                            }
                            static void newStatic();
                        }
                    """
                ),
                kotlin(
                    """
                        package test.pkg

                        interface KotlinInterface {
                            fun noDefault()
                            fun hasDefault(): Boolean = true
                        }
                    """
                )
            )
        )
    }

    @Test
    fun `Changing visibility from public to private`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:2: error: Class test.pkg.Foo changed visibility from public to private [ChangedScope]
            """.trimIndent(),
            signatureSource = """
                package test.pkg {
                  private class Foo {}
                }
            """.trimIndent(),
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class Foo {}
                }
            """.trimIndent()
        )
    }

    @Test
    fun `Changing class kind`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:11: error: Class test.pkg.AnnotationToClass changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:13: error: Class test.pkg.AnnotationToEnum changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:12: error: Class test.pkg.AnnotationToInterface changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:4: error: Class test.pkg.ClassToAnnotation changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:2: error: Class test.pkg.ClassToEnum changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:3: error: Class test.pkg.ClassToInterface changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:7: error: Class test.pkg.EnumToAnnotation changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:5: error: Class test.pkg.EnumToClass changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:6: error: Class test.pkg.EnumToInterface changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:10: error: Class test.pkg.InterfaceToAnnotation changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:8: error: Class test.pkg.InterfaceToClass changed class/interface declaration [ChangedClass]
                TESTROOT/load-api.txt:9: error: Class test.pkg.InterfaceToEnum changed class/interface declaration [ChangedClass]
            """.trimIndent(),
            signatureSource = """
                package test.pkg {
                  public enum ClassToEnum {}
                  public interface ClassToInterface {}
                  public @interface ClassToAnnotation {}
                  public class EnumToClass {}
                  public interface EnumToInterface {}
                  public @interface EnumToAnnotation {}
                  public class InterfaceToClass {}
                  public enum InterfaceToEnum {}
                  public @interface InterfaceToAnnotation {}
                  public class  AnnotationToClass {}
                  public interface AnnotationToInterface {}
                  public enum AnnotationToEnum {}
                }
            """.trimIndent(),
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  public class ClassToEnum {}
                  public class ClassToInterface {}
                  public class ClassToAnnotation {}
                  public enum EnumToClass {}
                  public enum EnumToInterface {}
                  public enum EnumToAnnotation {}
                  public interface InterfaceToClass {}
                  public interface InterfaceToEnum {}
                  public interface InterfaceToAnnotation {}
                  public @interface  AnnotationToClass {}
                  public @interface AnnotationToInterface {}
                  public @interface AnnotationToEnum {}
                }
            """.trimIndent()
        )
    }

    @Test
    fun `Allow increased field access for classes`() {
        check(
            signatureSource = """
                package test.pkg {
                  class Foo {
                    field public int bar;
                    field protected int baz;
                    field protected int spam;
                  }
                }
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  class Foo {
                    field protected int bar;
                    field private int baz;
                    field internal int spam;
                  }
                }
            """
        )
    }

    @Test
    fun `Block decreased field access in classes`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:3: error: Field test.pkg.Foo.bar changed visibility from public to protected [ChangedScope]
                TESTROOT/load-api.txt:4: error: Field test.pkg.Foo.baz changed visibility from protected to private [ChangedScope]
                TESTROOT/load-api.txt:5: error: Field test.pkg.Foo.spam changed visibility from protected to internal [ChangedScope]
            """,
            signatureSource = """
                package test.pkg {
                  class Foo {
                    field protected int bar;
                    field private int baz;
                    field internal int spam;
                  }
                }
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  class Foo {
                    field public int bar;
                    field protected int baz;
                    field protected int spam;
                  }
                }
            """
        )
    }

    @Test
    fun `Allow increased access`() {
        check(
            signatureSource = """
                package test.pkg {
                  class Foo {
                    method public void bar();
                    method protected void baz();
                    method protected void spam();
                  }
                }
            """,
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  class Foo {
                    method protected void bar();
                    method private void baz();
                    method internal void spam();
                  }
                }
            """
        )
    }

    @Test
    fun `Block decreased access`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:3: error: Method test.pkg.Foo.bar changed visibility from public to protected [ChangedScope]
                TESTROOT/load-api.txt:4: error: Method test.pkg.Foo.baz changed visibility from protected to private [ChangedScope]
                TESTROOT/load-api.txt:5: error: Method test.pkg.Foo.spam changed visibility from protected to internal [ChangedScope]
            """,
            signatureSource = """
                package test.pkg {
                  class Foo {
                    method protected void bar();
                    method private void baz();
                    method internal void spam();
                  }
                }
            """,
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  class Foo {
                    method public void bar();
                    method protected void baz();
                    method protected void spam();
                  }
                }
            """
        )
    }

    @Test
    fun `configuring issue severity`() {
        check(
            extraArguments = arrayOf(ARG_HIDE, Issues.REMOVED_METHOD.name),
            signatureSource = """
                package test.pkg {
                    public class Foo {
                    }
                }
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                    public class Foo {
                        ctor public Foo();
                        method public void bar();
                    }
                }
            """
        )
    }

    @Test
    fun `block changing open to abstract`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:2: error: Class test.pkg.Foo changed 'abstract' qualifier [ChangedAbstract]
                TESTROOT/load-api.txt:4: error: Method test.pkg.Foo.bar has changed 'abstract' qualifier [ChangedAbstract]
            """,
            signatureSource = """
                package test.pkg {
                    public abstract class Foo {
                        ctor public Foo();
                        method public abstract void bar();
                    }
                }
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                    public class Foo {
                        ctor public Foo();
                        method public void bar();
                    }
                }
            """
        )
    }

    @Test
    fun `allow changing abstract to open`() {
        check(
            signatureSource = """
                package test.pkg {
                    public class Foo {
                        ctor public Foo();
                        method public void bar();
                    }
                }
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                    public abstract class Foo {
                        ctor public Foo();
                        method public abstract void bar();
                    }
                }
            """
        )
    }

    @Test
    fun `Change default to abstract`() {
        check(
            expectedIssues = """
                TESTROOT/load-api.txt:3: error: Method test.pkg.Foo.bar has changed 'default' qualifier [ChangedDefault]
            """,
            signatureSource = """
                package test.pkg {
                  interface Foo {
                    method abstract public void bar(Int);
                  }
                }
            """,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  interface Foo {
                    method default public void bar(Int);
                    }
                  }
              """
        )
    }

    @Test
    fun `Allow change from non-final to final in sealed class`() {
        check(
            signatureSource = """
                package test.pkg {
                  sealed class Foo {
                    method final public void bar(Int);
                  }
                }
            """,
            format = FileFormat.V4,
            checkCompatibilityApiReleased = """
                package test.pkg {
                  sealed class Foo {
                    method public void bar(Int);
                  }
                }
            """
        )
    }

    @Test
    fun `unchanged self-referencing type parameter is compatible`() {
        check(
            checkCompatibilityApiReleased = """
                package test.pkg {
                    public abstract class Foo<T extends test.pkg.Foo<T>> {
                            method public static <T extends test.pkg.Foo<T>> T valueOf(Class<T>, String);
                    }
                }
            """,
            sourceFiles = arrayOf(
                java(
                    """
                    package test.pkg;
                    import android.annotation.NonNull;
                    public abstract class Foo<T extends Foo<T>> {
                        @NonNull
                        public static <T extends Foo<T>> T valueOf(@NonNull Class<T> fooType, @NonNull String name) {}
                    }
                    """
                ),
                nonNullSource
            )
        )
    }

    // TODO: Check method signatures changing incompatibly (look especially out for adding new overloaded
    // methods and comparator getting confused!)
    //   ..equals on the method items should actually be very useful!
}
