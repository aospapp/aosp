#
# Copyright (C) 2020 The Android Open-Source Project
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

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.fingerprint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.fingerprint.xml

# Fingerprint manifest
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
DEVICE_MANIFEST_FILE += \
    device/google/gs101/fingerprint/vendor.goodix.hardware.biometrics.fingerprint@2.1-service.xml
endif

# Include the Goodix AIDL HAL namespaces.
PRODUCT_SOONG_NAMESPACES += vendor/goodix/udfps/fp_utils
ifeq ($(GOODIX_CONFIG_BUILD_VERSION), g6_trusty)
PRODUCT_SOONG_NAMESPACES += vendor/goodix/udfps/g6_trusty
PRODUCT_SOONG_NAMESPACES += vendor/goodix/udfps/g6_aidl_trusty
else ifeq ($(GOODIX_CONFIG_BUILD_VERSION), g7_trusty)
PRODUCT_SOONG_NAMESPACES += vendor/goodix/udfps/g7_trusty
PRODUCT_SOONG_NAMESPACES += vendor/goodix/udfps/g7_aidl_trusty
else
$(error Invalid udfps build version)
endif

$(call soong_config_set,fp_hal_feature,biometric_suez_support,true)
