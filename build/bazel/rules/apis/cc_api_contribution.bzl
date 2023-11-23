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

"""Bazel rules for exporting API contributions of CC libraries"""

load("@bazel_skylib//lib:paths.bzl", "paths")
load("@bazel_skylib//lib:sets.bzl", "sets")
load("//build/bazel/rules/cc:cc_constants.bzl", "constants")
load(":api_surface.bzl", "MODULE_LIB_API", "PUBLIC_API", "VENDOR_API")

"""A Bazel provider that encapsulates the headers presented to an API surface"""
CcApiHeaderInfo = provider(
    fields = {
        "name": "Name identifying the header files",
        "root": "Directory containing the header files, relative to workspace root. This will become the -I parameter in consuming API domains. This defaults to the current Bazel package",
        "headers": "The header (.h) files presented by the library to an API surface",
        "system": "bool, This will determine whether the include path will be -I or -isystem",
        "arch": "Target arch of devices that use these header files to compile. The default is empty, which means that it is arch-agnostic",
    },
)

def _cc_api_header_impl(ctx):
    """Implementation for the cc_api_headers rule.
    This rule does not have any build actions, but returns a `CcApiHeaderInfo` provider object"""
    headers_filepath = [header.path for header in ctx.files.hdrs]
    root = paths.dirname(ctx.build_file_path)
    if ctx.attr.include_dir:
        root = paths.join(root, ctx.attr.include_dir)
    info = CcApiHeaderInfo(
        name = ctx.label.name,
        root = root,
        headers = headers_filepath,
        system = ctx.attr.system,
        arch = ctx.attr.arch,
    )

    # TODO: Use depset for CcApiHeaderInfoList to optimize merges in `_cc_api_contribution_impl`
    return [
        info,
        CcApiHeaderInfoList(
            headers_list = [info],
        ),
    ]

"""A bazel rule that encapsulates the header contributions of a CC library to an API surface
This rule does not contain the API symbolfile (.map.txt). The API symbolfile is part of the cc_api_contribution rule
This layering is necessary since the symbols present in a single .map.txt file can be defined in different include directories
e.g.
├── Android.bp
├── BUILD
├── include <-- cc_api_headers
├── include_other <-- cc_api_headers
├── libfoo.map.txt
"""
cc_api_headers = rule(
    implementation = _cc_api_header_impl,
    attrs = {
        "include_dir": attr.string(
            mandatory = False,
            doc = "Directory containing the header files, relative to the Bazel package. This relative path will be joined with the Bazel package path to become the -I parameter in the consuming API domain",
        ),
        "hdrs": attr.label_list(
            mandatory = True,
            allow_files = constants.hdr_dot_exts,
            doc = "List of .h files presented to the API surface. Glob patterns are allowed",
        ),
        "system": attr.bool(
            default = False,
            doc = "Boolean to indicate whether these are system headers",
        ),
        "arch": attr.string(
            mandatory = False,
            values = ["arm", "arm64", "x86", "x86_64"],
            doc = "Arch of the target device. The default is empty, which means that the headers are arch-agnostic",
        ),
    },
)

"""List container for multiple CcApiHeaderInfo providers"""
CcApiHeaderInfoList = provider(
    fields = {
        "headers_list": "List of CcApiHeaderInfo providers presented by a target",
    },
)

def _cc_api_library_headers_impl(ctx):
    hdrs_info = []
    for hdr in ctx.attr.hdrs:
        for hdr_info in hdr[CcApiHeaderInfoList].headers_list:
            hdrs_info.append(hdr_info)

    return [
        CcApiHeaderInfoList(
            headers_list = hdrs_info,
        ),
    ]

_cc_api_library_headers = rule(
    implementation = _cc_api_library_headers_impl,
    attrs = {
        "hdrs": attr.label_list(
            mandatory = True,
            providers = [CcApiHeaderInfoList],
        ),
    },
)

# Internal header library targets created by cc_api_library_headers macro
# Bazel does not allow target name to end with `/`
def _header_target_name(name, include_dir):
    return name + "_" + paths.normalize(include_dir)

def cc_api_library_headers(
        name,
        hdrs = [],  # @unused
        export_includes = [],
        export_system_includes = [],
        arch = None,
        deps = [],
        **kwargs):
    header_deps = []
    for include in export_includes:
        _name = _header_target_name(name, include)

        # export_include = "." causes the following error in glob
        # Error in glob: segment '.' not permitted
        # Normalize path before globbing
        fragments = [include, "**/*.h"]
        normpath = paths.normalize(paths.join(*fragments))

        cc_api_headers(
            name = _name,
            include_dir = include,
            hdrs = native.glob([normpath]),
            system = False,
            arch = arch,
        )
        header_deps.append(_name)

    for system_include in export_system_includes:
        _name = _header_target_name(name, system_include)
        cc_api_headers(
            name = _name,
            include_dir = system_include,
            hdrs = native.glob([paths.join(system_include, "**/*.h")]),
            system = True,
            arch = arch,
        )
        header_deps.append(_name)

    # deps should be exported
    header_deps.extend(deps)

    _cc_api_library_headers(
        name = name,
        hdrs = header_deps,
        **kwargs
    )

"""A Bazel provider that encapsulates the contributions of a CC library to an API surface"""
CcApiContributionInfo = provider(
    fields = {
        "name": "Name of the cc library",
        "api": "Path of map.txt describing the stable APIs of the library. Path is relative to workspace root",
        "headers": "metadata of the header files of the cc library",
        "api_surfaces": "API surface(s) this library contributes to",
    },
)

VALID_CC_API_SURFACES = [
    PUBLIC_API,
    MODULE_LIB_API,  # API surface provided by platform and mainline modules to other mainline modules
    VENDOR_API,
]

def _validate_api_surfaces(api_surfaces):
    for api_surface in api_surfaces:
        if api_surface not in VALID_CC_API_SURFACES:
            fail(api_surface, " is not a valid API surface. Acceptable values: ", VALID_CC_API_SURFACES)

def _cc_api_contribution_impl(ctx):
    """Implemenation for the cc_api_contribution rule
    This rule does not have any build actions, but returns a `CcApiContributionInfo` provider object"""
    api_filepath = ctx.file.api.path
    hdrs_info = sets.make()
    for hdr in ctx.attr.hdrs:
        for hdr_info in hdr[CcApiHeaderInfoList].headers_list:
            sets.insert(hdrs_info, hdr_info)

    name = ctx.attr.library_name or ctx.label.name
    _validate_api_surfaces(ctx.attr.api_surfaces)

    return [
        CcApiContributionInfo(
            name = name,
            api = api_filepath,
            headers = sets.to_list(hdrs_info),
            api_surfaces = ctx.attr.api_surfaces,
        ),
    ]

cc_api_contribution = rule(
    implementation = _cc_api_contribution_impl,
    attrs = {
        "library_name": attr.string(
            mandatory = False,
            doc = "Name of the library. This can be different from `name` to prevent name collision with the implementation of the library in the same Bazel package. Defaults to label.name",
        ),
        "api": attr.label(
            mandatory = True,
            allow_single_file = [".map.txt", ".map"],
            doc = ".map.txt file of the library",
        ),
        "hdrs": attr.label_list(
            mandatory = False,
            providers = [CcApiHeaderInfoList],
            doc = "Header contributions of the cc library. This should return a `CcApiHeaderInfo` provider",
        ),
        "api_surfaces": attr.string_list(
            doc = "API surface(s) this library contributes to. See VALID_CC_API_SURFACES in cc_api_contribution.bzl for valid values for API surfaces",
            default = ["publicapi"],
        ),
    },
)
