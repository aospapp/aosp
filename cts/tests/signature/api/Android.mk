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

# We define this in a subdir so that it won't pick up the parent's Android.xml by default.

LOCAL_PATH := $(call my-dir)

# $(1) name of the xml file to be created
# $(2) path to the api text file
define build_xml_api_file
include $(CLEAR_VARS)
LOCAL_MODULE := cts-$(subst .,-,$(1))
LOCAL_MODULE_STEM := $(1)
LOCAL_MODULE_CLASS := ETC
LOCAL_COMPATIBILITY_SUITE := arcts cts vts general-tests
include $(BUILD_SYSTEM)/base_rules.mk
$$(LOCAL_BUILT_MODULE): $(2) | $(APICHECK)
	@echo "Convert API file $$< -> $$@"
	@mkdir -p $$(dir $$@)
	$(hide) $(APICHECK_COMMAND) -convert2xmlnostrip $$< $$@
endef

# NOTE: the output XML file is also used
# in //cts/hostsidetests/devicepolicy/AndroidTest.xml
# by com.android.cts.managedprofile.CurrentApiHelper
# ============================================================
$(eval $(call build_xml_api_file,current.api,frameworks/base/api/current.txt))
$(eval $(call build_xml_api_file,system-current.api,frameworks/base/api/system-current.txt))
$(eval $(call build_xml_api_file,system-removed.api,frameworks/base/api/system-removed.txt))
$(eval $(call build_xml_api_file,apache-http-legacy-current.api,external/apache-http/api/current.txt))
$(eval $(call build_xml_api_file,android-test-base-current.api,frameworks/base/test-base/api/current.txt))
$(eval $(call build_xml_api_file,android-test-mock-current.api,frameworks/base/test-mock/api/current.txt))
$(eval $(call build_xml_api_file,android-test-runner-current.api,frameworks/base/test-runner/api/current.txt))
$(eval $(call build_xml_api_file,car-system-current.api,packages/services/Car/car-lib/api/system-current.txt))
$(eval $(call build_xml_api_file,car-system-removed.api,packages/services/Car/car-lib/api/system-removed.txt))
$(foreach ver,$(PLATFORM_SYSTEMSDK_VERSIONS),\
  $(if $(call math_is_number,$(ver)),\
    $(eval $(call build_xml_api_file,system-$(ver).api,prebuilts/sdk/$(ver)/system/api/android.txt))\
  )\
)

$(foreach ver,$(call int_range_list,28,$(PLATFORM_SDK_VERSION)),\
  $(foreach api_level,public system,\
    $(foreach lib,$(filter-out android,$(filter-out %removed,\
      $(basename $(notdir $(wildcard $(HISTORICAL_SDK_VERSIONS_ROOT)/$(ver)/$(api_level)/api/*.txt))))),\
        $(eval $(call build_xml_api_file,$(lib)-$(ver)-$(api_level).api,prebuilts/sdk/$(ver)/$(api_level)/api/$(lib).txt)))))
