package com.android.tools.metalava

import org.junit.Test

/** Tests for the --show-annotation functionality */
class ShowAnnotationTest : DriverTest() {

    @Test
    fun `Basic showAnnotation test`() {
        check(
            includeSystemApiAnnotations = true,
            checkDoclava1 = true,
            warnings = "src/test/pkg/Foo.java:17: error: @SystemApi APIs must also be marked @hide: method test.pkg.Foo.method4() [UnhiddenSystemApi]",
            sourceFiles = *arrayOf(
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;
                    public class Foo {
                        public void method1() { }

                        /**
                         * @hide Only for use by WebViewProvider implementations
                         */
                        @SystemApi
                        public void method2() { }

                        /**
                         * @hide Always hidden
                         */
                        public void method3() { }

                        @SystemApi
                        public void method4() { }

                    }
                    """
                ),
                java(
                    """
                    package foo.bar;
                    public class Bar {
                    }
                """
                ),
                systemApiSource
            ),

            extraArguments = arrayOf(
                ARG_HIDE_PACKAGE, "android.annotation",
                ARG_HIDE_PACKAGE, "android.support.annotation"
            ),

            api = """
                package test.pkg {
                  public class Foo {
                    method public void method2();
                    method public void method4();
                  }
                }
                """
        )
    }

    @Test
    fun `Basic showAnnotation with showUnannotated test`() {
        check(
            includeSystemApiAnnotations = true,
            showUnannotated = true,
            checkDoclava1 = true,
            warnings = "src/test/pkg/Foo.java:17: error: @SystemApi APIs must also be marked @hide: method test.pkg.Foo.method4() [UnhiddenSystemApi]",
            sourceFiles = *arrayOf(
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;
                    public class Foo {
                        public void method1() { }

                        /**
                         * @hide Only for use by WebViewProvider implementations
                         */
                        @SystemApi
                        public void method2() { }

                        /**
                         * @hide Always hidden
                         */
                        public void method3() { }

                        @SystemApi
                        public void method4() { }

                    }
                    """
                ),
                java(
                    """
                    package foo.bar;
                    public class Bar {
                    }
                """
                ),
                systemApiSource
            ),

            extraArguments = arrayOf(
                ARG_HIDE_PACKAGE, "android.annotation",
                ARG_HIDE_PACKAGE, "android.support.annotation"
            ),

            api = """
                package foo.bar {
                  public class Bar {
                    ctor public Bar();
                  }
                }
                package test.pkg {
                  public class Foo {
                    ctor public Foo();
                    method public void method1();
                    method public void method2();
                    method public void method4();
                  }
                }
                """
        )
    }

    @Test
    fun `Check @TestApi handling`() {
        check(
            includeSystemApiAnnotations = true,
            checkDoclava1 = true,
            sourceFiles = *arrayOf(
                java(
                    """
                    package test.pkg;
                    import android.annotation.TestApi;

                    /**
                     * Blah blah blah
                     * @hide
                     */
                    @TestApi
                    public class Bar {
                        public void test() {
                        }
                    }
                    """
                ),

                // This isn't necessary for this test, but doclava will ignore @hide classes marked
                // with an annotation unless there is a public reference it to it from elsewhere.
                // Include this here such that the checkDoclava1=true step produces any output.
                java(
                    """
                    package test.pkg;
                    public class Usage {
                        public Bar bar;
                    }
                    """
                ),
                testApiSource
            ),

            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.TestApi",
                ARG_HIDE_PACKAGE, "android.annotation",
                ARG_HIDE_PACKAGE, "android.support.annotation"
            ),

            api = """
                package test.pkg {
                  public class Bar {
                    ctor public Bar();
                    method public void test();
                  }
                }
                """
        )
    }

    @Test
    fun `Include interface-inherited fields in stubs`() {
        // When applying an annotations filter (-showAnnotation X), doclava
        // deliberately made the signature files *only* include annotated
        // elements, e.g. they're just showing the "diffs" between the base API
        // and the additional API made visible with annotations. However,
        // in the *stubs*, we have to include everything.
        check(
            checkDoclava1 = false,
            sourceFiles = *arrayOf(
                java(
                    """
                    package test.pkg2;

                    import test.pkg1.MyParent;
                    public class MyChild extends MyParent {
                    }
                    """
                ),
                java(
                    """
                    package test.pkg1;
                    import java.io.Closeable;
                    @SuppressWarnings("WeakerAccess")
                    public class MyParent implements MyConstants, Closeable {
                    }
                    """
                ),
                java(
                    """
                    package test.pkg1;
                    interface MyConstants {
                        long CONSTANT1 = 12345;
                        long CONSTANT2 = 67890;
                        long CONSTANT3 = 42;
                    }
                    """
                )
            ),
            stubs = arrayOf(
                """
                package test.pkg2;
                @SuppressWarnings({"unchecked", "deprecation", "all"})
                public class MyChild extends test.pkg1.MyParent {
                public MyChild() { throw new RuntimeException("Stub!"); }
                public static final long CONSTANT1 = 12345L; // 0x3039L
                public static final long CONSTANT2 = 67890L; // 0x10932L
                public static final long CONSTANT3 = 42L; // 0x2aL
                }
            """.trimIndent(),
                """
                package test.pkg1;
                @SuppressWarnings({"unchecked", "deprecation", "all"})
                public class MyParent implements java.io.Closeable {
                public MyParent() { throw new RuntimeException("Stub!"); }
                public static final long CONSTANT1 = 12345L; // 0x3039L
                public static final long CONSTANT2 = 67890L; // 0x10932L
                public static final long CONSTANT3 = 42L; // 0x2aL
                }
                """.trimIndent()
            ),
            // Empty API: showUnannotated=false
            api = """
                """.trimIndent(),
            includeSystemApiAnnotations = true,
            extraArguments = arrayOf(
                ARG_SHOW_ANNOTATION, "android.annotation.TestApi",
                ARG_HIDE_PACKAGE, "android.annotation",
                ARG_HIDE_PACKAGE, "android.support.annotation"
            )
        )
    }

    @Test
    fun `No UnhiddenSystemApi warning for --show-single-annotations`() {
        check(
            checkDoclava1 = true,
            warnings = "",
            sourceFiles = *arrayOf(
                java(
                    """
                    package test.pkg;
                    import android.annotation.SystemApi;
                    public class Foo {
                        public void method1() { }

                        /**
                         * @hide Only for use by WebViewProvider implementations
                         */
                        @SystemApi
                        public void method2() { }

                        /**
                         * @hide Always hidden
                         */
                        public void method3() { }

                        @SystemApi
                        public void method4() { }

                    }
                    """
                ),
                java(
                    """
                    package foo.bar;
                    public class Bar {
                    }
                """
                ),
                systemApiSource
            ),

            extraArguments = arrayOf(
                ARG_SHOW_SINGLE_ANNOTATION, "android.annotation.SystemApi",
                ARG_HIDE_PACKAGE, "android.annotation",
                ARG_HIDE_PACKAGE, "android.support.annotation"
            ),

            api = """
                package test.pkg {
                  public class Foo {
                    method public void method2();
                    method public void method4();
                  }
                }
                """
        )
    }
}