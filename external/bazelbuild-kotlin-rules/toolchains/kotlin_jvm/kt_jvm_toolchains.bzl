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

"""Kotlin toolchain."""

load("//bazel:stubs.bzl", "select_java_language_level")
load("//:visibility.bzl", "RULES_DEFS_THAT_COMPILE_KOTLIN")

# Work around to toolchains in Google3.
KtJvmToolchainInfo = provider()

KT_VERSION = "v1_8_10"

KT_LANG_VERSION = "1.8"

# Kotlin JVM toolchain type label
_TYPE = Label("//toolchains/kotlin_jvm:kt_jvm_toolchain_type")

def _kotlinc_common_flags(ctx, other_flags):
    """Returns kotlinc flags to use in all compilations."""
    args = [
        # We're supplying JDK in bootclasspath explicitly instead
        "-no-jdk",

        # stdlib included in merged_deps
        "-no-stdlib",

        # The bytecode format to emit
        "-jvm-target",
        ctx.attr.jvm_target,

        # Emit bytecode with parameter names
        "-java-parameters",

        # Allow default method declarations, akin to what javac emits (b/110378321).
        "-Xjvm-default=all",

        # Trust JSR305 nullness type qualifier nicknames the same as @Nonnull/@Nullable
        # (see https://kotlinlang.org/docs/reference/java-interop.html#jsr-305-support)
        "-Xjsr305=strict",

        # Trust go/JSpecify nullness annotations
        # (see https://kotlinlang.org/docs/whatsnew1520.html#support-for-jspecify-nullness-annotations)
        "-Xjspecify-annotations=strict",

        # Trust annotations on type arguments, etc.
        # (see https://kotlinlang.org/docs/java-interop.html#annotating-type-arguments-and-type-parameters)
        "-Xtype-enhancement-improvements-strict-mode",

        # TODO: Remove this as the default setting (probably Kotlin 1.7)
        "-Xenhance-type-parameter-types-to-def-not-null=true",

        # Explicitly set language version so we can update compiler separate from language version
        "-language-version",
        ctx.attr.kotlin_language_version,

        # Enable type annotations in the JVM bytecode (b/170647926)
        "-Xemit-jvm-type-annotations",

        # TODO: Temporarily disable 1.5's sam wrapper conversion
        "-Xsam-conversions=class",

        # We don't want people to use experimental APIs, but if they do, we want them to use @OptIn
        "-opt-in=kotlin.RequiresOptIn",

        # Don't complain when using old builds or release candidate builds
        "-Xskip-prerelease-check",

        # Allows a no source files to create an empty jar.
        "-Xallow-no-source-files",

        # TODO: Remove this flag
        "-Xuse-old-innerclasses-logic",

        # TODO: Remove this flag
        "-Xno-source-debug-extension",
    ] + other_flags

    # --define=extra_kt_jvm_opts is for overriding from command line.
    # (Last wins in repeated --define=foo= use, so use --define=bar= instead.)
    extra_kt_jvm_opts = ctx.var.get("extra_kt_jvm_opts", default = None)
    if extra_kt_jvm_opts:
        args.extend([o for o in extra_kt_jvm_opts.split(" ") if o])
    return args

def _kotlinc_ide_flags(ctx):
    return _kotlinc_common_flags(ctx, other_flags = [])

def _kotlinc_cli_flags(ctx):
    return _kotlinc_common_flags(ctx, other_flags = [
        # Silence all warning-level diagnostics
        "-nowarn",
    ])

def _kt_jvm_toolchain_impl(ctx):
    kt_jvm_toolchain = dict(
        android_java8_apis_desugared = ctx.attr.android_java8_apis_desugared,
        android_lint_config = ctx.file.android_lint_config,
        android_lint_runner = ctx.attr.android_lint_runner[DefaultInfo].files_to_run,
        build_marker = ctx.file.build_marker,
        coverage_instrumenter = ctx.attr.coverage_instrumenter[DefaultInfo].files_to_run,
        # Don't require JavaInfo provider for integration test convenience.
        coverage_runtime = ctx.attr.coverage_runtime[JavaInfo] if JavaInfo in ctx.attr.coverage_runtime else None,
        genclass = ctx.file.genclass,
        header_gen_tool = ctx.attr.header_gen_tool[DefaultInfo].files_to_run if ctx.attr.header_gen_tool else None,
        jar_tool = ctx.attr.jar_tool[DefaultInfo].files_to_run,
        java_language_version = ctx.attr.java_language_version,
        java_runtime = ctx.attr.java_runtime,
        jvm_abi_gen_plugin = ctx.file.jvm_abi_gen_plugin,
        jvm_target = ctx.attr.jvm_target,
        kotlin_annotation_processing = ctx.file.kotlin_annotation_processing,
        kotlin_compiler = ctx.attr.kotlin_compiler[DefaultInfo].files_to_run,
        kotlin_language_version = ctx.attr.kotlin_language_version,
        kotlin_libs = [JavaInfo(compile_jar = jar, output_jar = jar) for jar in ctx.files.kotlin_libs],
        kotlin_sdk_libraries = ctx.attr.kotlin_sdk_libraries,
        kotlinc_cli_flags = _kotlinc_cli_flags(ctx),
        kotlinc_ide_flags = _kotlinc_ide_flags(ctx),
        proguard_whitelister = ctx.attr.proguard_whitelister[DefaultInfo].files_to_run,
        source_jar_zipper = ctx.file.source_jar_zipper,
        turbine = ctx.file.turbine,
        turbine_direct = ctx.file.turbine_direct if ctx.attr.enable_turbine_direct else None,
        turbine_jsa = ctx.file.turbine_jsa,
        turbine_java_runtime = ctx.attr.turbine_java_runtime,
    )
    return [
        platform_common.ToolchainInfo(**kt_jvm_toolchain),
        KtJvmToolchainInfo(**kt_jvm_toolchain),
    ]

