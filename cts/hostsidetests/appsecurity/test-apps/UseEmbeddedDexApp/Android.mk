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

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := CtsUseEmbeddedDexApp_Canonical
LOCAL_USE_EMBEDDED_DEX := true
LOCAL_MANIFEST_FILE := AndroidManifest.xml

LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SDK_VERSION := current
LOCAL_MIN_SDK_VERSION := 27
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

include $(BUILD_CTS_SUPPORT_PACKAGE)


include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := CtsUseEmbeddedDexApp_DexCompressed
# Not specifying LOCAL_USE_EMBEDDED_DEX keeps dex compressed
LOCAL_MANIFEST_FILE := AndroidManifest.xml

LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SDK_VERSION := current
LOCAL_MIN_SDK_VERSION := 28
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

include $(BUILD_CTS_SUPPORT_PACKAGE)


include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := CtsUseEmbeddedDexApp_NotPreferred
LOCAL_MANIFEST_FILE := AndroidManifest_use_extracted_dex.xml

LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SDK_VERSION := current
LOCAL_MIN_SDK_VERSION := 28
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

include $(BUILD_CTS_SUPPORT_PACKAGE)


include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := CtsUseEmbeddedDexAppSplit_Canonical
LOCAL_MANIFEST_FILE := feature_split/AndroidManifest.xml

# We want the dex to be uncompressed, but there is a side effect of extra
# android:useEmbeddedDex in the manifest (which the framework will ignore
# for split).
LOCAL_USE_EMBEDDED_DEX := true

LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under, feature_split/src)
LOCAL_SDK_VERSION := current
LOCAL_MIN_SDK_VERSION := 27
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

include $(BUILD_CTS_SUPPORT_PACKAGE)


include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := CtsUseEmbeddedDexAppSplit_CompressedDex
LOCAL_MANIFEST_FILE := feature_split/AndroidManifest.xml

LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under, feature_split/src)
LOCAL_SDK_VERSION := current
LOCAL_MIN_SDK_VERSION := 27
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

include $(BUILD_CTS_SUPPORT_PACKAGE)


#include $(CLEAR_VARS)
#
#LOCAL_PACKAGE_NAME := CtsUseEmbeddedDexAppSplit_CompressedSo
#LOCAL_MANIFEST_FILE := feature_split/AndroidManifest.xml
#
#LOCAL_MODULE_TAGS := tests
#LOCAL_SRC_FILES := $(call all-java-files-under, feature_split/src)
#LOCAL_PREBUILT_JNI_LIBS := dummy.so
#LOCAL_SDK_VERSION := current
#LOCAL_MIN_SDK_VERSION := 27
#LOCAL_COMPATIBILITY_SUITE := cts vts general-tests
#LOCAL_PROGUARD_ENABLED := disabled
#LOCAL_DEX_PREOPT := false
#
#include $(BUILD_CTS_SUPPORT_PACKAGE)
