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

"""Kotlin kt_jvm_library rule tests."""

load("//kotlin:jvm_library.bzl", "kt_jvm_library")
load("//tests/analysis:util.bzl", "ONLY_FOR_ANALYSIS_TEST_TAGS", "create_file", "get_action", "get_arg")
load("@bazel_skylib//lib:sets.bzl", "sets")
load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")
load(":assert_failure_test.bzl", "assert_failure_test")
load("//:visibility.bzl", "RULES_KOTLIN")

_DEFAULT_LIST = ["__default__"]

def _test_impl(ctx):
    env = analysistest.begin(ctx)
    actions = analysistest.target_actions(env)
    actual = ctx.attr.target_under_test
    expected = ctx.attr.expected

    asserts.true(
        env,
        JavaInfo in actual,
        "kt_jvm_library did not produce JavaInfo provider.",
    )
    asserts.true(
        env,
        ProguardSpecProvider in actual,
        "Expected a ProguardSpecProvider provider.",
    )

    if "data" in expected:
        expected_data = expected["data"]
        actual_data = _extract_data_runfiles(actual)

        asserts.new_set_equals(
            env,
            sets.make(expected_data),
            sets.make(actual_data),
            """
            FAIL: kt_jvm_library did not produce the expected data dependencies.
            EXPECTED: %s
            ACTUAL: %s
            """ % (expected_data, actual_data),
        )

    expected_exports = []
    for target in ctx.attr.expected_exports:
        asserts.equals(
            env,
            1,
            len(target[JavaInfo].full_compile_jars.to_list()),
            "Not a single compile-time Jar: %s" % target.label,
        )
        expected_exports.extend(target[JavaInfo].full_compile_jars.to_list())
    actual_exports = actual[JavaInfo].full_compile_jars.to_list()

    # TODO: fail if there are *un*expected exports, maybe by making sure
    # that the actual exports are exactly the expected ones plus the Jar(s)
    # produced by this JavaInfo.
    for expected_export in expected_exports:
        asserts.true(
            env,
            expected_export in actual_exports,
            """
            kt_jvm_library did not export %s
            actual: %s
            """ % (expected_export, actual_exports),
        )

    asserts.equals(
        env,
        ctx.attr.expected_exported_processor_classes,
        actual[JavaInfo].plugins.processor_classes.to_list(),
    )

    kt_2_java_compile = get_action(actions, "Kt2JavaCompile")

    if kt_2_java_compile:
        asserts.true(
            env,
            kt_2_java_compile.outputs.to_list()[0].basename.endswith(".jar"),
            "Expected first output to be a JAR (this affects the param file name).",
        )

    if ctx.attr.expected_friend_jar_names != _DEFAULT_LIST:
        friend_paths_arg = get_arg(kt_2_java_compile, "-Xfriend-paths=")
        friend_jar_names = [p.rsplit("/", 1)[1] for p in friend_paths_arg.split(",")] if friend_paths_arg else []
        asserts.set_equals(env, sets.make(ctx.attr.expected_friend_jar_names), sets.make(friend_jar_names))

    asserts.equals(
        env,
        ctx.attr.expect_neverlink,
        len(actual[JavaInfo].transitive_runtime_jars.to_list()) == 0,
        "Mismatch: Expected transitive_runtime_jars iff (neverlink == False)",
    )

    return analysistest.end(env)

_test = analysistest.make(
    impl = _test_impl,
    attrs = dict(
        expected = attr.string_list_dict(),
        expected_exports = attr.label_list(),
        expected_exported_processor_classes = attr.string_list(
            doc = "Annotation processors reported as to be run on depending targets",
        ),
        expected_processor_classes = attr.string_list(
            doc = "Annotation processors reported as run on the given target",
        ),
        expected_friend_jar_names = attr.string_list(
            doc = "Names of all -Xfriend-paths= JARs",
            default = _DEFAULT_LIST,
        ),
        expect_processor_classpath = attr.bool(),
        expect_neverlink = attr.bool(),
    ),
)

jvm_library_test = _test

def _coverage_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    instrumented_files_info = target_under_test[InstrumentedFilesInfo]
    instrumented_files = instrumented_files_info.instrumented_files.to_list()
    asserts.equals(
        env,
        ctx.attr.expected_instrumented_file_basenames,
        [file.basename for file in instrumented_files],
    )
    return analysistest.end(env)

_coverage_test = analysistest.make(
    impl = _coverage_test_impl,
    attrs = {
        "expected_instrumented_file_basenames": attr.string_list(),
    },
    config_settings = {
        "//command_line_option:collect_code_coverage": "1",
        "//command_line_option:instrument_test_targets": "1",
        "//command_line_option:instrumentation_filter": "+tests/analysis[:/]",
    },
)

def _extract_data_runfiles(target):
    return [f.basename for f in target[DefaultInfo].data_runfiles.files.to_list()]

def _test_kt_jvm_library_with_proguard_specs():
    test_name = "kt_jvm_library_with_proguard_specs_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )
    create_file(
        name = test_name + "/salutations.pgcfg",
        content = """
-keep class * {
  *** greeting();
}
""",
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        proguard_specs = [
            test_name + "/salutations.pgcfg",
        ],
    )
    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
    )
    return test_name

