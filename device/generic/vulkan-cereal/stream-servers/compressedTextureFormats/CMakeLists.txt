if (ASTC_CPU_DECODING)
    set(astc-cpu-decompressor-sources AstcCpuDecompressorImpl.cpp)
else()
    set(astc-cpu-decompressor-sources AstcCpuDecompressorNoOp.cpp)
endif()

add_library(
    gfxstream-compressedTextures
    ${astc-cpu-decompressor-sources}
    etc.cpp)

if(ASTC_CPU_DECODING)
    target_link_libraries(gfxstream-compressedTextures PRIVATE astcdec-avx2-static)
endif()


if (ENABLE_VKCEREAL_TESTS)
    add_executable(
        gfxstream-compressedTextures_unittests
        AstcCpuDecompressor_unittest.cpp)

    target_include_directories(
        gfxstream-compressedTextures_unittests
        PRIVATE
        ${GFXSTREAM_REPO_ROOT}/include)

    target_link_libraries(
        gfxstream-compressedTextures_unittests
        PRIVATE
        aemu-base.headers
        gfxstream-compressedTextures
        ${GFXSTREAM_BASE_LIB}
        gtest_main
        gmock_main)

    gtest_discover_tests(gfxstream-compressedTextures_unittests)
endif()
