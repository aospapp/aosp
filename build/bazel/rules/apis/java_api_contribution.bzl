# Copyright (C) 2022 The Android Open Source Project
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

"""Bazel rules for exporting API contributions of Java libraries"""

load(":api_surface.bzl", "CORE_PLATFORM_API", "INTRA_CORE_API", "MODULE_LIB_API", "PUBLIC_API", "SYSTEM_API", "SYSTEM_SERVER_API", "TEST_API", "TOOLCHAIN_API")

"""A Bazel provider that encapsulates the contributions of a Java library to an API surface"""
JavaApiContributionInfo = provider(
    fields = {
        "name": "Name of the contribution target",
        "api": "Path of partial current.txt file describing the stable APIs of the library. Path is relative to workspace root",
        "api_surfaces": "List of API surfaces that this partial api file contributes to",
    },
)

# Java API surfaces are hierarchical.
# This hierarchy map was created by looking at the stub definitions in frameworks/base/StubLibraries.bp
# Key is the full api surface
# Values are the partial metalava signature files that are combined to generate the full api surface stubs.
_JAVA_FULLAPISURFACE_TO_PARTIALSIGNATUREFILE = {
    PUBLIC_API: [PUBLIC_API],
    SYSTEM_API: [PUBLIC_API, SYSTEM_API],
    TEST_API: [PUBLIC_API, SYSTEM_API, TEST_API],
    MODULE_LIB_API: [PUBLIC_API, SYSTEM_API, MODULE_LIB_API],
    SYSTEM_SERVER_API: [PUBLIC_API, SYSTEM_API, MODULE_LIB_API, SYSTEM_SERVER_API],
    # intracore is publicapi + "@IntraCoreApi".
    # e.g. art.module.intra.core.api uses the following `droiddoc_option`
    # [<hide>, --show-single-annotation libcore.api.IntraCoreApi"]
    # conscrypt and icu4j use similar droidoc_options
    INTRA_CORE_API: [PUBLIC_API, INTRA_CORE_API],
    # CorePlatformApi does not extend PublicApi
    # Each core module is at different stages of transition
    # The status quo in Soong today is
    # 1. conscrypt - Still provides CorePlatformApis
    # 2. i18n - APIs have migrated to Public API surface
    # 3. art - APIs have migrated to ModuleLib API suface
    # This layering complexity will be handled by the build orchestrator and not by API export.
    CORE_PLATFORM_API: [CORE_PLATFORM_API],
    # coreapi does not have an entry here, it really is the public stubs of the 3 core modules
    # (art, conscrypt, i18n)
    TOOLCHAIN_API: [TOOLCHAIN_API],
}

VALID_JAVA_API_SURFACES = _JAVA_FULLAPISURFACE_TO_PARTIALSIGNATUREFILE.keys()

def _java_api_contribution_impl(ctx):
    """Implemenation for the java_api_contribution rule
    This rule does not have any build actions, but returns a `JavaApiContributionInfo` provider object"""

    full_api_surfaces = []

    # The checked-in signature files are parital signatures. e.g. SystemAPI surface
    # (android_system_stubs_current.jar) contains the classes
    # and methods present in current.txt and system-current.txt.
    # The jar representing the full api surface is created by combining these partial signature files.
    for full_api_surface, partials in _JAVA_FULLAPISURFACE_TO_PARTIALSIGNATUREFILE.items():
        if ctx.attr.api_surface in partials:
            full_api_surfaces.append(full_api_surface)

    return [
        JavaApiContributionInfo(
            name = ctx.label.name,
            api = ctx.file.api.path,
            api_surfaces = full_api_surfaces,
        ),
    ]

java_api_contribution = rule(
    implementation = _java_api_contribution_impl,
    attrs = {
        "api": attr.label(
            mandatory = True,
            allow_single_file = [".txt"],
            doc = "The partial signature file describing the APIs of this module",
        ),
        # TODO: Better name for this
        "api_surface": attr.string(
            doc = "The partial api surface signature represented by this file. See _JAVA_FULLAPISURFACE_TO_PARTIALSIGNATUREFILE in java_api_contribution.bzl for relationship between partial signature files and full API surfaces",
            default = "publicapi",
            values = VALID_JAVA_API_SURFACES,
        ),
    },
)
