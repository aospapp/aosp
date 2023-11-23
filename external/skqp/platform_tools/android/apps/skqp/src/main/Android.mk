# Copyright 2019 Google LLC.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests
LOCAL_JAVA_LIBRARIES := android.test.runner.stubs
LOCAL_JNI_SHARED_LIBRARIES := libskqp_app
LOCAL_MULTILIB := both
LOCAL_USE_AAPT2 := true
LOCAL_STATIC_ANDROID_LIBRARIES := android-support-design
LOCAL_STATIC_JAVA_LIBRARIES := ctstestrunner-axt
LOCAL_SRC_FILES := $(call all-java-files-under, java)
LOCAL_PACKAGE_NAME := CtsSkQPTestCases
LOCAL_SDK_VERSION := test_current
include $(BUILD_CTS_PACKAGE)
