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

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := BugReportApp
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_AAPT_FLAGS := --auto-add-overlay

LOCAL_MODULE_TAGS := optional

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_CERTIFICATE := platform

LOCAL_PRIVILEGED_MODULE := true

LOCAL_DEX_PREOPT := false

LOCAL_JAVA_LIBRARIES += \
    android.car

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.recyclerview_recyclerview \
    br_google-api-java-client \
    br_google-api-client-android \
    br_google-oauth-client \
    br_google-http-client \
    br_google-http-client-android \
    br_google-http-client-jackson2 \
    br_jackson-core \
    br_gson-2.1 \
    br_google-storage-services \
    br_apache_commons \
    guava \
    jsr305

LOCAL_REQUIRED_MODULES := privapp_whitelist_com.google.android.car.bugreport

include $(BUILD_PACKAGE)

# ====  prebuilt library  ========================
include $(CLEAR_VARS)

COMMON_LIBS_PATH := ../../../../../prebuilts/tools/common/m2/repository

LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := \
    br_google-api-java-client:libs/google-api-client-1.25.0.jar \
    br_google-api-client-android:libs/google-api-client-android-1.25.0.jar \
    br_google-oauth-client:libs/google-oauth-client-1.25.0.jar \
    br_google-http-client:libs/google-http-client-1.25.0.jar \
    br_google-http-client-android:libs/google-http-client-android-1.25.0.jar \
    br_jackson-core:libs/jackson-core-2.9.6.jar \
    br_gson-2.1:libs/gson-2.1.jar \
    br_google-http-client-jackson2:libs/google-http-client-jackson2-1.25.0.jar \
    br_google-storage-services:libs/google-api-services-storage-v1-rev136-1.25.0.jar \
    br_apache_commons:$(COMMON_LIBS_PATH)/org/eclipse/tycho/tycho-bundles-external/0.18.1/eclipse/plugins/org.apache.commons.codec_1.4.0.v201209201156.jar

include $(BUILD_MULTI_PREBUILT)
