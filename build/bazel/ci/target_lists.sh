#!/usr/bin/env bash

###############
# Build and test targets for device target platform.
###############
BUILD_TARGETS=(
  //art/...
  //bionic/...
  //bootable/recovery/tools/recovery_l10n/...
  //build/...
  //cts/...
  //development/...
  //external/...
  //frameworks/...
  //libnativehelper/...
  //packages/...
  //prebuilts/clang/host/linux-x86:all
  //prebuilts/build-tools/tests/...
  //prebuilts/runtime/...
  //prebuilts/tools/...
  //platform_testing/...
  //system/...
  //tools/apksig/...
  //tools/asuite/...
  //tools/platform-compat/...

  # These tools only build for host currently
  -//external/e2fsprogs/misc:all
  -//external/e2fsprogs/resize:all
  -//external/e2fsprogs/debugfs:all
  -//external/e2fsprogs/e2fsck:all
  # TODO(b/277616982): These modules depend on private java APIs, but maybe they don't need to.
  -//external/ow2-asm:all

  # TODO(b/266459895): remove these after re-enabling libunwindstack
  -//bionic/libc/malloc_debug:libc_malloc_debug
  -//bionic/libfdtrack:libfdtrack
  -//frameworks/av/media/codec2/hidl/1.0/utils:libcodec2_hidl@1.0
  -//frameworks/av/media/codec2/hidl/1.1/utils:libcodec2_hidl@1.1
  -//frameworks/av/media/codec2/hidl/1.2/utils:libcodec2_hidl@1.2
  -//frameworks/av/media/module/bqhelper:libstagefright_bufferqueue_helper_novndk
  -//frameworks/av/media/module/codecserviceregistrant:libmedia_codecserviceregistrant
  -//frameworks/av/services/mediacodec:mediaswcodec
  -//frameworks/native/libs/gui:libgui
  -//frameworks/native/libs/gui:libgui_bufferqueue_static
  -//frameworks/native/opengl/libs:libEGL
  -//frameworks/native/opengl/libs:libGLESv2
  -//system/core/libutils:all
  -//system/unwinding/libunwindstack:all
)

TEST_TARGETS=(
  //build/bazel/...
  //prebuilts/clang/host/linux-x86:all
  //prebuilts/sdk:toolchains_have_all_prebuilts
)

HOST_ONLY_TEST_TARGETS=(
  //tools/trebuchet:AnalyzerKt
  //tools/metalava:metalava
  # Test both unstripped and stripped versions of a host native unit test
  //system/core/libcutils:libcutils_test
  //system/core/libcutils:libcutils_test__test_binary_unstripped
  # TODO(b/268186228): adb_test fails only on CI
  -//packages/modules/adb:adb_test
  # TODO(b/268185249): libbase_test asserts on the Soong basename of the test
  -//system/libbase:libbase_test
)

HOST_INCOMPATIBLE_TARGETS=(
  # TODO(b/216626461): add support for host_ldlibs
  -//packages/modules/adb:all
  -//packages/modules/adb/pairing_connection:all
)

# These targets are used to ensure that the aosp-specific rule wrappers forward
# all providers of the underlying rule.
EXAMPLE_WRAPPER_TARGETS=(
  # java_import wrapper
  //build/bazel/examples/java/com/bazel:hello_java_import
  # java_library wrapper
  //build/bazel/examples/java/com/bazel:hello_java_lib
  # kt_jvm_library wrapper
  //build/bazel/examples/java/com/bazel:some_kotlin_lib
  # android_library wrapper
  //build/bazel/examples/android_app/java/com/app:applib
  # android_binary wrapper
  //build/bazel/examples/android_app/java/com/app:app
  # aar_import wrapper
  //build/bazel/examples/android_app/java/com/app:import
)
