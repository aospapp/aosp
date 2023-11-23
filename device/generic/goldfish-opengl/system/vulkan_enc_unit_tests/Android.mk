LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := CommandBufferStagingStreamTests

$(call emugl-import,libvulkan_enc)

LOCAL_C_INCLUDES += \
    device/generic/goldfish-opengl/host/include/libOpenglRender \
    external/gfxstream-protocols/include/vulkan/include/

LOCAL_SRC_FILES:= \
    CommandBufferStagingStream_test.cpp \

LOCAL_STATIC_LIBRARIES := libgmock
LOCAL_VENDOR_MODULE := true
LOCAL_MODULE_TAGS := tests

LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/../../LICENSE
include $(BUILD_NATIVE_TEST)
