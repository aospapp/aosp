#
# Copyright (C) 2016-2018 ARM Limited. All rights reserved.
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

TOP_LOCAL_PATH := $(call my-dir)
MALI_GRALLOC_API_TESTS?=0

ifdef GRALLOC_USE_GRALLOC1_API
    ifdef GRALLOC_API_VERSION
        $(warning GRALLOC_API_VERSION flag is not compatible with GRALLOC_USE_GRALLOC1_API)
    endif
endif

include $(TOP_LOCAL_PATH)/gralloc.version.mk

# Place and access VPU library from /vendor directory in unit testing as default
# /system is not in the linker permitted paths
ifeq ($(MALI_GRALLOC_API_TESTS), 1)
MALI_GRALLOC_VPU_LIBRARY_PATH := \"/vendor/lib/\"
endif

#Build allocator for 2.x and gralloc libhardware HAL for all previous versions
GRALLOC_MAPPER := 0
ifeq ($(shell expr $(GRALLOC_VERSION_MAJOR) \>= 2), 1)
    $(info Build Gralloc allocator for 2.x)
else ifeq ($(shell expr $(GRALLOC_VERSION_MAJOR) \>= 1), 1)
    $(info Build Gralloc 1.x libhardware HAL)
else
    $(info Build Gralloc 0.x libhardware HAL)
endif
include $(TOP_LOCAL_PATH)/src/Android.mk

# Build gralloc mapper for 2.x
ifeq ($(shell expr $(GRALLOC_VERSION_MAJOR) \>= 2), 1)
   GRALLOC_MAPPER := 1
   $(info Build Gralloc mapper for 2.x)
   include $(TOP_LOCAL_PATH)/src/Android.mk
endif

# Build gralloc api tests.
ifeq ($(MALI_GRALLOC_API_TESTS), 1)
$(info Build gralloc API tests.)
include $(TOP_LOCAL_PATH)/api_tests/Android.mk
endif

####################################################################################################

include $(CLEAR_VARS)
include $(TOP_LOCAL_PATH)/gralloc.version.mk

LOCAL_SHARED_LIBRARIES := \
    liblog \
    libcutils \
    libutils \
    android.hardware.graphics.mapper@2.0 \
    libsync \
    libhardware \
    libhidlbase \
    libhidltransport \
    libnativewindow \
    libion_google \
    libdmabufheap

LOCAL_STATIC_LIBRARIES := libarect
LOCAL_HEADER_LIBRARIES := libnativebase_headers

LOCAL_C_INCLUDES := \
    $(TOP)/hardware/google/gchips/include \
    $(TOP)/hardware/google/gchips/gralloc3/include \
    $(TARGET_BOARD_KERNEL_HEADERS)

LOCAL_SRC_FILES := \
    gralloc_vendor_interface.cpp \
    mali_gralloc_ion.cpp \
    gralloc_buffer_priv.cpp \
    format_info.cpp \
    mali_gralloc_bufferallocation.cpp \
    mali_gralloc_formats.cpp \
    mali_gralloc_debug.cpp

# TODO(find out why setting this to $(HIDL_MAPPER_VERSION_SCALED) doesn't work)
LOCAL_CFLAGS := -DHIDL_MAPPER_VERSION_SCALED=200
LOCAL_CFLAGS += -DGRALLOC_VERSION_MAJOR=$(GRALLOC_VERSION_MAJOR)
LOCAL_CFLAGS += -DGRALLOC_HFR_BATCH_SIZE=8

LOCAL_CFLAGS += -DMALI_GRALLOC_GPU_LIBRARY_PATH1=$(MALI_GRALLOC_GPU_LIBRARY_64_PATH1)
LOCAL_CFLAGS += -DMALI_GRALLOC_GPU_LIBRARY_PATH2=$(MALI_GRALLOC_GPU_LIBRARY_64_PATH2)

ifeq ($(BOARD_EXYNOS_S10B_FORMAT_ALIGN), 64)
LOCAL_CFLAGS += -DBOARD_EXYNOS_S10B_FORMAT_ALIGN=$(BOARD_EXYNOS_S10B_FORMAT_ALIGN)
else
LOCAL_CFLAGS += -DBOARD_EXYNOS_S10B_FORMAT_ALIGN=16
endif

LOCAL_CFLAGS += -DGRALLOC_LIBRARY_BUILD=1

ifeq ($(BOARD_USES_VENDORIMAGE), true)
LOCAL_PROPRIETARY_MODULE := true
endif
LOCAL_MODULE := libGrallocWrapper
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice

include $(BUILD_SHARED_LIBRARY)
