# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("//build/bazel/rules/android:rules.bzl", "aar_import", "android_binary", "android_library")
load("//build/bazel/rules/cc:cc_library_shared.bzl", "cc_library_shared")
load("//build/bazel/rules/cc:cc_library_static.bzl", "cc_library_static")

package(default_applicable_licenses = ["//build/soong/licenses:Android-Apache-2.0"])

android_binary(
    name = "app",
    manifest = "AndroidManifest.xml",
    sdk_version = "current",
    target_compatible_with = ["//build/bazel/platforms/os:android"],
    deps = [
        ":applib",
    ],
)

android_binary(
    name = "app-cert-string",
    certificate_name = "platform",
    manifest = "AndroidManifest.xml",
    sdk_version = "current",
    target_compatible_with = ["//build/bazel/platforms/os:android"],
    deps = [
        ":applib",
    ],
)

android_binary(
    name = "app-cert-module",
    certificate = "//build/make/target/product/security:aosp-testkey",
    manifest = "AndroidManifest.xml",
    sdk_version = "current",
    target_compatible_with = ["//build/bazel/platforms/os:android"],
    deps = [
        ":applib",
    ],
)

android_library(
    name = "applib",
    srcs = [
        "Jni.java",
        "MainActivity.java",
        "some_kotlin.kt",
    ],
    manifest = "AndroidManifest.xml",
    resource_files = glob(["res/**"]),
    sdk_version = "current",
    target_compatible_with = ["//build/bazel/platforms/os:android"],
    deps = [
        # TODO(b/240555494): re-enable JNI when it is supported
        # ":jni",
        ":lib",
    ],
)

android_library(
    name = "lib",
    srcs = ["Lib.java"],
    sdk_version = "current",
    target_compatible_with = ["//build/bazel/platforms/os:android"],
)

cc_library_shared(
    name = "jni",
    srcs = ["jni.cc"],
    deps = [":jni_dep"],
)

cc_library_static(
    name = "jni_dep",
    srcs = ["jni_dep.cc"],
    hdrs = ["jni_dep.h"],
    deps = ["//libnativehelper:jni_headers"],
)

aar_import(
    name = "import",
    aar = "example_lib.aar",
    sdk_version = "32",
    target_compatible_with = ["//build/bazel/platforms/os:android"],
)
