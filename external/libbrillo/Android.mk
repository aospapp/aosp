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

# Default values for the USE flags. Override these USE flags from your product
# by setting BRILLO_USE_* values. Note that we define local variables like
# local_use_* to prevent leaking our default setting for other packages.

LOCAL_PATH := $(call my-dir)

libbrillo_CFLAGS := \
    -Wall \
    -Werror

# Shared minijail library for target
# ========================================================
include $(CLEAR_VARS)
LOCAL_CPP_EXTENSION := .cc
LOCAL_MODULE := libbrillo-minijail
LOCAL_SRC_FILES := brillo/minijail/minijail.cc
LOCAL_SHARED_LIBRARIES := libchrome libbrillo libminijail
LOCAL_STATIC_LIBRARIES := libgtest_prod
LOCAL_CFLAGS := $(libbrillo_CFLAGS)
LOCAL_CLANG := true
LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)
include $(BUILD_SHARED_LIBRARY)

# Run unit tests on target
# ========================================================
# We su shell because process tests try setting "illegal"
# uid/gids and expecting failures, but root can legally
# set those to any value.
runtargettests: libbrillo_test
	adb sync
	adb shell su shell /data/nativetest/libbrillo_test/libbrillo_test
