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

"""An assertion on kt_compiler_plugin analysis."""

load("//kotlin:compiler_plugin.bzl", "KtCompilerPluginInfo")
load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")
load("//:visibility.bzl", "RULES_KOTLIN")

def _test_impl(ctx):
    env = analysistest.begin(ctx)
    info = ctx.attr.target_under_test[KtCompilerPluginInfo]

    asserts.equals(env, info.plugin_id, ctx.attr.expected_id)
    asserts.equals(env, info.jar, ctx.file.expected_jar)
    asserts.equals(env, info.args, ctx.attr.expected_args)

    return analysistest.end(env)

assert_compiler_plugin_test = analysistest.make(
    impl = _test_impl,
    attrs = dict(
        expected_id = attr.string(),
        expected_jar = attr.label(allow_single_file = True, cfg = "exec"),
        expected_args = attr.string_list(),
    ),
)
