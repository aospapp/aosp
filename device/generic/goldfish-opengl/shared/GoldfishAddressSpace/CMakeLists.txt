# This is an autogenerated file! Do not edit!
# instead run make from .../device/generic/goldfish-opengl
# which will re-generate this file.
android_validate_sha256("${GOLDFISH_DEVICE_ROOT}/shared/GoldfishAddressSpace/Android.mk" "a8b27a12e9d8e1cf4679c3ee1007f5ce8e846d7b07e6956c6eb46be289af9365")
set(GoldfishAddressSpace_host_src goldfish_address_space.cpp)
android_add_library(TARGET GoldfishAddressSpace_host LICENSE Apache-2.0 SRC goldfish_address_space.cpp)
target_include_directories(GoldfishAddressSpace_host PRIVATE ${GOLDFISH_DEVICE_ROOT}/shared/GoldfishAddressSpace/include ${GOLDFISH_DEVICE_ROOT}/./host/include/libOpenglRender ${GOLDFISH_DEVICE_ROOT}/./system/include ${GOLDFISH_DEVICE_ROOT}/./../../../external/qemu/android/android-emugl/guest ${GOLDFISH_DEVICE_ROOT}/device/generic/goldfish-opengl/system/include)
target_compile_definitions(GoldfishAddressSpace_host PRIVATE "-DPLATFORM_SDK_VERSION=29" "-DGOLDFISH_HIDL_GRALLOC" "-DHOST_BUILD" "-DANDROID" "-DGL_GLEXT_PROTOTYPES" "-DPAGE_SIZE=4096" "-DGFXSTREAM" "-DENABLE_ANDROID_HEALTH_MONITOR" "-DLOG_TAG=\"goldfish-address-space\"")
target_compile_options(GoldfishAddressSpace_host PRIVATE "-fvisibility=default" "-Wno-unused-parameter")
target_link_libraries(GoldfishAddressSpace_host PRIVATE log android-emu-shared)