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

"""Rule for asserting plugin propagation."""

load("@bazel_skylib//lib:sets.bzl", "sets")
load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("//kotlin:traverse_exports.bzl", "kt_traverse_exports")
load("//:visibility.bzl", "RULES_KOTLIN")

def _assert_propagation_impl(ctx):
    expected_ids = sets.make(ctx.attr.expected_plugin_ids)
    actual_ids = sets.make([
        p.plugin_id
        for p in kt_traverse_exports.expand_compiler_plugins(ctx.attr.deps).to_list()
    ])

    if not sets.is_equal(expected_ids, actual_ids):
        fail("Expected IDs %s, actual IDs %s" % (sets.to_list(expected_ids), sets.to_list(actual_ids)))

    return [
        # Needed for kt_traverse_exports.aspect
        JavaInfo(
            compile_jar = ctx.file._empty_jar,
            output_jar = ctx.file._empty_jar,
        ),
    ]

_assert_propagation = rule(
    implementation = _assert_propagation_impl,
    attrs = dict(
        exports = attr.label_list(),
        exported_plugins = attr.label_list(),
        expected_plugin_ids = attr.string_list(),
        deps = attr.label_list(aspects = [kt_traverse_exports.aspect]),
        _empty_jar = attr.label(
            allow_single_file = True,
            default = "//tests/analysis/compiler_plugin:empty_jar",
        ),
    ),
)

def assert_propagation_test(name, **kwargs):
    _assert_propagation(name = name, **kwargs)

    build_test(name = name + "_build", targets = [name])
