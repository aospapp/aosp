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
load(":assert_propagation_test.bzl", "assert_propagation_test")

licenses(["notice"])

assert_propagation_test(
    name = "f",
    expected_plugin_ids = ["1"],
    deps = [":e"],
)

assert_propagation_test(
    name = "e",
    expected_plugin_ids = [],
    exports = [":a"],
)

assert_propagation_test(
    name = "d",
    expected_plugin_ids = [
        "1",
        "2",
    ],
    deps = [
        ":a",
        ":b",
    ],
)

assert_propagation_test(
    name = "c",
    expected_plugin_ids = ["2"],
    deps = [":b"],
)

assert_propagation_test(
    name = "b",
    expected_plugin_ids = ["1"],
    exported_plugins = [":2"],
    deps = [":a"],
)

assert_propagation_test(
    name = "a",
    expected_plugin_ids = [],
    exported_plugins = [":1"],
)

kt_compiler_plugin(
    name = "1",
    jar = "//tests/analysis/compiler_plugin:empty_jar",
    plugin_id = "1",
)

kt_compiler_plugin(
    name = "2",
    jar = "//tests/analysis/compiler_plugin:empty_jar",
    plugin_id = "2",
)

kt_compiler_plugin(
    name = "3",
    jar = "//tests/analysis/compiler_plugin:empty_jar",
    plugin_id = "3",
)