def _test_kt_jvm_library_with_resources():
    test_name = "kt_jvm_library_with_resources_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )
    create_file(
        name = test_name + "/salutations.txt",
        content = """
Hi!
""",
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
            "testinputs/Foo.java",
        ],
        resources = [
            test_name + "/salutations.txt",
        ],
    )
    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
    )
    return test_name

def _test_kt_jvm_library_with_plugin():
    test_name = "kt_jvm_library_with_plugin_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )

    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        # Need a working plugin so it can run for the test.
        plugins = ["//bazel:auto_value_plugin"],
    )

    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_processor_classes = ["com.google.auto.value.processor.AutoValueProcessor"],
        expect_processor_classpath = True,
    )
    return test_name

def _test_kt_jvm_library_no_kt_srcs_with_plugin():
    test_name = "kt_jvm_library_no_kt_srcs_with_plugin_test"
    native.java_plugin(
        name = "%s_plugin" % test_name,
        processor_class = test_name,
        srcs = ["testinputs/Foo.java"],  # induce processor_classpath
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = ["testinputs/Bar.java"],
        plugins = [":%s_plugin" % test_name],
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    )
    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_processor_classes = [test_name],
        expect_processor_classpath = True,
    )
    return test_name

def _test_kt_jvm_library_with_non_processor_plugin():
    test_name = "kt_jvm_library_with_non_processor_plugin_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )

    native.java_plugin(
        # no processor_class
        name = "%s_plugin" % test_name,
        srcs = ["testinputs/Foo.java"],
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        plugins = [":%s_plugin" % test_name],
    )

    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_processor_classes = [],  # no processor class so no processing
        expect_processor_classpath = True,  # expect java_plugin's Jar
    )
    return test_name

def _test_kt_jvm_library_with_exported_plugin():
    test_name = "kt_jvm_library_with_exported_plugin_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )
    native.java_plugin(
        name = "%s_plugin" % test_name,
        processor_class = test_name,
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        exported_plugins = [":%s_plugin" % test_name],
    )

    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_exported_processor_classes = [test_name],
        expected_processor_classes = [],  # exported plugin should *not* run on _tut itself
    )
    return test_name

def _test_kt_jvm_library_dep_on_exported_plugin():
    test_name = "kt_jvm_library_dep_on_exported_plugin_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )

    native.java_plugin(
        name = "%s_plugin" % test_name,
        processor_class = test_name,
        srcs = ["testinputs/Foo.java"],  # induce processor_classpath
    )
    kt_jvm_library(
        name = "%s_exports_plugin" % test_name,
        srcs = [test_name + "/Salutations.kt"],
        exported_plugins = [":%s_plugin" % test_name],
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        deps = [":%s_exports_plugin" % test_name],
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    )

    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_processor_classes = [test_name],
        expect_processor_classpath = True,
    )
    return test_name

def _test_kt_jvm_library_java_dep_on_exported_plugin():
    test_name = "kt_jvm_library_java_dep_on_exported_plugin_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )
    native.java_plugin(
        name = "%s_plugin" % test_name,
        processor_class = test_name,
        srcs = ["testinputs/Foo.java"],  # induce processor_classpath
    )
    native.java_library(
        name = "%s_exports_plugin" % test_name,
        exported_plugins = [":%s_plugin" % test_name],
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        deps = [":%s_exports_plugin" % test_name],
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    )

    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_processor_classes = [test_name],
        expect_processor_classpath = True,
    )
    return test_name

def _test_kt_jvm_library_with_exports():
    test_name = "kt_jvm_library_with_exports_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )
    kt_jvm_library(
        name = test_name + "_exp",
        srcs = [test_name + "/Salutations.kt"],
    )
    native.java_library(
        name = test_name + "_javaexp",
        srcs = ["testinputs/Foo.java"],  # need file here so we get a Jar
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        exports = [
            ":%s_exp" % test_name,
            ":%s_javaexp" % test_name,
        ],
    )
    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_exports = [
            ":%s_exp" % test_name,
            ":%s_javaexp" % test_name,
        ],
    )
    return test_name

def _test_kt_jvm_library_with_export_that_exports_plugin():
    test_name = "kt_jvm_library_with_export_that_exports_plugin_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )
    native.java_plugin(
        name = "%s_plugin" % test_name,
        processor_class = test_name,
        srcs = ["testinputs/Foo.java"],  # induce processor_classpath
    )
    kt_jvm_library(
        name = "%s_exports_plugin" % test_name,
        exported_plugins = [":%s_plugin" % test_name],
        srcs = [test_name + "/Salutations.kt"],
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        exports = [":%s_exports_plugin" % test_name],
    )
    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_exports = [":%s_exports_plugin" % test_name],
        expected_exported_processor_classes = [test_name],
    )
    return test_name

