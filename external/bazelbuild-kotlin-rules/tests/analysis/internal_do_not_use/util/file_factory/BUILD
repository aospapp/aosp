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

load(":happy_test.bzl", "file_factory_happy_test")
load(":check_base_file_valid.bzl", "check_base_file_valid")
load("//tests/analysis:assert_failure_test.bzl", "assert_failure_test")

licenses(["notice"])

file_factory_happy_test(
    name = "happy_test",
)

assert_failure_test(
    name = "base_without_extension_test",
    msg_contains = "file must have an extension",
    target_under_test = check_base_file_valid(
        name = "base_without_extension",
        base_file = "BUILD",
    ),
)

assert_failure_test(
    name = "base_from_other_package_test",
    msg_contains = "file must be from ctx package",
    target_under_test = check_base_file_valid(
        name = "base_from_other_package",
        base_file = "//tests/analysis/internal_do_not_use/util/file_factory/sub",
    ),
)
