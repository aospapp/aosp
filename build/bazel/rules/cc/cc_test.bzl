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

"""cc_test macro for building native tests with Bazel."""

load("//build/bazel/rules/cc:cc_library_common.bzl", "CcAndroidMkInfo")
load("//build/bazel/rules/cc:stripped_cc_common.bzl", "CcUnstrippedInfo", "StrippedCcBinaryInfo")
load("//build/bazel/rules/tradefed:tradefed.bzl", "tradefed_host_driven_test")
load(":cc_binary.bzl", "cc_binary")

# TODO(b/244559183): Keep this in sync with cc/test.go#linkerFlags
_gtest_copts = select({
    "//build/bazel/platforms/os:linux_glibc": ["-DGTEST_OS_LINUX"],
    "//build/bazel/platforms/os:darwin": ["-DGTEST_OS_MAC"],
    "//build/bazel/platforms/os:windows": ["-DGTEST_OS_WINDOWS"],
    "//conditions:default": ["-DGTEST_OS_LINUX_ANDROID"],
}) + select({
    "//build/bazel/platforms/os:android": [],
    "//conditions:default": ["-O0", "-g"],  # here, default == host platform
}) + [
    "-DGTEST_HAS_STD_STRING",
    "-Wno-unused-result",  # TODO(b/244433518): Figure out why this is necessary in the bazel compile action.
]

_gtest_deps = [
    "//external/googletest/googletest:libgtest_main",
    "//external/googletest/googletest:libgtest",
]

_pass_through_providers = [
    CcInfo,
    InstrumentedFilesInfo,
    DebugPackageInfo,
    OutputGroupInfo,
    StrippedCcBinaryInfo,
    CcUnstrippedInfo,
    CcAndroidMkInfo,
]

def cc_test(
        name,
        copts = [],
        deps = [],
        dynamic_deps = [],
        gtest = True,
        isolated = True,  # TODO(b/244432609): currently no-op. @unused
        tags = [],
        tidy = None,
        tidy_checks = None,
        tidy_checks_as_errors = None,
        tidy_flags = None,
        tidy_disabled_srcs = None,
        tidy_timeout_srcs = None,
        test_config = None,
        template_test_config = None,
        template_configs = [],
        template_install_base = None,
        **kwargs):
    # NOTE: Keep this in sync with cc/test.go#linkerDeps
    if gtest:
        # TODO(b/244433197): handle ctx.useSdk() && ctx.Device() case to link against the ndk variants of the gtest libs.
        # TODO(b/244432609): handle isolated = True to link against libgtest_isolated_main and liblog (dynamically)
        deps = deps + _gtest_deps
        copts = copts + _gtest_copts

    # A cc_test is essentially the same as a cc_binary. Let's reuse the
    # implementation for now and factor the common bits out as necessary.
    test_binary_name = name + "__test_binary"
    cc_binary(
        name = test_binary_name,
        copts = copts,
        deps = deps,
        dynamic_deps = dynamic_deps,
        generate_cc_test = True,
        tidy = tidy,
        tidy_checks = tidy_checks,
        tidy_checks_as_errors = tidy_checks_as_errors,
        tidy_flags = tidy_flags,
        tidy_disabled_srcs = tidy_disabled_srcs,
        tidy_timeout_srcs = tidy_timeout_srcs,
        tags = tags + ["manual"],
        **kwargs
    )

    # Host only test with no tradefed.
    # Compatability is left out for now so as not to break mix build.
    # which breaks when modules are skipped with --config=android
    without_tradefed_test_name = name + "__without_tradefed_test"
    cc_runner_test(
        name = without_tradefed_test_name,
        binary = test_binary_name,
        test = test_binary_name,
        tags = ["manual"],
    )

    # Tradefed host driven test
    tradefed_host_driven_test_name = name + "__tradefed_host_driven_test"
    if not test_config and not template_test_config:
        template_test_config = select({
            "//build/bazel/rules/tradefed:android_host_driven_tradefed_test": "//build/make/core:native_test_config_template.xml",
            "//build/bazel/rules/tradefed:linux_host_driven_tradefed_test": "//build/make/core:native_host_test_config_template.xml",
            "//conditions:default": "//build/make/core:native_test_config_template.xml",
        })
    tradefed_host_driven_test(
        name = tradefed_host_driven_test_name,
        test_identifier = name,
        test = test_binary_name,
        test_config = test_config,
        template_test_config = template_test_config,
        template_configs = template_configs,
        template_install_base = template_install_base,
        tags = ["manual"],
    )

    # TODO(b/264792912) update to use proper config/tags to determine which test to run.
    cc_runner_test(
        name = name,
        binary = test_binary_name,
        test = select({
            "//build/bazel/rules/tradefed:android_host_driven_tradefed_test": tradefed_host_driven_test_name,
            "//build/bazel/rules/tradefed:linux_host_driven_tradefed_test": tradefed_host_driven_test_name,
            "//conditions:default": without_tradefed_test_name,
        }),
    )

def _cc_runner_test_impl(ctx):
    executable = ctx.actions.declare_file(ctx.attr.name + "__cc_runner_test")
    ctx.actions.symlink(
        output = executable,
        target_file = ctx.attr.test.files_to_run.executable,
    )

    # Gather runfiles.
    runfiles = ctx.runfiles()
    runfiles = runfiles.merge_all([
        ctx.attr.binary.default_runfiles,
        ctx.attr.test.default_runfiles,
    ])

    # Propagate providers of the included binary
    # Those providers are used to populate attributes of the mixed build.
    providers = collect_providers(ctx.attr.binary, _pass_through_providers)
    return [DefaultInfo(
        executable = executable,
        runfiles = runfiles,
    )] + providers

cc_runner_test = rule(
    doc = "A wrapper rule used to run a test and also propagates providers",
    attrs = {
        "binary": attr.label(
            doc = "Binary that providers should be propagated to next rule // mix build.",
        ),
        "test": attr.label(
            doc = "Test to run.",
        ),
    },
    test = True,
    implementation = _cc_runner_test_impl,
    toolchains = ["@bazel_tools//tools/cpp:toolchain_type"],
)

def collect_providers(dep, provider_types):
    """Returns list of providers from dependency that match the provider types"""
    providers = []
    for provider in provider_types:
        if provider in dep:
            providers.append(dep[provider])
    return providers
