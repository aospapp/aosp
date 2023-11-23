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

# Analysis Tests

load("//tests/analysis:jvm_compile_test.bzl", jvm_compile_test_suite = "test_suite")
load("//tests/analysis:jvm_import_test.bzl", jvm_import_test_suite = "test_suite")
load("//tests/analysis:jvm_library_test.bzl", jvm_library_test_suite = "test_suite")
load("@bazel_skylib//:bzl_library.bzl", "bzl_library")

licenses(["notice"])

jvm_compile_test_suite(name = "jvm_compile_tests")

jvm_library_test_suite(name = "jvm_library_tests")

jvm_import_test_suite(name = "jvm_import_tests")
