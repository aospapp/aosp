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

"""Rules for test."""

load("//kotlin:jvm_library.bzl", "kt_jvm_library")
load("//tests/analysis:util.bzl", "ONLY_FOR_ANALYSIS_TEST_TAGS")
load("//:visibility.bzl", "RULES_KOTLIN")

def _kt_jvm_library_for_test(name, **kwargs):
    kt_jvm_library(
        name = name,
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
        **kwargs
    )
    return name

def _java_library_for_test(name, **kwargs):
    native.java_library(
        name = name,
        tags = ONLY_FOR_ANALYSIS_TEST_TAGS,
        **kwargs
    )
    return name

rules_for_test = struct(
    kt_jvm_library = _kt_jvm_library_for_test,
    java_library = _java_library_for_test,
)
