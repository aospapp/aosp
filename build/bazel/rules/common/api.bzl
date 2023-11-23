# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
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

# An API level, can be a finalized (numbered) API, a preview (codenamed) API, or
# the future API level (10000). Can be parsed from a string with
# parse_api_level_with_version.

load("@bazel_skylib//lib:dicts.bzl", "dicts")
load("@soong_injection//api_levels:api_levels.bzl", "api_levels_released_versions")
load("@soong_injection//api_levels:platform_versions.bzl", "platform_versions")

_NONE_API_LEVEL_INT = -1
_PREVIEW_API_LEVEL_BASE = 9000  # Base constant for preview API levels.
_FUTURE_API_LEVEL_INT = 10000  # API Level associated with an arbitrary future release

# TODO(b/271280342): access these variables in a transition friendly way.
_PLATFORM_SDK_FINAL = platform_versions.platform_sdk_final
_PLATFORM_SDK_VERSION = platform_versions.platform_sdk_version
_PLATFORM_SDK_CODENAME = platform_versions.platform_sdk_codename
_PLATFORM_VERSION_ACTIVE_CODENAMES = platform_versions.platform_version_active_codenames

# Dict of unfinalized codenames to a placeholder preview API int.
_preview_codenames_to_ints = {
    codename: _PREVIEW_API_LEVEL_BASE + i
    for i, codename in enumerate(_PLATFORM_VERSION_ACTIVE_CODENAMES)
}

# Returns true if a string or int version is in preview (not finalized).
def _is_preview(version):
    if type(version) == "string" and version.isdigit():
        # normalize int types internally
        version = int(version)

    # Future / current is considered as a preview.
    if version == "current" or version == _FUTURE_API_LEVEL_INT:
        return True

    # api can be either the codename or the int level (9000+)
    return version in _preview_codenames_to_ints or version in _preview_codenames_to_ints.values()

# Return 10000 for unfinalized versions, otherwise return unchanged.
def _final_or_future(version):
    if _is_preview(version):
        return _FUTURE_API_LEVEL_INT
    else:
        return version

_final_codename = {
    "current": _final_or_future(_PLATFORM_SDK_VERSION),
} if _PLATFORM_SDK_FINAL and _PLATFORM_SDK_VERSION else {}

_api_levels_with_previews = dicts.add(api_levels_released_versions, _preview_codenames_to_ints)
_api_levels_with_final_codenames = dicts.add(api_levels_released_versions, _final_codename)  # @unused

# parse_api_level_from_version is a Starlark implementation of ApiLevelFromUser
# at https://cs.android.com/android/platform/superproject/+/master:build/soong/android/api_levels.go;l=221-250;drc=5095a6c4b484f34d5c4f55a855d6174e00fb7f5e
def _parse_api_level_from_version(version):
    """converts the given string `version` to an api level

    Args:
        version: must be non-empty. Inputs that are not "current", known
        previews, finalized codenames, or convertible to an integer will return
        an error.

    Returns: The api level as an int.
    """
    if version == "":
        fail("API level string must be non-empty")

    if version == "current":
        return _FUTURE_API_LEVEL_INT

    if _is_preview(version):
        return _preview_codenames_to_ints.get(version) or int(version)

    # Not preview nor current.
    #
    # If the level is the codename of an API level that has been finalized, this
    # function returns the API level number associated with that API level. If
    # the input is *not* a finalized codename, the input is returned unmodified.
    canonical_level = api_levels_released_versions.get(version)
    if not canonical_level:
        if not version.isdigit():
            fail("version %s could not be parsed as integer and is not a recognized codename" % version)
        return int(version)
    return canonical_level

# Starlark implementation of DefaultAppTargetSDK from build/soong/android/config.go
# https://cs.android.com/android/platform/superproject/+/master:build/soong/android/config.go;l=875-889;drc=b0dc477ef740ec959548fe5517bd92ac4ea0325c
# check what you want returned for codename == "" case before using
def _default_app_target_sdk():
    """default_app_target_sdk returns the API level that platform apps are targeting.
       This converts a codename to the exact ApiLevel it represents.
    """
    if _PLATFORM_SDK_FINAL:
        return _PLATFORM_SDK_VERSION

    codename = _PLATFORM_SDK_CODENAME
    if not codename:
        # soong returns NoneApiLevel here value: "(no version)", number: -1, isPreview: true
        #
        # fail fast instead of returning an arbitrary value.
        fail("Platform_sdk_codename must be set.")

    if codename == "REL":
        fail("Platform_sdk_codename should not be REL when Platform_sdk_final is false")

    return _parse_api_level_from_version(codename)

api = struct(
    NONE_API_LEVEL = _NONE_API_LEVEL_INT,
    FUTURE_API_LEVEL = _FUTURE_API_LEVEL_INT,
    is_preview = _is_preview,
    final_or_future = _final_or_future,
    default_app_target_sdk = _default_app_target_sdk,
    parse_api_level_from_version = _parse_api_level_from_version,
    api_levels = _api_levels_with_previews,
)
