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

"""Bazel rules for generating the metadata of API domain contributions to an API surface"""

load(":api_surface.bzl", "ALL_API_SURFACES")
load(":cc_api_contribution.bzl", "CcApiContributionInfo")
load(":java_api_contribution.bzl", "JavaApiContributionInfo")

def _api_domain_impl(ctx):
    """Implementation of the api_domain rule
    Currently it only supports exporting the API surface contributions of the API domain
    """
    out = []
    for api_surface in ALL_API_SURFACES:
        # TODO(b/220938703): Add other contributions (e.g. resource_api_contribution)
        # cc
        cc_libraries = [cc[CcApiContributionInfo] for cc in ctx.attr.cc_api_contributions if api_surface in cc[CcApiContributionInfo].api_surfaces]

        # java
        java_libraries = [java[JavaApiContributionInfo] for java in ctx.attr.java_api_contributions if api_surface in java[JavaApiContributionInfo].api_surfaces]

        # The contributions of an API domain are always at ver=current
        # Contributions of an API domain to previous Android SDKs will be snapshot and imported into the build graph by a separate Bazel rule
        api_surface_metadata = struct(
            name = api_surface,
            version = "current",
            api_domain = ctx.attr.name,
            cc_libraries = cc_libraries,
            java_libraries = java_libraries,
        )
        api_surface_filestem = "-".join([api_surface, "current", ctx.attr.name])
        api_surface_file = ctx.actions.declare_file(api_surface_filestem + ".json")
        ctx.actions.write(api_surface_file, json.encode(api_surface_metadata))
        out.append(api_surface_file)

    return [DefaultInfo(files = depset(out))]

api_domain = rule(
    implementation = _api_domain_impl,
    attrs = {
        "cc_api_contributions": attr.label_list(providers = [CcApiContributionInfo]),
        "java_api_contributions": attr.label_list(providers = [JavaApiContributionInfo]),
    },
)
