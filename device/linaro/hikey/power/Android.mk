# Copyright (C) 2012 The Android Open Source Project
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

# HAL module implemenation stored in
# hw/<POWERS_HARDWARE_MODULE_ID>.<ro.hardware>.so
include $(CLEAR_VARS)

LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_VENDOR_MODULE := true
LOCAL_MODULE_TAGS := optional

LOCAL_MODULE := android.hardware.power@1.1-service.hikey-common
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0 SPDX-license-identifier-BSD
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_INIT_RC := android.hardware.power@1.1-service.hikey-common.rc
LOCAL_SRC_FILES := service.cpp Power.cpp power_hikey.c

#LOCAL_MODULE := power.$(TARGET_BOARD_PLATFORM)
#LOCAL_SRC_FILES := power_hikey.c

LOCAL_HEADER_LIBRARIES += libhardware_headers

LOCAL_SHARED_LIBRARIES := liblog libcutils

LOCAL_SHARED_LIBRARIES := \
    libbase \
    libcutils \
    libhidlbase \
    liblog \
    libutils \
    android.hardware.power@1.1 \

include $(BUILD_EXECUTABLE)
