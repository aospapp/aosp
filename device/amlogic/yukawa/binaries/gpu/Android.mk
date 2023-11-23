LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

TARGET := ${GPU_TYPE}
GPU_TARGET_PLATFORM ?= default_8a
GPU_DRV_VERSION ?= r16p0
LOCAL_ANDROID_VERSION_NUM := p-${GPU_DRV_VERSION}gralloc1

LOCAL_MODULE := libGLES_mali
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_MULTILIB := both
LOCAL_MODULE_SUFFIX := .so
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
ifeq ($(shell test $(PLATFORM_SDK_VERSION) -ge 26 && echo OK),OK)
LOCAL_MODULE_PATH    := $(TARGET_OUT_VENDOR)/egl
LOCAL_MODULE_PATH_32 := $(TARGET_OUT_VENDOR)/lib/egl
LOCAL_MODULE_PATH_64 := $(TARGET_OUT_VENDOR)/lib64/egl
else
LOCAL_MODULE_PATH    := $(TARGET_OUT_SHARED_LIBRARIES)/egl
LOCAL_MODULE_PATH_32 := $(TARGET_OUT)/lib/egl
LOCAL_MODULE_PATH_64 := $(TARGET_OUT)/lib64/egl
endif
ifeq ($(TARGET_2ND_ARCH),)
ifeq ($(TARGET_ARCH),arm)
LOCAL_SRC_FILES    	 := $(TARGET)/libGLES_mali_$(GPU_TARGET_PLATFORM)_32-$(LOCAL_ANDROID_VERSION_NUM).so
else
LOCAL_SRC_FILES    	 := $(TARGET)/libGLES_mali_$(GPU_TARGET_PLATFORM)_64-$(LOCAL_ANDROID_VERSION_NUM).so
endif
else
LOCAL_SRC_FILES_32   := $(TARGET)/libGLES_mali_$(GPU_TARGET_PLATFORM)_32-$(LOCAL_ANDROID_VERSION_NUM).so
LOCAL_SRC_FILES_64	 := $(TARGET)/libGLES_mali_$(GPU_TARGET_PLATFORM)_64-$(LOCAL_ANDROID_VERSION_NUM).so
endif
LOCAL_SHARED_LIBRARIES := android.hardware.graphics.common@1.0 libz libnativewindow libc++ liblog libm libc libdl
LOCAL_STRIP_MODULE := false

ifeq ($(BOARD_INSTALL_VULKAN),true)
LOCAL_POST_INSTALL_CMD = $(hide) \
    pushd $(dir $(LOCAL_INSTALLED_MODULE))../hw && \
        ln -sf ../egl/$(notdir $(LOCAL_INSTALLED_MODULE)) vulkan.$(TARGET_DEVICE).so && \
        popd;
endif

ifeq ($(BOARD_INSTALL_OPENCL),true)
LOCAL_POST_INSTALL_CMD += \
    pushd $(dir $(LOCAL_INSTALLED_MODULE)).. && \
        ln -sf egl/$(notdir $(LOCAL_INSTALLED_MODULE)) libOpenCL.so.1.1 && \
        ln -sf libOpenCL.so.1.1 libOpenCL.so.1 && \
        ln -sf libOpenCL.so.1.1 libOpenCL.so && \
        popd;
endif

include $(BUILD_PREBUILT)
