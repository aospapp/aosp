# Copyright (C) 2022 The Android Open Source Project
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

"""Starlark rules for building Android apps."""

load(
    ":aar_import.bzl",
    _aar_import = "aar_import",
)
load(
    "@rules_android//rules:rules.bzl",
    _android_application = "android_application",
)
load(
    "@rules_android//rules:rules.bzl",
    _android_ndk_repository = "android_ndk_repository",
)
load(
    "@rules_android//rules:rules.bzl",
    _android_sdk = "android_sdk",
)
load(
    "@rules_android//rules:rules.bzl",
    _android_sdk_repository = "android_sdk_repository",
)
load(
    "@rules_android//rules:rules.bzl",
    _android_tools_defaults_jar = "android_tools_defaults_jar",
)
load(
    ":android_app_certificate.bzl",
    _android_app_certificate = "android_app_certificate",
)
load(
    ":android_binary.bzl",
    _android_binary = "android_binary",
)
load(
    ":android_library.bzl",
    _android_library = "android_library",
)

aar_import = _aar_import
android_application = _android_application
android_app_certificate = _android_app_certificate
android_binary = _android_binary
android_library = _android_library
android_ndk_repository = _android_ndk_repository
android_sdk = _android_sdk
android_sdk_repository = _android_sdk_repository
android_tools_defaults_jar = _android_tools_defaults_jar
