"""
Copyright (C) 2023 The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

load(":merged_txts_test.bzl", "merged_txts_test_suite")
load(":sdk_library_test.bzl", "java_sdk_library_test_suite")
load(":java_system_modules_test.bzl", "java_system_modules_test_suite")
load(":bootclasspath_test.bzl", "bootclasspath_test_suite")
load(":versions_test.bzl", "versions_test_suite")
load(":versions.bzl", "java_versions")
load(":sdk_transition_test.bzl", "sdk_transition_test_suite")
load(":host_for_device_test.bzl", "host_for_device_test_suite")
load("@bazel_skylib//rules:common_settings.bzl", "string_setting")
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "DEFAULT_JAVACOPTS", "default_java_toolchain")
load("@soong_injection//java_toolchain:constants.bzl", "constants")

package(
    default_visibility = ["//visibility:public"],
)

java_sdk_library_test_suite(name = "java_sdk_library_tests")

merged_txts_test_suite(name = "merged_txts_tests")

java_system_modules_test_suite(name = "java_system_modules_tests")

bootclasspath_test_suite(name = "bootclasspath_tests")

versions_test_suite(name = "versions_tests")

sdk_transition_test_suite(name = "sdk_transition_tests")

host_for_device_test_suite(name = "host_for_device_test_suite")

string_setting(
    name = "version",
    build_setting_default = str(java_versions.get_version()),
    values = [str(v) for v in java_versions.ALL_VERSIONS],
)

[
    config_setting(
        name = setting,
        flag_values = {
            "//build/bazel/rules/java:version": str(java_version),
        },
    )
    for java_version, setting in java_versions.VERSION_TO_CONFIG_SETTING.items()
]

# There is no need for both host and device java version build settings in a
# world where every java_*/android_*/kt_* target uses the AOSP-specific
# wrappers. However, there are targets defined by BUILD.tools files within the
# Bazel binary that do not use the wrapper. These would inherit their java
# version from their reverse dependency, which can cause build failures (e.g. an
# android_library_import with java_version=7 has a tools dependency on a
# non-wrapped Bazel java_library that uses lambdas). By using a separate host
# version, we can reset it to its default when in the device configuration, so
# that a subsequent exec transition will use the default java version.
string_setting(
    name = "host_version",
    build_setting_default = str(java_versions.get_version()),
    values = [str(v) for v in java_versions.ALL_VERSIONS],
)

[
    config_setting(
        name = "host_" + setting,
        flag_values = {
            "//build/bazel/rules/java:host_version": str(java_version),
        },
    )
    for java_version, setting in java_versions.VERSION_TO_CONFIG_SETTING.items()
]

java_version_select_dict = {
    "host_" + setting: str(version)
    for version, setting in java_versions.VERSION_TO_CONFIG_SETTING.items()
} | {
    "//conditions:default": str(java_versions.get_version()),
}

default_java_toolchain(
    name = "jdk17_host_toolchain_java",
    # TODO(b/218720643): Support switching between multiple JDKs.
    java_runtime = "//prebuilts/jdk/jdk17:jdk17_runtime",
    misc = DEFAULT_JAVACOPTS + constants.CommonJdkFlags,
    source_version = select(java_version_select_dict),
    target_version = select(java_version_select_dict),
    toolchain_definition = False,
)

toolchain(
    name = "jdk17_host_toolchain_java_definition",
    exec_compatible_with = ["//build/bazel/platforms/os:linux"],
    target_compatible_with = ["//build/bazel/platforms/os:linux"],
    target_settings = [],
    toolchain = ":jdk17_host_toolchain_java",
    toolchain_type = "@bazel_tools//tools/jdk:toolchain_type",
)
