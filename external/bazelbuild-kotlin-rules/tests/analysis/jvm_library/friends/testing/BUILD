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

load("//kotlin:rules.bzl", "kt_jvm_library")
load("//tests/analysis:for_test.bzl", "rules_for_test")
load("//tests/analysis:jvm_library_test.bzl", "jvm_library_test")
load("//tests/analysis:util.bzl", "ONLY_FOR_ANALYSIS_TEST_TAGS")

package(
    default_testonly = True,
    default_visibility = ["//tests/analysis/jvm_library/friends:__subpackages__"],
)

licenses(["notice"])

jvm_library_test(
    name = "has_kt_exported_friend_impl_package_test",
    expected_friend_jar_names = [
        "libkt_exports_friend-compile.jar",
        "libfriend-compile.jar",
    ],
    target_under_test = rules_for_test.kt_jvm_library(
        name = "has_kt_exported_friend_impl_package",
        srcs = ["Input.kt"],
        deps = [":kt_exports_friend"],
    ),
)

jvm_library_test(
    name = "has_direct_friend_impl_package_test",
    expected_friend_jar_names = [
        "libfriend-compile.jar",
    ],
    target_under_test = rules_for_test.kt_jvm_library(
        name = "has_direct_friend_impl_package",
        srcs = ["Input.kt"],
        deps = ["//tests/analysis/jvm_library/friends:friend"],
    ),
)

jvm_library_test(
    name = "no_kt_exported_friend_sibling_package_test",
    expected_friend_jar_names = [
        "libkt_exports_subfriend-compile.jar",
        # Absent # "subfriend-compile.jar"
    ],
    target_under_test = rules_for_test.kt_jvm_library(
        name = "no_kt_exported_friend_sibling_package",
        srcs = ["Input.kt"],
        deps = [":kt_exports_subfriend"],
    ),
)

jvm_library_test(
    name = "no_direct_friend_sibling_package_test",
    expected_friend_jar_names = [],
    target_under_test = rules_for_test.kt_jvm_library(
        name = "no_direct_friend_sibling_package",
        srcs = ["Input.kt"],
        deps = ["//tests/analysis/jvm_library/friends/sub:subfriend"],
    ),
)

kt_jvm_library(
    name = "kt_exports_subfriend",
    srcs = ["Input.kt"],
    tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    exports = ["//tests/analysis/jvm_library/friends/sub:subfriend"],
)

kt_jvm_library(
    name = "kt_exports_friend",
    srcs = ["Input.kt"],
    tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
    exports = ["//tests/analysis/jvm_library/friends:friend"],
)

kt_jvm_library(
    name = "testingfriend",
    srcs = ["Input.kt"],
    tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
)
