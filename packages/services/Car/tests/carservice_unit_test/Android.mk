# Copyright (C) 2016 The Android Open Source Project
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
#

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR += $(LOCAL_PATH)/res \
    packages/services/Car/service/res

LOCAL_AAPT_FLAGS += --extra-packages com.android.car --auto-add-overlay

LOCAL_PACKAGE_NAME := CarServiceUnitTest
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_CERTIFICATE := platform

LOCAL_MODULE_TAGS := tests

# When built explicitly put it in the data partition
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_INSTRUMENTATION_FOR := CarService

LOCAL_JAVA_LIBRARIES := \
    android.car \
    android.car.userlib \
    android.test.runner \
    android.test.base \
    android.test.mock

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.test.core \
    androidx.test.rules \
    car-frameworks-service \
    car-service-lib-for-test \
    com.android.car.test.utils \
    junit \
    mockito-target-inline-minus-junit4 \
    frameworks-base-testutils \
    truth-prebuilt

# mockito-target-inline dependency
LOCAL_JNI_SHARED_LIBRARIES := \
    libdexmakerjvmtiagent \

include $(BUILD_PACKAGE)
