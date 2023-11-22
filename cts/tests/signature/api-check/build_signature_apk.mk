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

# Specify the following variables before including:
#
#     LOCAL_PACKAGE_NAME
#         the name of the package
#
#     LOCAL_SIGNATURE_API_FILES
#         the list of api files needed

# don't include this package in any target
LOCAL_MODULE_TAGS := tests

# Tag this module as a cts test artifact
LOCAL_COMPATIBILITY_SUITE := cts vts general-tests

LOCAL_SDK_VERSION := current

LOCAL_STATIC_JAVA_LIBRARIES += cts-api-signature-test

LOCAL_JNI_SHARED_LIBRARIES += libclassdescriptors
LOCAL_MULTILIB := both

LOCAL_ADDITIONAL_DEPENDENCIES += \
    $(addprefix $(COMPATIBILITY_TESTCASES_OUT_cts)/,$(LOCAL_SIGNATURE_API_FILES))

# Add dependencies needed to build/run the test with atest.
#
# This is a temporary workaround as described in b/123393637. It adds the dependencies that
# atest requires for each of the modules specified in the LOCAL_SIGNATURE_API_FILES. The
# mapping from module name to the dependency is slightly complicated due to inconsistencies
# in the mapping for the different file types, i.e. .api, .csv and .zip. Those
# inconsistencies will be resolved by build improvement work mentioned in b/123393637.
#
# Converts:
#     current.api -> $(TARGET_OUT_TESTCASES)/cts-current-api/current.api
#     hiddenapi_flags.csv -> $(TARGET_OUT_TESTCASES)/cts-hiddenapi_flags-csv/hiddenapi_flags.csv
#     system-all.api.zip -> $(TARGET_OUT_TESTCASES)/cts-system-all.api/system-all.api.zip

# Construct module name directory from file name, matches behavior in the
# build_xml_api_file function in ../api/Android.mk plus some extra rules for handling slight
# inconsistencies with that behavior for the ..all.zip files used by some signature tests.
#   Replace . with -
#   Prefix every entry with cts-
#   Replace -all-api-zip with -all.api to handle ...all.zip files
#
cts_signature_module_deps := $(LOCAL_SIGNATURE_API_FILES)
cts_signature_module_deps := $(subst .,-,$(cts_signature_module_deps))
cts_signature_module_deps := $(addprefix cts-,$(cts_signature_module_deps))
cts_signature_module_deps := $(subst -all-api-zip,-all.api,$(cts_signature_module_deps))

LOCAL_REQUIRED_MODULES := $(cts_signature_module_deps)

LOCAL_DEX_PREOPT := false
LOCAL_PROGUARD_ENABLED := disabled

LOCAL_USE_EMBEDDED_NATIVE_LIBS := false

ifneq (,$(wildcard $(LOCAL_PATH)/src))
  LOCAL_SRC_FILES := $(call all-java-files-under, src)
endif

include $(BUILD_CTS_PACKAGE)

LOCAL_SIGNATURE_API_FILES :=
cts_signature_module_deps :=
