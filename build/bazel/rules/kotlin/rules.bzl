# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http:#www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load(
    "@rules_kotlin//kotlin:compiler_opt.bzl",
    _kt_compiler_opt = "kt_compiler_opt",
)
load(
    ":kt_jvm_library.bzl",
    _kt_jvm_library = "kt_jvm_library",
)

kt_jvm_library = _kt_jvm_library
kt_compiler_opt = _kt_compiler_opt
