#
# Copyright (C) 2020 The Android Open Source Project
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

$(call inherit-product, device/google/cuttlefish/vsoc_arm64/auto/aosp_cf.mk)

include device/google/trout/aosp_trout_common.mk

DEVICE_MANIFEST_FILE += device/google/trout/manifest.xml

# Sensor HAL
# The implementations use SCMI, which only works on arm architecture
LOCAL_SENSOR_PRODUCT_PACKAGE ?= \
    android.hardware.sensors@2.0-service.multihal \
    android.hardware.sensors@2.0-service.multihal.rc \
    android.hardware.sensors@2.0-Google-IIO-Subhal \

PRODUCT_COPY_FILES += \
    device/google/trout/product_files/odm/ueventd.rc:$(TARGET_COPY_OUT_ODM)/ueventd.rc \

PRODUCT_COPY_FILES += \
    device/google/trout/product_files/vendor/etc/input-port-associations.xml:$(TARGET_COPY_OUT_VENDOR)/etc/input-port-associations.xml \

PRODUCT_COPY_FILES += device/google/trout/product_files/vendor/etc/sensors/hals.conf:$(TARGET_COPY_OUT_VENDOR)/etc/sensors/hals.conf

BOARD_VENDOR_KERNEL_MODULES += \
    $(wildcard device/google/trout-kernel/5.4-arm64/*.ko) \

PRODUCT_NAME := aosp_trout_arm64
PRODUCT_DEVICE := vsoc_arm64
PRODUCT_MODEL := arm64 trout
