# Copyright 2022 Google LLC. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the License);
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Kotlin kt_jvm_compile API test."""

load("//kotlin:traverse_exports.bzl", "kt_traverse_exports")
load("//kotlin:jvm_compile.bzl", "kt_jvm_compile")
load("//kotlin:common.bzl", "common")
load("//tests/analysis:util.bzl", "ONLY_FOR_ANALYSIS_TEST_TAGS", "create_dir", "create_file")
load("//toolchains/kotlin_jvm:java_toolchains.bzl", "java_toolchains")
load("//toolchains/kotlin_jvm:kt_jvm_toolchains.bzl", "kt_jvm_toolchains")
load("@bazel_skylib//rules:build_test.bzl", "build_test")
load(":assert_failure_test.bzl", "assert_failure_test")
load("//:visibility.bzl", "RULES_KOTLIN")

def _impl(ctx):
    # As additional capabilites need to be tested, this rule should support
    # additional fields/attributes.
    result = kt_jvm_compile(
        ctx,
        output = ctx.outputs.jar,
        srcs = ctx.files.srcs,
        common_srcs = ctx.files.common_srcs,
        deps = ctx.attr.deps,
        plugins = [],
        exported_plugins = [],
        runtime_deps = [],
        exports = ctx.attr.exports,
        javacopts = [],
        kotlincopts = [],
        neverlink = False,
        testonly = False,
                android_lint_plugins = [],
        manifest = None,
        merged_manifest = None,
        resource_files = [],
        rule_family = ctx.attr.rule_family,
        kt_toolchain = kt_jvm_toolchains.get(ctx),
        java_toolchain = java_toolchains.get(ctx),
        disable_lint_checks = [],
        r_java = ctx.attr.r_java[JavaInfo] if ctx.attr.r_java else None,
    )
    return [result.java_info]

_kt_jvm_compile = rule(
    implementation = _impl,
    attrs = dict(
        srcs = attr.label_list(
            allow_files = True,
        ),
        common_srcs = attr.label_list(
            allow_files = True,
        ),
        deps = attr.label_list(
            aspects = [kt_traverse_exports.aspect],
            providers = [JavaInfo],
        ),
        exports = attr.label_list(
            aspects = [kt_traverse_exports.aspect],
            providers = [JavaInfo],
        ),
        rule_family = attr.int(
            default = common.RULE_FAMILY.UNKNOWN,
        ),
        r_java = attr.label(
            providers = [JavaInfo],
        ),
        _java_toolchain = attr.label(
            default = Label(
                "@bazel_tools//tools/jdk:current_java_toolchain",
            ),
        ),
    ),
    fragments = ["java"],
    outputs = dict(
        jar = "lib%{name}.jar",
    ),
    toolchains = [kt_jvm_toolchains.type],
)

def _test_kt_jvm_compile_using_kt_jvm_compile_with_r_java():
    test_name = "kt_jvm_compile_using_kt_jvm_compile_with_r_java_test"

    native.java_library(
        name = "foo_resources",
        srcs = [create_file(
            name = test_name + "/java/com/foo/R.java",
            content = """
package com.foo;

public final class R {
  public static final class string {
    public static int a_string=0x00000001;
    public static int b_string=0x00000002;
  }
}
""",
        )],
    )

    _kt_jvm_compile(
        name = "kt_jvm_compile_with_r_java",
        srcs = [create_file(
            name = test_name + "/AString.kt",
            content = """
package test

import com.foo.R.string.a_string

fun aString(): String = "a_string=" + a_string
""",
        )],
        r_java = ":foo_resources",
    )

    _kt_jvm_compile(
        name = "kt_jvm_compile_using_kt_jvm_compile_with_r_java",
        srcs = [create_file(
            name = test_name + "/ABString.kt",
            content = """
package test

import com.foo.R.string.b_string

fun bString(): String = "b_string=" + b_string

fun abString(): String = aString() + bString()
""",
        )],
        deps = [":kt_jvm_compile_with_r_java"],
    )

    # If a failure occurs, it will be at build time.
    build_test(
        name = test_name,
        targets = [":kt_jvm_compile_using_kt_jvm_compile_with_r_java"],
    )
    return test_name

def _test_kt_jvm_compile_with_illegal_r_java():
    test_name = "kt_jvm_compile_with_illegal_r_java_test"

    native.java_library(
        name = "foo",
        srcs = [create_file(
            name = test_name + "/java/com/foo/Foo.java",
            content = """
package com.foo;

public class Foo {}
""",
        )],
    )
    _kt_jvm_compile(
        name = "kt_jvm_compile_with_illegal_r_java",
        srcs = [create_file(
            name = test_name + "/AString.kt",
            content = """
package test

import com.foo.Foo

fun bar(): String = "Bar"
""",
        )],
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
        r_java = ":foo",
    )
    assert_failure_test(
        name = test_name,
        target_under_test = ":kt_jvm_compile_with_illegal_r_java",
        msg_contains = "illegal dependency provided for r_java",
    )
    return test_name

def _test_kt_jvm_compile_with_r_java_as_first_dep():
    test_name = "kt_jvm_compile_with_r_java_as_first_dep_test"

    # Note: The R from an android_library must be the first dependency in
    # the classpath to prevent another libraries R from being used for
    # compilation. If the ordering is incorrect, compiletime failures will
    # occur as the depot relies on this ordering.

    native.java_library(
        name = "foo_with_symbol_resources",
        srcs = [create_file(
            name = test_name + "/with_symbol/java/com/foo/R.java",
            content = """
