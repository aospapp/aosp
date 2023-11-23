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

"""A fake impl of kt_compiler_plugin."""

load("//kotlin:compiler_plugin.bzl", "KtCompilerPluginInfo")
load("//:visibility.bzl", "RULES_KOTLIN")

def _kt_fake_compiler_plugin_impl(ctx):
    return [
        KtCompilerPluginInfo(
            plugin_id = "fake",
            jar = ctx.file._jar,
            args = [],
        ),
    ]

kt_fake_compiler_plugin = rule(
    implementation = _kt_fake_compiler_plugin_impl,
    attrs = dict(
        _jar = attr.label(
            allow_single_file = True,
            default = "//tests/analysis/compiler_plugin:empty_jar",
        ),
    ),
    provides = [KtCompilerPluginInfo],
)
