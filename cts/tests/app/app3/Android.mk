# Copyright (C) 2018 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)/../app

include $(CLEAR_VARS)

# don't include this package in any target
LOCAL_MODULE_TAGS := optional
# and when built explicitly put it in the data partition
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_JAVA_LIBRARIES := \
    android.test.runner.stubs \
    telephony-common \
    voip-common \
    org.apache.http.legacy \
    android.test.base.stubs \


LOCAL_STATIC_JAVA_LIBRARIES := \
    compatibility-device-util-axt \
    ctstestrunner-axt \
    ctstestserver \
    mockito-target-minus-junit4 \
    androidx.legacy_legacy-support-v4

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
              src/android/app/stubs/ISecondary.aidl

# Tag this module as a cts test artifact
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests

LOCAL_PACKAGE_NAME := CtsAppTestStubsApp3
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_AAPT_FLAGS += --rename-manifest-package com.android.app3

# Disable AAPT2 manifest checks to fix:
# cts/tests/app/app/AndroidManifest.xml:25: error: unexpected element <meta-data> found in <manifest><permission>.
# TODO(b/79755007): Remove when AAPT2 recognizes the manifest elements.
LOCAL_AAPT_FLAGS += --warn-manifest-validation

include $(BUILD_CTS_SUPPORT_PACKAGE)
