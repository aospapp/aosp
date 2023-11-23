# Copyright (C) 2021 The Android Open Source Project
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

"""A macro to handle shared library stripping."""

load(":cc_library_common.bzl", "CcAndroidMkInfo")
load(":clang_tidy.bzl", "collect_deps_clang_tidy_info")
load(
    ":lto_transitions.bzl",
    "drop_lto_transition",
    "lto_deps_transition",
)

CcUnstrippedInfo = provider(
    "Provides unstripped binary/shared library",
    fields = {
        "unstripped": "unstripped target",
    },
)

# Keep this consistent with soong/cc/strip.go#NeedsStrip.
def _needs_strip(ctx):
    if ctx.attr.none:
        return False
    if ctx.target_platform_has_constraint(ctx.attr._android_constraint[platform_common.ConstraintValueInfo]):
        return True
    return (ctx.attr.all or ctx.attr.keep_symbols or
            ctx.attr.keep_symbols_and_debug_frame or ctx.attr.keep_symbols_list)

# Keep this consistent with soong/cc/strip.go#strip and soong/cc/builder.go#transformStrip.
def _get_strip_args(attrs):
    strip_args = []
    keep_mini_debug_info = False
    if attrs.keep_symbols:
        strip_args.append("--keep-symbols")
    elif attrs.keep_symbols_and_debug_frame:
        strip_args.append("--keep-symbols-and-debug-frame")
    elif attrs.keep_symbols_list:
        strip_args.append("-k" + ",".join(attrs.keep_symbols_list))
    elif not attrs.all:
        strip_args.append("--keep-mini-debug-info")
        keep_mini_debug_info = True

    if not keep_mini_debug_info:
        strip_args.append("--add-gnu-debuglink")

    return strip_args

# https://cs.android.com/android/platform/superproject/+/master:build/soong/cc/builder.go;l=131-146;drc=master
def stripped_impl(ctx, prefix = "", suffix = "", extension = ""):
    out_file = ctx.actions.declare_file(prefix + ctx.attr.name + suffix + extension)
    if not _needs_strip(ctx):
        ctx.actions.symlink(
            output = out_file,
            target_file = ctx.files.src[0],
        )
        return out_file
    d_file = ctx.actions.declare_file(ctx.attr.name + ".d")
    ctx.actions.run(
        env = {
            "CREATE_MINIDEBUGINFO": ctx.executable._create_minidebuginfo.path,
            "XZ": ctx.executable._xz.path,
            "CLANG_BIN": ctx.executable._ar.dirname,
        },
        inputs = ctx.files.src,
        tools = [
            ctx.executable._ar,
            ctx.executable._create_minidebuginfo,
            ctx.executable._objcopy,
            ctx.executable._readelf,
            ctx.executable._strip,
            ctx.executable._strip_script,
            ctx.executable._xz,
        ],
        outputs = [out_file, d_file],
        executable = ctx.executable._strip_script,
        arguments = _get_strip_args(ctx.attr) + [
            "-i",
            ctx.files.src[0].path,
            "-o",
            out_file.path,
            "-d",
            d_file.path,
        ],
        mnemonic = "CcStrip",
    )
    return out_file

