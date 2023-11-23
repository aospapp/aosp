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

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV1
LOCAL_MANIFEST_FILE := version1_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV1_instant
LOCAL_MANIFEST_FILE := version1_instant_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV2
LOCAL_MANIFEST_FILE := version2_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV2_instant
LOCAL_MANIFEST_FILE := version2_instant_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestApp2V1
LOCAL_MANIFEST_FILE := version1_package2_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestApp2V2
LOCAL_MANIFEST_FILE := version2_package2_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV1_badsignature
LOCAL_MANIFEST_FILE := version1_badsignature_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV1_badb64_config
LOCAL_MANIFEST_FILE := version1_badb64_config_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV1_badb64_signature
LOCAL_MANIFEST_FILE := version1_badb64_signature_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk


include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV3_configv1
LOCAL_MANIFEST_FILE := version3_configv1_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CtsSignedConfigTestAppV1_debug_key
LOCAL_MANIFEST_FILE := version1_debug_key_AndroidManifest.xml
include $(LOCAL_PATH)/build_signedconfig_apk.mk
