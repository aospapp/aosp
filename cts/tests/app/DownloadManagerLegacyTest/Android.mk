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

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# don't include this package in any target
LOCAL_MODULE_TAGS := optional
# and when built explicitly put it in the data partition
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)

LOCAL_JAVA_LIBRARIES := \
    android.test.runner.stubs \
    org.apache.http.legacy \
    android.test.base.stubs

LOCAL_STATIC_JAVA_LIBRARIES := \
    compatibility-device-util-axt \
    ctstestrunner-axt \
    ctstestserver \
    mockito-target-minus-junit4 \
    androidx.test.rules \
    platform-test-annotations \
    androidx.test.rules \

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \
    ../src/android/app/cts/DownloadManagerTestBase.java

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/../app/res

LOCAL_ASSET_DIR := $(LOCAL_PATH)/../app/assets

# Tag this module as a cts test artifact
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests

LOCAL_PACKAGE_NAME := CtsDownloadManagerLegacy

LOCAL_SDK_VERSION := test_current
LOCAL_MIN_SDK_VERSION := 11

include $(BUILD_CTS_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))