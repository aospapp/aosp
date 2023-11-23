# 
# Copyright (C) 2010 ARM Limited. All rights reserved.
# 
# Copyright (C) 2008 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


LOCAL_PATH := $(call my-dir)

# HAL module implemenation, not prelinked and stored in
# hw/<OVERLAY_HARDWARE_MODULE_ID>.<ro.product.board>.so
include $(CLEAR_VARS)
LOCAL_PRELINK_MODULE := false

LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_VENDOR_MODULE := true

MALI_DDK_TEST_PATH := hardware/arm/

LOCAL_MODULE := gralloc.hikey
LOCAL_MODULE_RELATIVE_PATH := hw
#LOCAL_MODULE_TAGS := optional

# Mali-200/300/400MP DDK
MALI_DDK_PATH := hardware/arm/mali
#SHARED_MEM_LIBS := libUMP
SHARED_MEM_LIBS := libion libhardware
LOCAL_SHARED_LIBRARIES := liblog libsync libGLESv1_CM $(SHARED_MEM_LIBS)

LOCAL_C_INCLUDES := system/core/include/ $(MALI_DDK_PATH)/include 
# Include the UMP header files
LOCAL_C_INCLUDES += $(MALI_DDK_PATH)/src/ump/include

LOCAL_CFLAGS := -DLOG_TAG=\"gralloc\" -DGRALLOC_32_BITS -DSTANDARD_LINUX_SCREEN -DPLATFORM_SDK_VERSION=$(PLATFORM_SDK_VERSION)

LOCAL_SRC_FILES := \
	gralloc_module.cpp \
	alloc_device.cpp \
	framebuffer_device.cpp

#LOCAL_CFLAGS+= -DMALI_VSYNC_EVENT_REPORT_ENABLE


ifeq ($(HIKEY_USE_DRM_HWCOMPOSER), true)
LOCAL_CFLAGS += -DDISABLE_FRAMEBUFFER_HAL
endif

include $(BUILD_SHARED_LIBRARY)
