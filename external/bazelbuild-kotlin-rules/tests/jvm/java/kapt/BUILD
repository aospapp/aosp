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

load(
    "//kotlin:rules.bzl",
    "kt_jvm_library",
    "kt_jvm_test",
)
load("@bazel_skylib//rules:build_test.bzl", "build_test")

licenses(["notice"])

package(default_visibility = [
    "//tests/android/java/com/google/local_test:__subpackages__",
])

build_test(
    name = "kapt_regressions",
    tags = ["darwin_x86_64_compatible"],
    targets = [
        ":test_interface_with_default_impls",
        ":test_javac",
        ":test_kapt",
        ":test_javac",
        ":test_javac_with_exported_plugin",
        ":test_kapt_with_exported_plugin",
        ":test_no_processors",
        ":test_noop",
        ":test_noop_processors_ignored",
        ":test_processor_flags",
        ":test_processor_with_data",
        ":test_reference",
        ":test_stubs",
        ":test_direct_classpath",
    ],
)

# Regression test for no processor outputs (b/79878966)
# and running non-service processors (b/110540324)
kt_jvm_library(
    name = "test_noop",
    srcs = ["Noop.kt"],
    plugins = [
        ":non_service_processor",
    ],
)

kt_jvm_library(
    name = "test_interface_with_default_impls",
    srcs = [
        "InterfaceWithDefaultImpls.kt",
    ],
    plugins = [":AP"],  # trigger kapt for regression test (b/121222399)
)

kt_jvm_library(
    name = "test_kapt",
    srcs = [
        "KTest.kt",
    ],
    plugins = [":AP"],
    deps = [
        ":anno",
    ],
)

# Test support for -A processor flags (b/134963914).
kt_jvm_library(
    name = "test_processor_flags",
    srcs = ["FlagTest.kt"],
    javacopts = ["-Akapt.AP.indexPrefix=Gen"],
    plugins = [":AP"],
    deps = [":anno"],
)

# Skip kapt altogether when no plugin defines a processor (b/110540324).
kt_jvm_library(
    name = "test_no_processors",
    srcs = [
        "Noop.kt",
    ],
    plugins = [
        ":ignored_failing_processor",  # would fail if run
    ],
    deps = [
        ":anno",
    ],
)

# Skip plugins without processors, for consistency with how Blaze runs javac (b/110540324).
kt_jvm_library(
    name = "test_noop_processors_ignored",
    srcs = [
        "KTest.kt",
    ],
    plugins = [
        ":AP",
        ":ignored_failing_processor",  # would fail if run
    ],
    deps = [
        ":anno",
    ],
)

# Use kapt to annotation-process a .java file
kt_jvm_library(
    name = "test_kapt_java",
    srcs = [
        "Noop.kt",  # needed so kapt is used
        "Test.java",
    ],
    plugins = [":AP"],
    deps = [
        ":anno",
    ],
)

# No .kt sources: javac is used for annotation processing
kt_jvm_library(
    name = "test_javac",
    srcs = ["Test.java"],
    plugins = [":AP"],
    deps = [
        ":anno",
    ],
)

# Test for java_library picking up exported_plugin from kt_jvm_library
java_library(
    name = "test_javac_with_exported_plugin",
    srcs = ["Test.java"],
    deps = [
        ":anno_with_plugin",
    ],
)

# Reference build with java_library
java_library(
    name = "test_reference",
    srcs = ["Test.java"],
    plugins = [":AP"],
    deps = [
        ":anno",
    ],
)

# This target compiles repro code for b/118338417 and b/110373008.  Since these are issues with the
# kapt-generated .java stub files, which aren't currently used by the Kotlin build rules, this
# target compiles, but we can manually inspect the stubs to see if issues were fixed.
# TODO: Turn this into a dump test asserting the content of the generated stubs
kt_jvm_library(
    name = "test_stubs",
    srcs = [
        "InnerEnumImport.kt",  # repro for https://youtrack.jetbrains.net/issue/KT-28220
        "SealedClasses.kt",  # repro for https://youtrack.jetbrains.net/issue/KT-29924
        "StaticImport.kt",  # Static* repro https://youtrack.jetbrains.net/issue/KT-25071 (fixed)
        "StaticMethod.java",
    ],
    plugins = [":AP"],
)

