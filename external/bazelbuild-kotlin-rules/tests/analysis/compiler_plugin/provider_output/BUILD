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

load("//kotlin:compiler_plugin.bzl", "kt_compiler_plugin")
load("//tests/analysis:util.bzl", "create_file")
load("@bazel_skylib//rules:build_test.bzl", "build_test")
load(":assert_compiler_plugin_test.bzl", "assert_compiler_plugin_test")

licenses(["notice"])

assert_compiler_plugin_test(
    name = "example_plugin_test",
    expected_args = [
        "plugin:com.google.example:key=value",
    ],
    expected_id = "com.google.example",
    expected_jar = "//tests/analysis/compiler_plugin:empty_jar",
    target_under_test = ":example_plugin",
)

build_test(
    name = "example_plugin_in_java_library_build_test",
    targets = [
        ":example_plugin_in_java_library",
    ],
)

java_library(
    name = "example_plugin_in_java_library",
    srcs = [create_file(
        name = "Tmp.java",
        content = """
          @SuppressWarnings("DefaultPackage")
          class Tmp { }
        """,
    )],
    plugins = [":example_plugin"],
)

kt_compiler_plugin(
    name = "example_plugin",
    args = {
        "key": "value",
    },
    jar = "//tests/analysis/compiler_plugin:empty_jar",
    plugin_id = "com.google.example",
)
