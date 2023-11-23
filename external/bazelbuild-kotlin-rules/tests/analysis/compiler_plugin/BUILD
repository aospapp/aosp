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

licenses(["notice"])

genrule(
    name = "empty_jar",
    outs = ["empty.jar"],
    cmd = """$(location @bazel_tools//tools/zip:zipper) c $@ "assets/_empty=" """,
    tools = ["@bazel_tools//tools/zip:zipper"],
    visibility = ["//tests/analysis/compiler_plugin:__subpackages__"],
)
