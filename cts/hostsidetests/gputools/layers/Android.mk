# Copyright (C) 2017 The Android Open Source Project
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


include $(CLEAR_VARS)
LOCAL_MODULE := libVkLayer_nullLayerA
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := jni/nullLayer.cpp
LOCAL_CFLAGS += -Wall -Werror -fvisibility=hidden -DLAYERNAME="A"
LOCAL_SHARED_LIBRARIES := libandroid libvulkan libEGL libGLESv3 liblog
LOCAL_NDK_STL_VARIANT := c++_static
LOCAL_SDK_VERSION := current
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := libVkLayer_nullLayerB
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := jni/nullLayer.cpp
LOCAL_CFLAGS += -Wall -Werror -fvisibility=hidden -DLAYERNAME="B"
LOCAL_SHARED_LIBRARIES := libandroid libvulkan libEGL libGLESv3 liblog
LOCAL_NDK_STL_VARIANT := c++_static
LOCAL_SDK_VERSION := current
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := libVkLayer_nullLayerC
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := jni/nullLayer.cpp
LOCAL_CFLAGS += -Wall -Werror -fvisibility=hidden -DLAYERNAME="C"
LOCAL_SHARED_LIBRARIES := libandroid libvulkan libEGL libGLESv3 liblog
LOCAL_NDK_STL_VARIANT := c++_static
LOCAL_SDK_VERSION := current
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := libGLES_glesLayerA
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := jni/glesLayer.cpp
LOCAL_CFLAGS += -std=c++14 -Wall -Werror -fvisibility=hidden -DLAYERNAME=A
LOCAL_SHARED_LIBRARIES := libandroid libEGL libGLESv3 liblog
LOCAL_NDK_STL_VARIANT := c++_static
LOCAL_SDK_VERSION := current
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := libGLES_glesLayerB
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := jni/glesLayer.cpp
LOCAL_CFLAGS += -std=c++14 -Wall -Werror -fvisibility=hidden -DLAYERNAME=B
LOCAL_SHARED_LIBRARIES := libandroid libEGL libGLESv3 liblog
LOCAL_NDK_STL_VARIANT := c++_static
LOCAL_SDK_VERSION := current
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := libGLES_glesLayerC
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := jni/glesLayer.cpp
LOCAL_CFLAGS += -std=c++14 -Wall -Werror -fvisibility=hidden -DLAYERNAME=C
LOCAL_SHARED_LIBRARIES := libandroid libEGL libGLESv3 liblog
LOCAL_NDK_STL_VARIANT := c++_static
LOCAL_SDK_VERSION := current
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_PACKAGE_NAME := CtsGpuToolsRootlessGpuDebugApp-LAYERS
LOCAL_SDK_VERSION := current

# tag this module as a cts test artifact
LOCAL_COMPATIBILITY_SUITE := cts

LOCAL_MULTILIB := both

LOCAL_JNI_SHARED_LIBRARIES := \
libVkLayer_nullLayerA \
libVkLayer_nullLayerB \
libVkLayer_nullLayerC

LOCAL_USE_EMBEDDED_NATIVE_LIBS := false

include $(BUILD_CTS_SUPPORT_PACKAGE)


include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_PACKAGE_NAME := CtsGpuToolsRootlessGpuDebugApp-GLES_LAYERS
LOCAL_SDK_VERSION := current

# tag this module as a cts test artifact
LOCAL_COMPATIBILITY_SUITE := cts

LOCAL_MULTILIB := both

LOCAL_JNI_SHARED_LIBRARIES := \
libGLES_glesLayerA \
libGLES_glesLayerB \
libGLES_glesLayerC

LOCAL_USE_EMBEDDED_NATIVE_LIBS := false

LOCAL_AAPT_FLAGS := \
--rename-manifest-package android.rootlessgpudebug.GLES_LAYERS.app

include $(BUILD_CTS_SUPPORT_PACKAGE)


include $(call all-makefiles-under,$(LOCAL_PATH))
