# TODO(gregschlom) move this to base
add_library(logging-base
    STATIC
    logging.cpp
    GfxstreamFatalError.cpp
    ../base/Metrics.cpp)
target_include_directories(
    logging-base
    PRIVATE
    include/host-common)
target_link_libraries(
    logging-base
    PUBLIC
    PRIVATE
    aemu-base.headers
    aemu-host-common.headers)

add_library(aemu-host-common.headers INTERFACE)
target_link_libraries(
    aemu-host-common.headers
    INTERFACE
    gfxstream-snapshot.headers)
target_include_directories(
    aemu-host-common.headers
    INTERFACE
    include)

if (BUILD_STANDALONE)
    add_library(
        aemu-host-common
        STATIC

        # emugl glue
        empty-crash-handler.cpp
        crash_reporter.cpp
        vm_operations.cpp
        feature_control.cpp
        dma_device.cpp
        sync_device.cpp
        misc.cpp
        window_operations.cpp

        # What used to be android-emu
        AndroidPipe.cpp
        HostmemIdMapping.cpp
        RefcountPipe.cpp
        GraphicsAgentFactory.cpp

        # goldfish sync
        GoldfishSyncCommandQueue.cpp
        goldfish_sync.cpp

        # goldfish dma
        DmaMap.cpp
        GoldfishDma.cpp

        # Address space device
        address_space_device_control_ops.cpp
        address_space_device.cpp
        address_space_host_memory_allocator.cpp
        address_space_shared_slots_host_memory_allocator.cpp
        address_space_graphics.cpp
        address_space_host_media.cpp

        hw-config.cpp
        )

    target_include_directories(
        aemu-host-common
        PRIVATE
        include/host-common
        ${AEMU_COMMON_REPO_ROOT}/../../../external/angle/include)
    target_link_libraries(
        aemu-host-common
        PUBLIC
        aemu-base.headers
        aemu-host-common.headers
        ${GFXSTREAM_BASE_LIB}
        PRIVATE
        logging-base
    )
endif()

if(NOT TARGET aemu-host-common.product-feature-override)
    add_library(aemu-host-common.product-feature-override OBJECT FeatureControlOverride.cpp)
    target_link_libraries(
        aemu-host-common.product-feature-override
        PRIVATE
        aemu-host-common.headers
        aemu-base.headers)
endif()

if (GFXSTREAM_HOST_COMMON_LIB)
    target_link_libraries(
        ${GFXSTREAM_HOST_COMMON_LIB}
        PRIVATE
        aemu-host-common.product-feature-override)
endif()

if (ENABLE_VKCEREAL_TESTS)
    # Tests
    add_library(
        aemu-host-common-testing-support
        testing/HostAddressSpace.cpp
        testing/MockGraphicsAgentFactory.cpp
        testing/MockAndroidEmulatorWindowAgent.cpp
        testing/MockAndroidMultiDisplayAgent.cpp
        testing/MockAndroidVmOperations.cpp)
    target_include_directories(
        aemu-host-common-testing-support
        PUBLIC
        ${AEMU_COMMON_REPO_ROOT})
    target_link_libraries(
        aemu-host-common-testing-support
        PUBLIC
        PRIVATE
        aemu-base.headers
        aemu-host-common.headers
        gtest
        gmock)

    add_executable(
        aemu-host-common_unittests
        address_space_graphics_unittests.cpp
        address_space_host_memory_allocator_unittests.cpp
        address_space_shared_slots_host_memory_allocator_unittests.cpp
        HostAddressSpace_unittest.cpp
        HostmemIdMapping_unittest.cpp
        logging_unittest.cpp
        GfxstreamFatalError_unittest.cpp)

    target_include_directories(
        aemu-host-common_unittests
        PRIVATE
        ${AEMU_COMMON_REPO_ROOT})

    target_link_libraries(
        aemu-host-common_unittests
        PRIVATE
        aemu-base.headers
        aemu-host-common.headers
        ${GFXSTREAM_BASE_LIB}
        ${GFXSTREAM_HOST_COMMON_LIB}
        aemu-host-common-testing-support
        gtest_main
        gmock_main)

    gtest_discover_tests(aemu-host-common_unittests)
endif()
