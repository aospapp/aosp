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

# NOTE: when converting this file to Android.bp, verify that
# 'atest CtsExtendedMockingTestCases' succeeds.
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := \
    tests
LOCAL_JAVA_LIBRARIES := \
    android.test.runner.stubs
LOCAL_STATIC_JAVA_LIBRARIES = \
    mockito-target-extended \
    androidx.test.rules \
    ctstestrunner-axt \
    dexmaker-mockmaker-tests \
    dexmaker-inline-mockmaker-tests \
    dexmaker-extended-mockmaker-tests \
    android-support-v4
LOCAL_MULTILIB := \
    both
LOCAL_JNI_SHARED_LIBRARIES := \
    libdexmakerjvmtiagent \
    libmultiplejvmtiagentsinterferenceagent \
    libstaticjvmtiagent
LOCAL_COMPATIBILITY_SUITE := \
    cts vts general-tests
LOCAL_PACKAGE_NAME := \
    CtsExtendedMockingTestCases
LOCAL_SDK_VERSION := \
    current
include $(BUILD_CTS_PACKAGE)

