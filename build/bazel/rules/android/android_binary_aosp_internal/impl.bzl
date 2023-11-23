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

load("@rules_android//rules:java.bzl", "java")
load(
    "@rules_android//rules:processing_pipeline.bzl",
    "ProviderInfo",
    "processing_pipeline",
)
load("@rules_android//rules:resources.bzl", _resources = "resources")
load("@rules_android//rules:utils.bzl", "get_android_toolchain")
load("@rules_android//rules/android_binary_internal:impl.bzl", "finalize", _BASE_PROCESSORS = "PROCESSORS")
load("//build/bazel/rules/common:api.bzl", "api")

def _process_manifest_aosp(ctx, **unused_ctxs):
    manifest_ctx = _resources.set_default_min_sdk(
        ctx,
        manifest = ctx.file.manifest,
        default = api.default_app_target_sdk(),
        enforce_min_sdk_floor_tool = get_android_toolchain(ctx).enforce_min_sdk_floor_tool.files_to_run,
    )

    return ProviderInfo(
        name = "manifest_ctx",
        value = manifest_ctx,
    )

# (b/274150785)  validation processor does not allow min_sdk that are a string
PROCESSORS = processing_pipeline.replace(
    _BASE_PROCESSORS,
    ManifestProcessor = _process_manifest_aosp,
)

_PROCESSING_PIPELINE = processing_pipeline.make_processing_pipeline(
    processors = PROCESSORS,
    finalize = finalize,
)

def impl(ctx):
    """The rule implementation.

    Args:
      ctx: The context.

    Returns:
      A list of providers.
    """
    java_package = java.resolve_package_from_label(ctx.label, ctx.attr.custom_package)
    return processing_pipeline.run(ctx, java_package, _PROCESSING_PIPELINE)
