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

"""Kotlin/JVM toolchain."""

load(":kt_jvm_toolchains.bzl", "kt_jvm_toolchains")
load("@bazel_skylib//:bzl_library.bzl", "bzl_library")

licenses(["notice"])

package(default_visibility = ["//:internal"])

toolchain_type(name = kt_jvm_toolchains.name)

# Toolchain resolution must match :kt_jvm_toolchain_impl. Therefore, there's no
# need for platform-specific toolchain declarations.
toolchain(
    name = "kt_jvm_toolchain",
    toolchain = ":kt_jvm_toolchain_impl",
    toolchain_type = kt_jvm_toolchains.type,
)

alias(
    name = "kt_jvm_toolchain_impl",
    actual = select({
        "@bazel_platforms//os:macos": ":kt_jvm_toolchain_macos_impl",
        "//conditions:default": ":kt_jvm_toolchain_default_impl",
    }),
    visibility = ["//visibility:public"],
)

kt_jvm_toolchains.declare(
    name = "kt_jvm_toolchain_macos_impl",
    enable_turbine_direct = False,
)

kt_jvm_toolchains.declare(
    name = "kt_jvm_toolchain_default_impl",
)