package com.foo;

public final class R {
  public static final class string {
    public static int a_string=0x00000001;
  }
}
""",
        )],
    )

    native.java_library(
        name = "foo_without_symbol_resources",
        srcs = [create_file(
            name = test_name + "/without_symbol/java/com/foo/R.java",
            content = """
package com.foo;

public final class R {
  public static final class string {
  }
}
""",
        )],
    )

    _kt_jvm_compile(
        name = "kt_jvm_compile_with_r_java_as_first_dep",
        srcs = [create_file(
            name = test_name + "/AString.kt",
            content = """
package test

import com.foo.R.string.a_string

fun aString(): String = "a_string=" + a_string
""",
        )],
        r_java = ":foo_with_symbol_resources",
        deps = [":foo_without_symbol_resources"],
    )

    # If a failure occurs, it will be at build time.
    build_test(
        name = test_name,
        targets = [":kt_jvm_compile_with_r_java_as_first_dep"],
    )
    return test_name

def _test_kt_jvm_compile_without_srcs_for_android():
    test_name = "kt_jvm_compile_without_srcs_for_android_test"

    # This is a common case for rules like android_library where Kotlin sources
    # could be empty, due to the rule being used for resource processing. For
    # this scenario, historically, rules continue to produce empty Jars.
    _kt_jvm_compile(
        name = "kt_jvm_compile_without_srcs_for_android",
        rule_family = common.RULE_FAMILY.ANDROID_LIBRARY,
    )

    # If a failure occurs, it will be at build time.
    build_test(
        name = test_name,
        targets = [":kt_jvm_compile_without_srcs_for_android"],
    )
    return test_name

def _test_kt_jvm_compile_without_srcs_for_jvm():
    test_name = "kt_jvm_compile_without_srcs_for_jvm_test"

    _kt_jvm_compile(
        name = "kt_jvm_compile_without_srcs_for_jvm",
        srcs = [],
        common_srcs = [],
        exports = [],
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    )
    assert_failure_test(
        name = test_name,
        target_under_test = ":kt_jvm_compile_without_srcs_for_jvm",
        msg_contains = "Expected one of (srcs, common_srcs, exports) is not empty",
    )
    return test_name

def _test_kt_jvm_compile_without_srcs_and_with_exports():
    test_name = "kt_jvm_compile_without_srcs_and_with_exports_test"

    _kt_jvm_compile(
        name = "bar_lib",
        srcs = [create_file(
            name = test_name + "/Bar.kt",
            content = """
package test

fun bar(): String = "Bar"
""",
        )],
    )

    _kt_jvm_compile(
        name = "kt_jvm_compile_without_srcs_and_with_exports",
        exports = [":bar_lib"],
    )

    _kt_jvm_compile(
        name = "foo_bar_lib",
        srcs = [create_file(
            name = test_name + "/FooBar.kt",
            content = """
package test

fun fooBar(): String = "Foo" + bar()
""",
        )],
        deps = [":kt_jvm_compile_without_srcs_and_with_exports"],
    )

    # If a failure occurs, it will be at build time.
    build_test(
        name = test_name,
        targets = [":foo_bar_lib"],
    )
    return test_name

def _test_kt_jvm_compile_unsupported_src_artifacts():
    test_name = "kt_jvm_compile_unsupported_src_artifacts_test"

    kt_src = create_file(
        name = test_name + "/src.kt",
        content = "",
    )
    kt_dir = create_dir(
        name = test_name + "/kotlin",
        subdir = "",
        srcs = [create_file(
            name = test_name + "/dir.kt",
            content = "",
        )],
    )
    java_src = create_file(
        name = test_name + "/src.java",
        content = "",
    )
    java_dir = create_dir(
        name = test_name + "/java",
        subdir = "",
        srcs = [create_file(
            name = test_name + "/dir.java",
            content = "",
        )],
    )
    java_srcjar = create_file(
        name = test_name + "/java.srcjar",
        content = "",
    )
    _kt_jvm_compile(
        name = test_name + "_expected_lib",
        srcs = [kt_src, kt_dir, java_src, java_dir, java_srcjar],
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    )

    unexpected_file = create_file(
        name = test_name + "/src.unexpected",
        content = "",
    )
    _kt_jvm_compile(
        name = test_name + "_unexpected_lib",
        srcs = [unexpected_file],
        deps = [test_name + "_expected_lib"],
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    )

    assert_failure_test(
        name = test_name,
        target_under_test = test_name + "_unexpected_lib",
        msg_contains = "/src.unexpected",
    )
    return test_name

def test_suite(name = None):
    native.test_suite(
        name = name,
        tests = [
            _test_kt_jvm_compile_unsupported_src_artifacts(),
            _test_kt_jvm_compile_using_kt_jvm_compile_with_r_java(),
            _test_kt_jvm_compile_with_illegal_r_java(),
            _test_kt_jvm_compile_with_r_java_as_first_dep(),
            _test_kt_jvm_compile_without_srcs_for_android(),
            _test_kt_jvm_compile_without_srcs_for_jvm(),
            _test_kt_jvm_compile_without_srcs_and_with_exports(),
        ],
    )