def _test_kt_jvm_library_with_java_export_that_exports_plugin():
    test_name = "kt_jvm_library_with_java_export_that_exports_plugin_test"
    create_file(
        name = test_name + "/Salutations.kt",
        content = """
package test

fun greeting(): String = "Hello World!"
""",
    )
    native.java_plugin(
        name = "%s_plugin" % test_name,
        processor_class = test_name,
        srcs = ["testinputs/Foo.java"],  # induce processor_classpath
    )
    native.java_library(
        name = "%s_exports_plugin" % test_name,
        exported_plugins = [":%s_plugin" % test_name],
    )
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [
            test_name + "/Salutations.kt",
        ],
        exports = [":%s_exports_plugin" % test_name],
    )
    _test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_exports = [],  # _exports_plugin has no compile/runtime Jars
        expected_exported_processor_classes = [test_name],
    )
    return test_name

def _test_forbidden_nano_dep():
    test_name = "kt_jvm_library_forbidden_nano_test"

    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [test_name + "/Ignored.kt"],
        deps = [test_name + "_fake_nano_proto_lib"],
                tags = [
            "manual",
            "nobuilder",
        ],
    )
    native.java_library(
        name = test_name + "_fake_nano_proto_lib",
        srcs = [],
                tags = ["nano_proto_library"],
    )
    assert_failure_test(
        name = test_name,
        target_under_test = test_name + "_tut",
        msg_contains = test_name + "_fake_nano_proto_lib : nano_proto_library",
    )
    return test_name

def _test_forbidden_nano_export():
    test_name = "kt_jvm_library_forbidden_nano_export_test"

    kt_jvm_library(
        name = test_name + "_tut",
        srcs = [test_name + "/Ignored.kt"],
        deps = [test_name + "_export"],
                tags = [
            "manual",
            "nobuilder",
        ],
    )
    native.java_library(
        name = test_name + "_export",
        exports = [test_name + "_fake_nano_proto_lib"],
            )
    native.java_library(
        name = test_name + "_fake_nano_proto_lib",
        srcs = [],
                tags = ["nano_proto_library"],
    )
    assert_failure_test(
        name = test_name,
        target_under_test = test_name + "_tut",
        msg_contains = test_name + "_fake_nano_proto_lib : nano_proto_library",
    )
    return test_name

def _test_kt_jvm_library_with_no_sources():
    test_name = "kt_jvm_library_with_no_sources_test"

    kt_jvm_library(
        name = test_name + "_tut",
        tags = [
            "manual",
            "nobuilder",
        ],
    )
    tut_label = str(Label("//tests/analysis:kt_jvm_library_with_no_sources_test_tut"))
    assert_failure_test(
        name = test_name,
        target_under_test = test_name + "_tut",
        msg_contains = "One of {srcs, common_srcs, exports, exported_plugins} of target " + tut_label + " must be non empty",
    )
    return test_name

def _test_kt_jvm_library_coverage():
    test_name = "kt_jvm_library_coverage"
    kt_jvm_library(
        name = test_name + "_tut",
        srcs = ["testinputs/Srcs.kt"],
        common_srcs = ["testinputs/CommonSrcs.kt"],
        deps = [":{}_deps".format(test_name)],
        runtime_deps = [":{}_runtime_deps".format(test_name)],
        data = [":{}_data".format(test_name)],
        resources = [":{}_resources".format(test_name)],
        testonly = True,
    )
    native.java_library(
        name = test_name + "_deps",
        srcs = ["testinputs/Deps.java"],
        testonly = True,
    )
    native.java_library(
        name = test_name + "_runtime_deps",
        srcs = ["testinputs/RuntimeDeps.java"],
        testonly = True,
    )
    native.java_binary(
        name = test_name + "_data",
        main_class = "Data",
        srcs = ["testinputs/Data.java"],
        testonly = True,
    )
    native.java_binary(
        name = test_name + "_resources",
        main_class = "Resources",
        srcs = ["testinputs/Resources.java"],
        testonly = True,
    )
    _coverage_test(
        name = test_name,
        target_under_test = test_name + "_tut",
        expected_instrumented_file_basenames = [
            "Data.java",
            "Deps.java",
            "Resources.java",
            "RuntimeDeps.java",
            "Srcs.kt",
            "CommonSrcs.kt",
        ],
    )
    return test_name

def test_suite(name):
    native.test_suite(
        name = name,
        tests = [
            _test_forbidden_nano_dep(),
            _test_forbidden_nano_export(),
            _test_kt_jvm_library_dep_on_exported_plugin(),
            _test_kt_jvm_library_java_dep_on_exported_plugin(),
            _test_kt_jvm_library_no_kt_srcs_with_plugin(),
            _test_kt_jvm_library_with_export_that_exports_plugin(),
            _test_kt_jvm_library_with_exported_plugin(),
            _test_kt_jvm_library_with_exports(),
            _test_kt_jvm_library_with_java_export_that_exports_plugin(),
            _test_kt_jvm_library_with_no_sources(),
            _test_kt_jvm_library_with_non_processor_plugin(),
            _test_kt_jvm_library_with_plugin(),
            _test_kt_jvm_library_with_proguard_specs(),
            _test_kt_jvm_library_with_resources(),
            _test_kt_jvm_library_coverage(),
        ],
    )
