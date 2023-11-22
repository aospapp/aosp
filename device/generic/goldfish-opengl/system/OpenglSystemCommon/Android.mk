LOCAL_PATH := $(call my-dir)

$(call emugl-begin-shared-library,libOpenglSystemCommon)
$(call emugl-import,libGLESv1_enc libGLESv2_enc lib_renderControl_enc)

ifeq (true,$(BUILD_EMULATOR_VULKAN))
$(call emugl-import,libvulkan_enc)
endif

LOCAL_SRC_FILES := \
    FormatConversions.cpp \
    HostConnection.cpp \
    QemuPipeStream.cpp \
    ProcessPipe.cpp    \

LOCAL_CFLAGS += -Wno-unused-variable -Wno-unused-parameter

ifeq (true,$(GOLDFISH_OPENGL_BUILD_FOR_HOST))

LOCAL_SRC_FILES += \
    ThreadInfo_host.cpp \

else

ifeq (true,$(BUILD_EMULATOR_VULKAN))

LOCAL_HEADER_LIBRARIES += vulkan_headers

endif

LOCAL_SRC_FILES += \
    ThreadInfo.cpp \

endif

ifneq ($(filter virgl, $(BOARD_GPU_DRIVERS)),)
LOCAL_CFLAGS += -DVIRTIO_GPU
LOCAL_SRC_FILES += VirtioGpuStream.cpp
LOCAL_C_INCLUDES += external/libdrm external/minigbm/cros_gralloc
LOCAL_SHARED_LIBRARIES += libdrm
endif

ifdef IS_AT_LEAST_OPD1
LOCAL_HEADER_LIBRARIES += libnativebase_headers

$(call emugl-export,HEADER_LIBRARIES,libnativebase_headers)
endif

ifdef IS_AT_LEAST_OPD1
LOCAL_HEADER_LIBRARIES += libhardware_headers
$(call emugl-export,HEADER_LIBRARIES,libhardware_headers)
endif

$(call emugl-export,C_INCLUDES,$(LOCAL_PATH) bionic/libc/private)

ifeq (true,$(GOLDFISH_OPENGL_BUILD_FOR_HOST))
$(call emugl-export,SHARED_LIBRARIES,android-emu-shared)
endif

$(call emugl-end-module)