strip_attrs = dict(
    keep_symbols = attr.bool(default = False),
    keep_symbols_and_debug_frame = attr.bool(default = False),
    all = attr.bool(default = False),
    none = attr.bool(default = False),
    keep_symbols_list = attr.string_list(default = []),
)
common_strip_attrs = dict(
    strip_attrs,
    _xz = attr.label(
        cfg = "exec",
        executable = True,
        allow_single_file = True,
        default = "//prebuilts/build-tools:linux-x86/bin/xz",
    ),
    _create_minidebuginfo = attr.label(
        cfg = "exec",
        executable = True,
        allow_single_file = True,
        default = "//prebuilts/build-tools:linux-x86/bin/create_minidebuginfo",
    ),
    _strip_script = attr.label(
        cfg = "exec",
        executable = True,
        allow_single_file = True,
        default = "//build/soong/scripts:strip.sh",
    ),
    _ar = attr.label(
        cfg = "exec",
        executable = True,
        allow_single_file = True,
        default = "//prebuilts/clang/host/linux-x86:llvm-ar",
    ),
    _strip = attr.label(
        cfg = "exec",
        executable = True,
        allow_single_file = True,
        default = "//prebuilts/clang/host/linux-x86:llvm-strip",
    ),
    _readelf = attr.label(
        cfg = "exec",
        executable = True,
        allow_single_file = True,
        default = "//prebuilts/clang/host/linux-x86:llvm-readelf",
    ),
    _objcopy = attr.label(
        cfg = "exec",
        executable = True,
        allow_single_file = True,
        default = "//prebuilts/clang/host/linux-x86:llvm-objcopy",
    ),
    _cc_toolchain = attr.label(
        default = Label("@local_config_cc//:toolchain"),
        providers = [cc_common.CcToolchainInfo],
    ),
    _android_constraint = attr.label(
        default = Label("//build/bazel/platforms/os:android"),
    ),
)

def _stripped_shared_library_impl(ctx):
    out_file = stripped_impl(ctx, prefix = "lib", extension = ".so")

    return [
        DefaultInfo(files = depset([out_file])),
        ctx.attr.src[CcSharedLibraryInfo],
        ctx.attr.src[OutputGroupInfo],
    ]

stripped_shared_library = rule(
    implementation = _stripped_shared_library_impl,
    attrs = dict(
        common_strip_attrs,
        src = attr.label(
            mandatory = True,
            # TODO(b/217908237): reenable allow_single_file
            # allow_single_file = True,
            providers = [CcSharedLibraryInfo],
        ),
    ),
    toolchains = ["@bazel_tools//tools/cpp:toolchain_type"],
)

# A marker provider to distinguish a cc_binary from everything else that exports
# a CcInfo.
StrippedCcBinaryInfo = provider()

def _stripped_binary_impl(ctx):
    common_providers = [
        ctx.attr.src[0][CcInfo],
        ctx.attr.src[0][InstrumentedFilesInfo],
        ctx.attr.src[0][DebugPackageInfo],
        ctx.attr.src[0][OutputGroupInfo],
        StrippedCcBinaryInfo(),  # a marker for dependents
        CcUnstrippedInfo(
            unstripped = ctx.attr.unstripped,
        ),
        collect_deps_clang_tidy_info(ctx),
    ] + [
        d[CcAndroidMkInfo]
        for d in ctx.attr.androidmk_deps
    ]

    out_file = stripped_impl(ctx, suffix = ctx.attr.suffix)

    return [
        DefaultInfo(
            files = depset([out_file]),
            executable = out_file,
        ),
    ] + common_providers

_rule_attrs = dict(
    common_strip_attrs,
    src = attr.label(
        mandatory = True,
        allow_single_file = True,
        providers = [CcInfo],
        cfg = lto_deps_transition,
    ),
    runtime_deps = attr.label_list(
        providers = [CcInfo],
        doc = "Deps that should be installed along with this target. Read by the apex cc aspect.",
    ),
    androidmk_deps = attr.label_list(
        providers = [CcAndroidMkInfo],
    ),
    suffix = attr.string(),
    unstripped = attr.label(
        mandatory = True,
        allow_single_file = True,
        cfg = lto_deps_transition,
        doc = "Unstripped binary to be returned by ",
    ),
    _allowlist_function_transition = attr.label(
        default = "@bazel_tools//tools/allowlists/function_transition_allowlist",
    ),
)

stripped_binary = rule(
    implementation = _stripped_binary_impl,
    cfg = drop_lto_transition,
    attrs = _rule_attrs,
    executable = True,
    toolchains = ["@bazel_tools//tools/cpp:toolchain_type"],
)

stripped_test = rule(
    implementation = _stripped_binary_impl,
    cfg = drop_lto_transition,
    attrs = _rule_attrs,
    test = True,
    toolchains = ["@bazel_tools//tools/cpp:toolchain_type"],
)
