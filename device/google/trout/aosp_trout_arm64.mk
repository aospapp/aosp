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

#RBC# type_hint string TROUT_KERNEL_IMAGE

TARGET_USES_CF_RILD ?= false
$(call inherit-product, device/google/cuttlefish/vsoc_arm64_only/auto/aosp_cf.mk)

# Prefer ext4 for the system image
TARGET_RO_FILE_SYSTEM_TYPE := ext4

# Audio HAL
# TODO: turn back on goldfish HAL and HFP
TARGET_USES_CUTTLEFISH_AUDIO ?= false
AUDIO_FEATURE_HFP_ENABLED ?= true

# HWComposer choice. Setting this flag to true
# will disable Ranchu and turn on the legacy
# drmhwc. This is not a supported configuration and
# should only be turned on for debugging and experimental
# purposes. In general, omitting this line or leaving the
# default configured (false) will do the right thing and pick
# Ranchu from upstream Cuttlefish
TARGET_ENABLE_DRMHWCOMPOSER ?= false

# Audio Control HAL
# TODO (chenhaosjtuacm, egranata): move them to kernel command line
LOCAL_AUDIOCONTROL_PROPERTIES ?= \
    ro.vendor.audiocontrol.server.cid=1000 \
    ro.vendor.audiocontrol.server.port=9410 \

# Tracing Server Address
LOCAL_TRACING_SERVER_PROPERTIES ?= \
    ro.vendor.tracing.server.cid=1000 \
    ro.vendor.tracing.server.port=9510 \

include device/google/trout/aosp_trout_common.mk

DEVICE_MANIFEST_FILE += device/google/trout/trout_arm64/manifest.xml

PRODUCT_PROPERTY_OVERRIDES += \
	vendor.ser.bt-uart?= \

PRODUCT_PACKAGES += \
	vport_trigger \

# Sensor HAL
# The implementations use SCMI, which only works on arm architecture
LOCAL_SENSOR_PRODUCT_PACKAGE ?= \
    android.hardware.sensors@2.0-service.multihal \
    android.hardware.sensors@2.0-service.multihal.rc \
    android.hardware.sensors@2.0-Google-IIO-Subhal \

LOCAL_SENSOR_FILE_OVERRIDES := true

UEVENTD_ODM_COPY_FILE ?= device/google/trout/product_files/odm/ueventd.rc

PRODUCT_COPY_FILES += \
    $(UEVENTD_ODM_COPY_FILE):$(TARGET_COPY_OUT_ODM)/etc/ueventd.rc \
    device/google/trout/hal/sensors/2.0/config/sensor_hal_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/sensors/sensor_hal_configuration.xml \
    device/google/trout/product_files/odm/usr/idc/Vendor_0fff_Product_0fff.idc:$(TARGET_COPY_OUT_ODM)/usr/idc/Vendor_0fff_Product_0fff.idc \
    device/google/trout/product_files/vendor/etc/sensors/hals.conf:$(TARGET_COPY_OUT_VENDOR)/etc/sensors/hals.conf \
    frameworks/native/data/etc/android.hardware.sensor.gyroscope.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.gyroscope.xml \
    frameworks/native/data/etc/android.hardware.sensor.accelerometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.accelerometer.xml \

# WiFi configuration
PRODUCT_ENFORCE_MAC80211_HWSIM ?= false
ifndef DEVICE_VIRTWIFI_PORT
DEVICE_VIRTWIFI_PORT := eth0
endif
PRODUCT_PROPERTY_OVERRIDES += ro.vendor.disable_rename_eth0?=true
PRODUCT_COPY_FILES += device/google/trout/trout_arm64/wifi/virtwifi.sh:$(TARGET_COPY_OUT_VENDOR)/bin/virtwifi.sh
PRODUCT_COPY_FILES += device/google/trout/trout_arm64/wifi/virtwifi.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/virtwifi.rc

PRODUCT_NAME := aosp_trout_arm64
PRODUCT_DEVICE := trout_arm64
PRODUCT_MODEL := arm64 trout
