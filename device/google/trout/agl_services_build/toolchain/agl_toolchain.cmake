SET(CMAKE_SYSTEM_NAME Linux)
SET(CMAKE_SYSTEM_PROCESSOR aarch64)

# Toolchain precedence: environment variable TROUT_CLANG_PATH > Android Clang Toolchain > Floral Clang Toolchain
# AGL sysroot precedence: environment variable TROUT_AGL_SYSROOT > Floral AGL sysroot
IF(DEFINED ENV{COQOS_HV_PATH})
    SET(TROUT_CLANG_PATH $ENV{COQOS_HV_PATH}/tools/toolchains/clang-llvm-9)
    SET(TROUT_AGL_SYSROOT $ENV{COQOS_HV_PATH}/bsp/linux/yocto/qti-yocto-agl-sdk-sa8155-automotive-machine-image/sysroots/aarch64-agl-linux)
ENDIF()

IF (DEFINED ENV{ANDROID_BUILD_TOP})
    FILE(GLOB TROUT_ANDROID_TOOLCHAIN_CANDIDATES $ENV{ANDROID_BUILD_TOP}/prebuilts/clang/host/linux-x86/clang-*
        LIST_DIRECTORIES true)
    # Select the latest one
    LIST(SORT TROUT_ANDROID_TOOLCHAIN_CANDIDATES ORDER DESCENDING)
    FIND_PATH(TROUT_ANDROID_TOOLCHAIN
        NAMES
        bin/clang
        bin/clang++
        bin/llvm-ar
        bin/llvm-nm
        bin/llvm-ranlib
        PATHS
        ${TROUT_ANDROID_TOOLCHAIN_CANDIDATES}
        NO_DEFAULT_PATH)
    SET(TROUT_CLANG_PATH ${TROUT_ANDROID_TOOLCHAIN})
ENDIF()

IF (DEFINED ENV{TROUT_CLANG_PATH})
    SET(TROUT_CLANG_PATH $ENV{TROUT_CLANG_PATH})
ENDIF()

IF (DEFINED ENV{TROUT_AGL_SYSROOT})
    SET(TROUT_AGL_SYSROOT $ENV{TROUT_AGL_SYSROOT})
ENDIF()

IF (NOT TROUT_CLANG_PATH)
    MESSAGE(FATAL_ERROR "Please run `lunch`, or define environment variable COQOS_HV_PATH or TROUT_CLANG_PATH")
ENDIF()

IF (NOT TROUT_AGL_SYSROOT)
    MESSAGE(FATAL_ERROR "Please define environment variable COQOS_HV_PATH or TROUT_AGL_SYSROOT")
ENDIF()

SET(_triple aarch64-none-linux-gnu)

SET(CMAKE_CROSSCOMPILING TRUE)
SET(CMAKE_SYSROOT ${TROUT_AGL_SYSROOT})

SET(CMAKE_C_COMPILER ${TROUT_CLANG_PATH}/bin/clang)
SET(CMAKE_C_COMPILER_TARGET ${_triple})

SET(CMAKE_CXX_COMPILER ${TROUT_CLANG_PATH}/bin/clang++)
SET(CMAKE_CXX_COMPILER_TARGET ${_triple})

SET(CMAKE_ASM_COMPILER_TARGET ${_triple})

SET(CMAKE_AR ${TROUT_CLANG_PATH}/bin/llvm-ar)
SET(CMAKE_NM ${TROUT_CLANG_PATH}/bin/llvm-nm)
SET(CMAKE_RANLIB ${TROUT_CLANG_PATH}/bin/llvm-ranlib)

SET(CMAKE_CXX_STANDARD_INCLUDE_DIRECTORIES
    ${TROUT_AGL_SYSROOT}/usr/include/c++/7.3.0
    ${TROUT_AGL_SYSROOT}/usr/include/c++/7.3.0/aarch64-agl-linux
)

SET(CMAKE_EXE_LINKER_FLAGS
    -fuse-ld=lld
    -B ${TROUT_AGL_SYSROOT}/usr/lib/aarch64-agl-linux/7.3.0
    -nodefaultlibs
    -lstdc++
    -lm
    -lc
    -lgcc_s
)
STRING(REPLACE ";" " " CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS}")

SET(CMAKE_SHARED_LINKER_FLAGS
    -fuse-ld=lld
    -B ${TROUT_AGL_SYSROOT}/usr/lib/aarch64-agl-linux/7.3.0
    -nodefaultlibs
    -lstdc++
    -lm
    -lc
    -lgcc_s
)
STRING(REPLACE ";" " " CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS}")

SET(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
SET(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
SET(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
SET(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
