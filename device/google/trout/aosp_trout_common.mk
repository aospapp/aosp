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

$(call add_soong_config_namespace,audio_extn_config)
$(call add_soong_config_var_value,audio_extn_config,isHFPEnabled,$(AUDIO_FEATURE_HFP_ENABLED))

PRODUCT_PACKAGE_OVERLAYS += device/google/trout/product_files/overlay

LOCAL_DEVICE_FCM_MANIFEST_FILE ?= device/google/trout/manifest.xml

ifeq ($(TARGET_USES_CUTTLEFISH_AUDIO),false)
# Car Emulator Audio HAL
LOCAL_AUDIO_PRODUCT_PACKAGE ?= \
    audio.primary.caremu \
    audio.r_submix.default \
    android.hardware.audio@6.0-impl:32 \
    android.hardware.audio.effect@6.0-impl:32 \
    android.hardware.audio.service \
    android.hardware.soundtrigger@2.3-impl

LOCAL_AUDIO_DEVICE_PACKAGE_OVERLAYS ?= device/generic/car/emulator/audio/overlay

LOCAL_AUDIO_PROPERTIES ?= \
    ro.hardware.audio.primary=caremu \
    ro.vendor.caremu.audiohal.out_period_ms=16 \
    ro.vendor.caremu.audiohal.in_period_ms=16

ifndef LOCAL_AUDIO_PRODUCT_COPY_FILES
LOCAL_AUDIO_PRODUCT_COPY_FILES := \
    device/google/trout/product_files/vendor/etc/audio_policy_configuration.emulator.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    device/google/trout/product_files/vendor/etc/car_audio_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/car_audio_configuration.xml \
    frameworks/native/data/etc/android.hardware.broadcastradio.xml:system/etc/permissions/android.hardware.broadcastradio.xml \
    frameworks/av/services/audiopolicy/config/a2dp_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml
endif
endif

# Audio Control HAL
LOCAL_AUDIOCONTROL_HAL_PRODUCT_PACKAGE ?= android.hardware.automotive.audiocontrol-service.trout

# Dumpstate HAL
LOCAL_DUMPSTATE_PRODUCT_PACKAGE ?= android.hardware.automotive.dumpstate-service.trout
LOCAL_DUMPSTATE_PROPERTIES ?= \
    ro.vendor.dumpstate.server.cid=2 \
    ro.vendor.dumpstate.server.port=9310 \
    ro.vendor.helpersystem.log_loc=/data/host_logs \

# Vehicle HAL
ENABLE_VHAL_FAKE_GRPC_SERVER ?= false
LOCAL_VHAL_PROPERTIES ?=
TROUT_DEFAULT_VHAL_PACKAGES = android.hardware.automotive.vehicle@default-trout-service
ifeq ($(ENABLE_VHAL_FAKE_GRPC_SERVER),true)
TROUT_DEFAULT_VHAL_PACKAGES += android.hardware.automotive.vehicle@default-trout-fake-hardware-grpc-server
LOCAL_VHAL_PROPERTIES += ro.vendor.vehiclehal.server.use_local_fake_server=true
endif
LOCAL_VHAL_PRODUCT_PACKAGE ?= ${TROUT_DEFAULT_VHAL_PACKAGES}

# EVS HAL
LOCAL_EVS_RRO_PACKAGE_OVERLAYS ?= TroutEvsOverlay
ENABLE_EVS_SERVICE ?= true
ENABLE_MOCK_EVSHAL ?= false
ENABLE_EVS_SAMPLE ?= true
ENABLE_SAMPLE_EVS_APP ?= false
ENABLE_CAREVSSERVICE_SAMPLE ?= true

PRODUCT_PACKAGES += $(LOCAL_EVS_RRO_PACKAGE_OVERLAYS)

ifeq ($(LOCAL_EVS_PRODUCT_COPY_FILES),)
LOCAL_EVS_PRODUCT_COPY_FILES := \
    device/google/trout/product_files/etc/automotive/evs/config_override.json:${TARGET_COPY_OUT_VENDOR}/etc/automotive/evs/config_override.json \
    device/google/trout/product_files/vendor/etc/automotive/evs/evs_configuration_override.xml:$(TARGET_COPY_OUT_VENDOR)/etc/automotive/evs/evs_configuration_override.xml
endif
PRODUCT_COPY_FILES += $(LOCAL_EVS_PRODUCT_COPY_FILES)

# A device inheriting trout can enable Vulkan support.
TARGET_VULKAN_SUPPORT ?= false

PRODUCT_PROPERTY_OVERRIDES += \
    ro.hardware.type=automotive \
    ${LOCAL_AUDIO_PROPERTIES} \
    ${LOCAL_AUDIOCONTROL_PROPERTIES} \
    ${LOCAL_DUMPSTATE_PROPERTIES} \
    ${LOCAL_TRACING_SERVER_PROPERTIES} \
    ${LOCAL_VHAL_PROPERTIES} \
    ro.audio.flinger_standbytime_ms=0

ifeq ($(TARGET_DISABLE_BOOT_ANIMATION),true)
PRODUCT_PROPERTY_OVERRIDES += debug.sf.nobootanimation=1
endif

PRODUCT_CHARACTERISTICS := nosdcard,automotive

TARGET_BOARD_INFO_FILE ?= device/google/trout/board-info.txt

# Keymaster HAL
LOCAL_KEYMINT_PRODUCT_PACKAGE ?= android.hardware.keymaster@4.1-service

# Gatekeeper HAL
LOCAL_GATEKEEPER_PRODUCT_PACKAGE ?= android.hardware.gatekeeper@1.0-service.software

PRODUCT_PACKAGES += tinyplay tinycap

# Trout fstab (workaround b/182190949)
PRODUCT_COPY_FILES += \
    device/google/trout/product_files/fstab.trout:$(TARGET_COPY_OUT_RAMDISK)/fstab.trout \
    device/google/trout/product_files/fstab.trout:$(TARGET_COPY_OUT_RAMDISK)/first_stage_ramdisk/fstab.trout \
    device/google/trout/product_files/fstab.trout:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.trout \
    device/google/trout/product_files/fstab.trout:$(TARGET_COPY_OUT_RECOVERY)/root/first_stage_ramdisk/fstab.trout \
    device/google/trout/product_files/fstab.trout:$(TARGET_COPY_OUT_RAMDISK)/first_stage_ramdisk/fstab.trout

# User HAL support
TARGET_SUPPORTS_USER_HAL ?= false

ifeq ($(TARGET_SUPPORTS_USER_HAL),false)
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += android.car.user_hal_enabled=false
endif

PRODUCT_PACKAGES += android.automotive.tracing-client.trout

BOARD_SEPOLICY_DIRS += device/google/trout/sepolicy/vendor/google
