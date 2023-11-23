# This repository provides files that Soong emits during bp2build (other than
# converted BUILD files), mostly .bzl files containing constants to support the
# converted BUILD files.
load("//build/bazel/rules:soong_injection.bzl", "soong_injection_repository")

soong_injection_repository(name = "soong_injection")

# ! WARNING ! WARNING ! WARNING !
# make_injection is a repository rule to allow Bazel builds to depend on
# Soong-built prebuilts for experimental purposes. It is fragile, slow, and
# works for very limited use cases. Do not add a dependency that will cause
# make_injection to run for any prod builds or tests.
#
# If you need to add something in this list, please contact the Roboleaf
# team and ask jingwen@ for a review.
load("//build/bazel/rules:make_injection.bzl", "make_injection_repository")

make_injection_repository(
    name = "make_injection",
    binaries = [
        "build_image",
        "mkuserimg_mke2fs",
    ],
    target_module_files = {},
    watch_android_bp_files = [
        "//:build/make/tools/releasetools/Android.bp",  # for build_image
        "//:system/extras/ext4_utils/Android.bp",  # for mkuserimg_mke2fs
    ],
)
# ! WARNING ! WARNING ! WARNING !

load("//build/bazel/rules:env.bzl", "env_repository")

env_repository(
    name = "env",
)

# This repository is a containter for API surface stub libraries.
load("//build/bazel/rules:api_surfaces_injection.bzl", "api_surfaces_repository")

# TODO: Once BUILD files for stubs are checked-in, this should be converted to a local_repository.
api_surfaces_repository(name = "api_surfaces")

load("//build/bazel_common_rules/workspace:external.bzl", "import_external_repositories")

import_external_repositories(
    bazel_skylib = True,
    io_abseil_py = True,
)

load("@bazel_skylib//:workspace.bzl", "bazel_skylib_workspace")

bazel_skylib_workspace()

local_repository(
    name = "rules_android",
    path = "external/bazelbuild-rules_android",
)

local_repository(
    name = "rules_license",
    path = "external/bazelbuild-rules_license",
)

register_toolchains(
    "//prebuilts/build-tools:py_toolchain",

    # For Android rules
    "//prebuilts/sdk:all",

    # For APEX rules
    "//build/bazel/rules/apex:all",

    # For partition rules
    "//build/bazel/rules/partitions:all",
)

bind(
    name = "databinding_annotation_processor",
    actual = "//prebuilts/sdk:compiler_annotation_processor",
)

bind(
    name = "android/dx_jar_import",
    actual = "//prebuilts/sdk:dx_jar_import",
)

# The r8.jar in prebuilts/r8 happens to have the d8 classes needed
# for Android app building, whereas the d8.jar in prebuilts/sdk/tools doesn't.
bind(
    name = "android/d8_jar_import",
    actual = "//prebuilts/bazel/common/r8:r8_jar_import",
)

# TODO(b/201242197): Avoid downloading remote_coverage_tools (on CI) by creating
# a stub workspace. Test rules (e.g. sh_test) depend on this external dep, but
# we don't support coverage yet. Either vendor the external dep into AOSP, or
# cut the dependency from test rules to the external repo.
local_repository(
    name = "remote_coverage_tools",
    path = "build/bazel_common_rules/rules/coverage/remote_coverage_tools",
)

# Stubbing the local_jdk both ensures that we don't accidentally download remote
# repositories and allows us to let the Kotlin rules continue to access
# @local_jdk//jar.
local_repository(
    name = "local_jdk",
    path = "build/bazel/rules/java/stub_local_jdk",
)

# The following 2 repositories contain prebuilts that are necessary to the Java Rules.
# They are vendored locally to avoid the need for CI bots to download them.
local_repository(
    name = "remote_java_tools",
    path = "prebuilts/bazel/common/remote_java_tools",
)

local_repository(
    name = "remote_java_tools_linux",
    path = "prebuilts/bazel/linux-x86_64/remote_java_tools_linux",
)

# The following repository contains android_tools prebuilts.
local_repository(
    name = "android_tools",
    path = "prebuilts/bazel/common/android_tools",
)

# The rules_java repository is stubbed and points to the native Java rules until
# it can be properly vendored.
local_repository(
    name = "rules_java",
    path = "build/bazel_common_rules/rules/java/rules_java",
)

register_toolchains(
    "//prebuilts/jdk/jdk17:runtime_toolchain_definition",
    "//build/bazel/rules/java:jdk17_host_toolchain_java_definition",
)

local_repository(
    name = "kotlin_maven_interface",
    path = "build/bazel/rules/kotlin/maven_interface",
)

local_repository(
    name = "rules_kotlin",
    path = "external/bazelbuild-kotlin-rules",
    repo_mapping = {
        "@maven": "@kotlin_maven_interface",
        "@bazel_platforms": "@platforms",
    },
)

new_local_repository(
    name = "kotlinc",
    build_file = "//build/bazel/rules/kotlin:kotlinc.BUILD",
    path = "external/kotlinc",
)

register_toolchains("@rules_kotlin//toolchains/kotlin_jvm:kt_jvm_toolchain")

load("//prebuilts/clang/host/linux-x86:cc_toolchain_config.bzl", "cc_register_toolchains")

cc_register_toolchains()
