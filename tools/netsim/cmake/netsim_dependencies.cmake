set(BLUETOOTH_EMULATION True)
set(AOSP ${CMAKE_CURRENT_LIST_DIR}/../../..)
set(EXTERNAL ${AOSP}/external)
set(EXTERNAL_QEMU ${EXTERNAL}/qemu)
set(ANDROID_QEMU2_TOP_DIR ${EXTERNAL_QEMU})

if(NOT DEFINED ANDROID_TARGET_TAG)
  message(
    WARNING
      "You should invoke the cmake generator with a proper toolchain from ${EXTERNAL_QEMU}/android/build/cmake, "
      "Trying to infer toolchain, this might not work.")
  list(APPEND CMAKE_MODULE_PATH "${EXTERNAL_QEMU}/android/build/cmake/")
  include(toolchain)
  _get_host_tag(TAG)
  toolchain_configure_tags(${TAG})
endif()

include(android)
include(prebuilts)
prebuilt(Threads)

if(Rust_COMPILER OR OPTION_ENABLE_SYSTEM_RUST)
  if(OPTION_ENABLE_SYSTEM_RUST)
    message(STATUS "Attempting to use the system rust compiler")
    use_system_rust_toolchain()
  endif()

  enable_vendorized_crates("${EXTERNAL_QEMU}/android/third_party/rust/crates")
  add_subdirectory(${EXTERNAL_QEMU}/android/build/cmake/corrosion corrosion)
  ensure_rust_version_is_compliant()
endif()

set(_gRPC_RE2_INCLUDE_DIR "${EXTERNAL_QEMU}/android/third_party/re2")
set(_gRPC_RE2_LIBRARIES re2)

# First make the protobuf and dependencies available to gRPC
add_subdirectory(${EXTERNAL}/qemu/android/third_party/protobuf protobuf)

add_subdirectory(${AOSP}/hardware/google/aemu/base aemu-base)
add_subdirectory(${AOSP}/hardware/google/aemu/host-common host-common)
add_subdirectory(${EXTERNAL_QEMU}/android/bluetooth/rootcanal rootcanal)
add_subdirectory(${EXTERNAL_QEMU}/android/third_party/abseil-cpp abseil-cpp)
add_subdirectory(${EXTERNAL_QEMU}/android/third_party/boringssl boringssl)
add_subdirectory(${EXTERNAL_QEMU}/android/third_party/google-benchmark
                 google-benchmark)
add_subdirectory(${EXTERNAL_QEMU}/android/third_party/googletest/ gtest)
add_subdirectory(${EXTERNAL_QEMU}/android/third_party/lz4 lz4)
add_subdirectory(${EXTERNAL_QEMU}/android/third_party/re2 re2)
add_subdirectory(${EXTERNAL}/cares cares)
add_subdirectory(${EXTERNAL}/grpc/emulator grpc)
add_subdirectory(${EXTERNAL}/qemu/android/android-emu-base android-emu-base)
add_subdirectory(${EXTERNAL}/qemu/android/emu/base emu-base)
add_subdirectory(${EXTERNAL}/webrtc/third_party/jsoncpp jsoncpp)

if(NOT TARGET gfxstream-snapshot.headers)
  # Fake dependency to satisfy linker
  add_library(gfxstream-snapshot.headers INTERFACE)
endif()

if(CMAKE_BUILD_TYPE MATCHES DEBUG)
  # This will help you find issues.
  set(CMAKE_C_FLAGS "-fsanitize=address -fno-omit-frame-pointer -g3 -O0")
  set(CMAKE_EXE_LINKER_FLAGS "-fsanitize=address")
endif()

if(LINUX_X86_64)
  # Our linux headers are from 2013, and do not define newer socket options.
  # (b/156635589)
  target_compile_options(grpc PRIVATE -DSO_REUSEPORT=15)
  target_compile_options(grpc_unsecure PRIVATE -DSO_REUSEPORT=15)
endif()

# Testing
enable_testing()
include(GoogleTest)
