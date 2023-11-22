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

LOCAL_MODULE := CtsSignedConfigHostTestCases

LOCAL_CTS_TEST_PACKAGE := android.signedconfig

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := \
    tools-common-prebuilt \
    cts-tradefed \
    tradefed \
    compatibility-host-util \
    guava \
    truth-prebuilt

LOCAL_STATIC_JAVA_LIBRARIES := \
    hamcrest-library

# tag this module as a cts test artifact
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests

LOCAL_ADDITIONAL_DEPENDENCIES := $(call module-installed-files, \
    CtsSignedConfigTestAppV1 \
    CtsSignedConfigTestAppV1_instant \
    CtsSignedConfigTestAppV2 \
    CtsSignedConfigTestAppV2_instant \
    CtsSignedConfigTestApp2V1 \
    CtsSignedConfigTestApp2V2 \
    CtsSignedConfigTestAppV1_badsignature \
    CtsSignedConfigTestAppV1_badb64_config \
    CtsSignedConfigTestAppV1_badb64_signature \
    CtsSignedConfigTestAppV3_configv1 \
    CtsSignedConfigTestAppV1_debug_key \
    )


include $(BUILD_CTS_HOST_JAVA_LIBRARY)

# Build the test APKs using their own makefiles
include $(call all-makefiles-under,$(LOCAL_PATH))
