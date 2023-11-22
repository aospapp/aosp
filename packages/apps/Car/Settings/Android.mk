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

ifneq ($(TARGET_BUILD_PDK), true)

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# To avoid build errors, build empty package for non-platform builds
# (for example, projected). See b/30064991
ifeq (,$(TARGET_BUILD_APPS))
  LOCAL_PACKAGE_NAME := CarSettings
  LOCAL_OVERRIDES_PACKAGES := Settings
  LOCAL_PRIVATE_PLATFORM_APIS := true

  LOCAL_SRC_FILES := $(call all-java-files-under, src)

  LOCAL_USE_AAPT2 := true

  LOCAL_JAVA_LIBRARIES := \
      android.car \
      telephony-common

  LOCAL_STATIC_ANDROID_LIBRARIES := \
      androidx.car_car \
      androidx.lifecycle_lifecycle-common-java8 \
      androidx.lifecycle_lifecycle-extensions \
      androidx.preference_preference \
      androidx-constraintlayout_constraintlayout \
      car-apps-common \
      car-settings-lib \
      car-setup-wizard-lib-utils \
      SettingsLib

  # Including the resources for the static android libraries allows this app to pick up their static overlays.
  LOCAL_RESOURCE_DIR += \
      $(LOCAL_PATH)/res \
      $(LOCAL_PATH)/../libs/car-apps-common/res \
      $(LOCAL_PATH)/../libs/car-settings-lib/res \
      $(LOCAL_PATH)/../../../../frameworks/base/packages/SettingsLib/res

  LOCAL_CERTIFICATE := platform

  LOCAL_MODULE_TAGS := optional

  LOCAL_PROGUARD_ENABLED := disabled

  LOCAL_PRIVILEGED_MODULE := true

  LOCAL_DEX_PREOPT := false

  LOCAL_STATIC_JAVA_LIBRARIES := \
      android.car.userlib \
      androidx-constraintlayout_constraintlayout-solver \
      jsr305

  LOCAL_REQUIRED_MODULES := privapp_whitelist_com.android.car.settings

  LOCAL_DX_FLAGS := --multi-dex

  include $(BUILD_PACKAGE)
endif

###################################################################################
# Duplicate of CarSettings which includes testing only resources for Robolectric #
###################################################################################
include $(CLEAR_VARS)

# To avoid build errors, build empty package for non-platform builds
# (for example, projected). See b/30064991
ifeq (,$(TARGET_BUILD_APPS))
  LOCAL_PACKAGE_NAME := CarSettingsForTesting
  LOCAL_OVERRIDES_PACKAGES := Settings
  LOCAL_PRIVATE_PLATFORM_APIS := true

  LOCAL_SRC_FILES := $(call all-java-files-under, src)

  LOCAL_USE_AAPT2 := true

  LOCAL_JAVA_LIBRARIES := \
      android.car \
      telephony-common

  LOCAL_STATIC_ANDROID_LIBRARIES := \
      androidx.car_car \
      androidx.lifecycle_lifecycle-common-java8 \
      androidx.lifecycle_lifecycle-extensions \
      androidx.preference_preference \
      androidx-constraintlayout_constraintlayout \
      car-apps-common \
      car-settings-lib \
      car-setup-wizard-lib-utils \
      SettingsLib

  # Testing only resources must be applied first so they take precedence.
  LOCAL_RESOURCE_DIR := \
      $(LOCAL_PATH)/tests/robotests/res \
      $(LOCAL_PATH)/res \
      $(LOCAL_PATH)/../libs/car-apps-common/res \
      $(LOCAL_PATH)/../libs/car-settings-lib/res \
      $(LOCAL_PATH)/../../../../frameworks/base/packages/SettingsLib/res

  LOCAL_CERTIFICATE := platform

  LOCAL_MODULE_TAGS := optional

  LOCAL_PROGUARD_ENABLED := disabled

  LOCAL_PRIVILEGED_MODULE := true

  LOCAL_DEX_PREOPT := false

  LOCAL_STATIC_JAVA_LIBRARIES := \
      android.car.userlib \
      androidx-constraintlayout_constraintlayout-solver \
      jsr305

  LOCAL_DX_FLAGS := --multi-dex

  include $(BUILD_PACKAGE)
endif
###################################################################################

# Use the following include to make our test apk.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under, $(LOCAL_PATH))
endif

endif
