#
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
#

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := libctsnativemidi_jni

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
	com_android_cts_verifier_audio_midilib_NativeMidiManager.cpp \
	MidiTestManager.cpp

LOCAL_C_INCLUDES := $(JNI_H_INCLUDE)

LOCAL_C_INCLUDES += \
    frameworks/base/media/native/midi/include \
    frameworks/av/media/ndk/include \
    system/core/include/cutils

#LOCAL_CXX_STL := libc++_static
#LOCAL_NDK_STL_VARIANT := libc++_static

#APP_STL := stlport_static
#APP_STL := gnustl_static

LOCAL_SDK_VERSION := current
LOCAL_NDK_STL_VARIANT := system

LOCAL_SHARED_LIBRARIES := liblog libamidi \

LOCAL_CFLAGS := \
        -Wall -Werror \
        -Wno-unused-parameter \
        -Wno-unused-variable \

include $(BUILD_SHARED_LIBRARY)
