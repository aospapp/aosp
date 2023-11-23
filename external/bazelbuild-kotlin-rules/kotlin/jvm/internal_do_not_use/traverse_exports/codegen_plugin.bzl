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

"""kt_codegen_plugin_visitor"""

load("//kotlin:codegen_plugin.internal.bzl", "KtCodegenPluginInfo")
load("//:visibility.bzl", "RULES_KOTLIN")

def _get_kt_codegen_plugins(_target, ctx_rule):
    return [
        t[KtCodegenPluginInfo]
        for t in getattr(ctx_rule.attr, "exported_plugins", [])
        if KtCodegenPluginInfo in t
    ]

kt_codegen_plugin_visitor = struct(
    name = "codegen_plugins",
    visit_target = _get_kt_codegen_plugins,
    filter_edge = None,
    finish_expansion = None,
    process_unvisited_target = None,
)