kt_jvm_toolchain = rule(
    attrs = dict(
        android_java8_apis_desugared = attr.bool(
            # Reflects a select in build rules.
            doc = "Whether Java 8 API desugaring is enabled",
            mandatory = True,
        ),
        android_lint_config = attr.label(
            cfg = "exec",
            allow_single_file = [".xml"],
        ),
        android_lint_runner = attr.label(
            default = "//bazel:stub_tool",
            executable = True,
            cfg = "exec",
        ),
        build_marker = attr.label(
            default = "//tools:build_marker",
            allow_single_file = [".jar"],
        ),
        coverage_instrumenter = attr.label(
            default = "//tools/coverage:offline_instrument",
            cfg = "exec",
            executable = True,
        ),
        coverage_runtime = attr.label(
            default = "@maven//:org_jacoco_org_jacoco_agent",
        ),
        enable_turbine_direct = attr.bool(
            # If disabled, the value of turbine_direct will be ignored.
            # Starlark doesn't allow None to override default-valued attributes:
            default = True,
        ),
        genclass = attr.label(
            default = "@bazel_tools//tools/jdk:GenClass_deploy.jar",
            cfg = "exec",
            allow_single_file = True,
        ),
        header_gen_tool = attr.label(
            executable = True,
            allow_single_file = True,
            cfg = "exec",
        ),
        jar_tool = attr.label(
            default = "@bazel_tools//tools/jdk:jar",
            executable = True,
            allow_files = True,
            cfg = "exec",
        ),
        java_language_version = attr.string(
            default = "11",
        ),
        java_runtime = attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            cfg = "exec",
            allow_files = True,
        ),
        jvm_abi_gen_plugin = attr.label(
            default = "@kotlinc//:jvm_abi_gen_plugin",
            cfg = "exec",
            allow_single_file = [".jar"],
        ),
        jvm_target = attr.string(
            doc = "The value to pass as -jvm-target, indicating the bytecode format to emit.",
        ),
        kotlin_annotation_processing = attr.label(
            default = "@kotlinc//:kotlin_annotation_processing",
            cfg = "exec",
            allow_single_file = True,
        ),
        kotlin_compiler = attr.label(
            default = "@kotlinc//:kotlin_compiler",
            cfg = "exec",
            executable = True,
        ),
        kotlin_language_version = attr.string(
            default = KT_LANG_VERSION,
        ),
        kotlin_libs = attr.label_list(
            doc = "The libraries required during all Kotlin builds.",
            default = [
                "@kotlinc//:kotlin_stdlib",
                "@kotlinc//:annotations",
            ],
            allow_files = [".jar"],
            cfg = "target",
        ),
        kotlin_sdk_libraries = attr.label_list(
            doc = "The libraries required to resolve Kotlin code in an IDE.",
            default = [
                "@kotlinc//:kotlin_reflect",
                "@kotlinc//:kotlin_stdlib",
                "@kotlinc//:kotlin_test_not_testonly",
            ],
            cfg = "target",
        ),
        proguard_whitelister = attr.label(
            default = "@bazel_tools//tools/jdk:proguard_whitelister",
            cfg = "exec",
        ),
        runtime = attr.label_list(
            # This attribute has a "magic" name recognized by the native DexArchiveAspect
            # (b/78647825). Must list all implicit runtime deps here, this is not limited
            # to Kotlin runtime libs.
            default = [
                "@kotlinc//:kotlin_stdlib",
                "@kotlinc//:annotations",
            ],
            cfg = "target",
            doc = "The Kotlin runtime libraries grouped into one attribute.",
        ),
        source_jar_zipper = attr.label(
            default = "//tools/bin:source_jar_zipper_binary",
            cfg = "exec",
            allow_single_file = [".jar"],
        ),
        turbine = attr.label(
            default = "@bazel_tools//tools/jdk:turbine_direct",
            cfg = "exec",
            allow_single_file = True,
        ),
        turbine_direct = attr.label(
            executable = True,
            cfg = "exec",
            allow_single_file = True,
        ),
        turbine_jsa = attr.label(
            cfg = "exec",
            allow_single_file = True,
        ),
        turbine_java_runtime = attr.label(
            cfg = "exec",
        ),
    ),
    provides = [platform_common.ToolchainInfo],
    implementation = _kt_jvm_toolchain_impl,
)

def _declare(**kwargs):
    kt_jvm_toolchain(
        android_java8_apis_desugared = select({
            "//conditions:default": False,
        }),
        # The JVM bytecode version to output
        # https://kotlinlang.org/docs/compiler-reference.html#jvm-target-version
        jvm_target = "11",
        **kwargs
    )

_ATTRS = dict(
    _toolchain = attr.label(
        # TODO: Delete this attr when fixed.
        doc = "Magic attribute name for DexArchiveAspect (b/78647825)",
        default = "//toolchains/kotlin_jvm:kt_jvm_toolchain_impl",
    ),
)

kt_jvm_toolchains = struct(
    name = _TYPE.name,
    get = lambda ctx: ctx.toolchains[_TYPE],
    type = str(_TYPE),
    declare = _declare,
    attrs = _ATTRS,
)
