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

LOCAL_PATH:= $(call my-dir)

# Build the Call Screening test app.
# This is intentionally packaged separately so that the app can use its own
# package name which does not conflict with the CallScreeningService
# associated with the CTS InCallService.
include $(CLEAR_VARS)

src_dirs := src

LOCAL_AIDL_INCLUDES := $(call all-Iaidl-files-under, aidl)

LOCAL_SRC_FILES := $(call all-java-files-under, $(src_dirs)) \
                   $(call all-Iaidl-files-under, aidl)

LOCAL_PACKAGE_NAME := CallScreeningServiceTestApp

LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := test_current
LOCAL_COMPATIBILITY_SUITE := cts vts
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)

LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_PACKAGE)
