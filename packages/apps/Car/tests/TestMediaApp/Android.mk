#
# Copyright (C) 2019 The Android Open Source Project
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

# Actual Package:

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := TestMediaApp
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_CERTIFICATE := platform

LOCAL_MODULE_TAGS := optional

# car_car is ok here because this is meant to simulate a third party media app
LOCAL_STATIC_ANDROID_LIBRARIES := \
        androidx.car_car \
        androidx.appcompat_appcompat \
        androidx.preference_preference \
        car-media-common

LOCAL_USE_AAPT2 := true

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_DEX_PREOPT := false

include $(BUILD_PACKAGE)

endif
