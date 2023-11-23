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
load("//tests/analysis:assert_failure_test.bzl", "assert_failure_test")
load("//tests/analysis:jvm_library_test.bzl", "jvm_library_test")
load("//tests/analysis:util.bzl", "create_dir")

package(default_testonly = True)

licenses(["notice"])

jvm_library_test(
    name = "treeartifact_basename_kotlin_test",
    target_under_test = rules_for_test.kt_jvm_library(
        name = "treeartifact_basename_kotlin",
        srcs = [
            create_dir(
                name = "treeartifact_extension/kotlin",
                srcs = [
                    "Input.java",  # TODO: Reject this source
                ],
            ),
        ],
    ),
)

jvm_library_test(
    name = "treeartifact_basename_java_test",
    target_under_test = rules_for_test.kt_jvm_library(
        name = "treeartifact_basename_java",
        srcs = [
            create_dir(
                name = "treeartifact_extension/java",
                srcs = [
                    "Input.kt",  # TODO: Reject this source
                ],
            ),
        ],
    ),
)

assert_failure_test(
    name = "treeartifact_extension_kt_test",
    msg_contains = "/treeartifact_extension.kt",
    target_under_test = rules_for_test.kt_jvm_library(
        name = "treeartifact_extension_kt",
        srcs = [
            create_dir(
                name = "treeartifact_extension.kt",
            ),
        ],
    ),
)

assert_failure_test(
    name = "treeartifact_extension_java_test",
    msg_contains = "/treeartifact_extension.java",
    target_under_test = rules_for_test.kt_jvm_library(
        name = "treeartifact_extension_java",
        srcs = [
            create_dir(
                name = "treeartifact_extension.java",
            ),
        ],
    ),
)

assert_failure_test(
    name = "treeartifact_extension_srcjar_test",
    msg_contains = "/treeartifact_extension.srcjar",
    target_under_test = rules_for_test.kt_jvm_library(
        name = "treeartifact_extension_srcjar",
        srcs = [
            create_dir(
                name = "treeartifact_extension.srcjar",
            ),
        ],
    ),
)

assert_failure_test(
    name = "treeartifact_no_extension_test",
    msg_contains = "/treeartifact_no_extension",
    target_under_test = rules_for_test.kt_jvm_library(
        name = "treeartifact_no_extension",
        srcs = [
            create_dir(
                name = "treeartifact_no_extension_dir",
            ),
        ],
    ),
)