java_plugin(
    name = "AP",
    generates_api = 1,
    processor_class = "kapt.AP",
    deps = [":AP_lib"],
)

java_library(
    name = "AP_lib",
    srcs = ["AP.java"],
    deps = [
        ":anno",
        "//bazel:auto_service",
    ],
)

# Processor that doesn't advertise itself to j.u.ServiceLoader (regression for b/110540324)
java_plugin(
    name = "non_service_processor",
    srcs = ["NonServiceProcessor.java"],
    processor_class = "kapt.NonServiceProcessor",
)

# Processor that would fail but doesn't declare processor_class and hence shouldn't run.
java_plugin(
    name = "ignored_failing_processor",
    srcs = ["FailingProcessor.java"],
    deps = [
        "//bazel:auto_service",
    ],
)

java_library(
    name = "anno",
    srcs = ["Count.java"],
)

java_library(
    name = "anno-android",
    srcs = ["Count.java"],
)

kt_jvm_library(
    name = "anno_with_plugin",
    srcs = ["Count.java"],
    exported_plugins = [":AP"],
)

kt_jvm_library(
    name = "test_kapt_with_exported_plugin",
    srcs = ["KTest.kt"],
    deps = [
        ":anno_with_plugin",
    ],
)

# Regression test for b/199932860: processor with data dependency
java_plugin(
    name = "ProcessorWithData",
    data = ["MakeHelperClass.java.template"],
    processor_class = "kapt.ProcessorWithData",
    deps = [":processor_with_data"],
)

kt_jvm_library(
    name = "processor_with_data",
    srcs = [
        "MakeHelper.kt",
        "ProcessorWithData.kt",
    ],
    deps = [
        "//bazel:auto_service",
    ],
)

kt_jvm_library(
    name = "make_helper",
    srcs = ["MakeHelper.kt"],
    exported_plugins = [":ProcessorWithData"],
)

kt_jvm_library(
    name = "test_processor_with_data",
    srcs = ["TriggerHelper.kt"],
    deps = [":make_helper"],
)

kt_jvm_library(
    name = "generates_services",
    srcs = ["GeneratesServices.kt"],
    deps = [
        "//bazel:auto_service",
    ],
)

kt_jvm_test(
    name = "GeneratesServicesTest",
    srcs = ["GeneratesServicesTest.kt"],
    tags = ["darwin_x86_64_compatible"],
    deps = [
        ":generates_services",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
    ],
)

kt_jvm_library(
    name = "dagger_c",
    testonly = 1,
    srcs = ["DaggerC.kt"],
    deps = [
        "@maven//:javax_inject_jsr330_api",
    ],
)

kt_jvm_library(
    name = "dagger_b",
    testonly = 1,
    srcs = ["DaggerB.kt"],
    deps = [
        ":dagger_c",
        "@maven//:javax_inject_jsr330_api",
    ],
)

kt_jvm_library(
    name = "dagger_a",
    testonly = 1,
    srcs = ["DaggerA.kt"],
    deps = [
        ":dagger_b",
        ":dagger_c",
        "@dagger",
    ],
)

# Tests a scenario where Dagger generates code referencing indirect dependencies, which can confuse
# Blaze's direct classpath optimization for running Turbine.
kt_jvm_library(
    name = "test_direct_classpath",
    testonly = 1,
    srcs = ["DaggerTopLevel.kt"],
    deps = [
        ":dagger_a",
    ],
)

# Demonstrates a simple compile test that uses Kotlin kapt stubs (b/199411692)
kt_jvm_test(
    name = "APTest",
    srcs = ["APTest.kt"],
    tags = ["darwin_x86_64_compatible"],
    deps = [
        ":AP_lib",
        "@maven//:com_google_testing_compile_compile_testing",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
    ],
)
