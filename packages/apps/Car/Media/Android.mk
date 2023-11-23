#
# Copyright (C) 2015 The Android Open Source Project
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
#
ifneq ($(TARGET_BUILD_PDK), true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := CarMediaApp

LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_REQUIRED_MODULES := privapp_whitelist_com.android.car.media

LOCAL_CERTIFICATE := platform

LOCAL_MODULE_TAGS := optional

LOCAL_PRIVILEGED_MODULE := true

LOCAL_USE_AAPT2 := true

LOCAL_JAVA_LIBRARIES += android.car

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_DEX_PREOPT := false

LOCAL_JAVA_LIBRARIES += android.car

LOCAL_STATIC_JAVA_LIBRARIES += \
    androidx-constraintlayout_constraintlayout-solver

LOCAL_STATIC_ANDROID_LIBRARIES += \
    androidx-constraintlayout_constraintlayout \
    car-apps-common \
    car-media-common

# Including the resources for the static android libraries allows to pick up their static overlays.
LOCAL_RESOURCE_DIR += \
    $(LOCAL_PATH)/../libs/car-apps-common/res \
    $(LOCAL_PATH)/../libs/car-media-common/res

include $(BUILD_PACKAGE)

endif
