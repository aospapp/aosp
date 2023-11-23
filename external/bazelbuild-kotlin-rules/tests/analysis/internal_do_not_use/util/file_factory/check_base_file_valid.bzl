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

"""Happy tests for FileFactory."""

load("//kotlin/jvm/internal_do_not_use/util:file_factory.bzl", "FileFactory")
load("//tests/analysis:util.bzl", "ONLY_FOR_ANALYSIS_TEST_TAGS")
load("//:visibility.bzl", "RULES_KOTLIN")

def _check_base_file_valid_impl(ctx):
    FileFactory(ctx, ctx.file.base_file)
    return []

_check_base_file_valid = rule(
    implementation = _check_base_file_valid_impl,
    attrs = dict(
        base_file = attr.label(allow_single_file = True, mandatory = True),
    ),
)

def check_base_file_valid(name, tags = [], **kwargs):
    _check_base_file_valid(
        name = name,
        tags = tags + ONLY_FOR_ANALYSIS_TEST_TAGS,
        **kwargs
    )
    return name
