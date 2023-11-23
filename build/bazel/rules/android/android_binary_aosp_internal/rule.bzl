"""
Copyright (C) 2023 The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

load("@rules_android//rules/android_binary_internal:rule.bzl", "make_rule", "sanitize_attrs")
load(":impl.bzl", _impl = "impl")

android_binary_aosp_internal = make_rule(implementation = _impl)

def android_binary_aosp_internal_macro(**attrs):
    """android_binary_internal rule.

    Args:
      **attrs: Rule attributes
    """
    android_binary_aosp_internal(**sanitize_attrs(attrs))
