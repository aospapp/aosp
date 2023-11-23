#
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
#
ifneq ($(TARGET_BUILD_PDK), true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := CarDialerApp
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_REQUIRED_MODULES := privapp_whitelist_com.android.car.dialer

LOCAL_OVERRIDES_PACKAGES := Dialer

LOCAL_MODULE_TAGS := optional

LOCAL_USE_AAPT2 := true

LOCAL_JAVA_LIBRARIES += \
    android.car

LOCAL_STATIC_ANDROID_LIBRARIES += \
    androidx.recyclerview_recyclerview \
    androidx.lifecycle_lifecycle-extensions \
    androidx.preference_preference \
    androidx-constraintlayout_constraintlayout \
    androidx.legacy_legacy-support-v4 \
    androidx.cardview_cardview \
    car-apps-common \
    car-arch-common \
    car-telephony-common \
    car-theme-lib \

# Including the resources for the static android libraries allows to pick up their static overlays.
LOCAL_RESOURCE_DIR += \
    $(LOCAL_PATH)/../libs/car-apps-common/res \
    $(LOCAL_PATH)/../libs/car-telephony-common/res \
    $(LOCAL_PATH)/../libs/car-theme-lib/res

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx-constraintlayout_constraintlayout-solver \
    guava \
    car-glide \
    car-glide-disklrucache \
    car-gifdecoder \
    libphonenumber

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_PRIVILEGED_MODULE := true

LOCAL_DEX_PREOPT := false

include $(BUILD_PACKAGE)

###################################################################################
# Duplicate of CarDialerApp which includes testing only resources for Robolectric #
###################################################################################
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/res \
    $(LOCAL_PATH)/tests/robotests/res

LOCAL_PACKAGE_NAME := CarDialerAppForTesting
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MODULE_TAGS := optional

LOCAL_USE_AAPT2 := true

LOCAL_JAVA_LIBRARIES += \
    android.car

LOCAL_STATIC_ANDROID_LIBRARIES += \
    androidx.recyclerview_recyclerview \
    androidx.lifecycle_lifecycle-extensions \
    androidx.preference_preference \
    androidx-constraintlayout_constraintlayout \
    androidx.legacy_legacy-support-v4 \
    androidx.cardview_cardview \
    car-apps-common \
    car-arch-common \
    car-telephony-common \
    car-theme-lib \

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx-constraintlayout_constraintlayout-solver \
    guava \
    car-glide \
    car-glide-disklrucache \
    car-gifdecoder \
    libphonenumber

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_PRIVILEGED_MODULE := true

LOCAL_DEX_PREOPT := false

include $(BUILD_PACKAGE)
###################################################################################

# Use the following include to make our test apk.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call all-makefiles-under,$(LOCAL_PATH))
endif

endif
