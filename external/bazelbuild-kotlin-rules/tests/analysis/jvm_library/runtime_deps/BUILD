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

load("//tests/analysis:for_test.bzl", "rules_for_test")
load("//tests/analysis:jvm_library_test.bzl", "jvm_library_test")

package(default_testonly = True)

licenses(["notice"])

rules_for_test.java_library(
    name = "runtime_deps_test_dep",
    srcs = [],
)

jvm_library_test(
    name = "runtime_deps_test",
    target_under_test = rules_for_test.kt_jvm_library(
        name = "runtime_deps",
        srcs = [
            "Input.kt",
        ],
        runtime_deps = [
            ":runtime_deps_test_dep",
        ],
    ),
)
