#
# Copyright (C) 2021 The Android Open-Source Project
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

TARGET_KERNEL_DIR ?= device/google/bluejay-kernel
TARGET_BOARD_KERNEL_HEADERS := device/google/bluejay-kernel/kernel-headers

$(call inherit-product-if-exists, vendor/google_devices/bluejay/prebuilts/device-vendor-bluejay.mk)
$(call inherit-product-if-exists, vendor/google_devices/gs101/prebuilts/device-vendor.mk)
$(call inherit-product-if-exists, vendor/google_devices/gs101/proprietary/device-vendor.mk)
$(call inherit-product-if-exists, vendor/google_devices/bluejay/proprietary/device-vendor.mk)
$(call inherit-product-if-exists, vendor/google_devices/bluejay/proprietary/bluejay/device-vendor-bluejay.mk)
$(call inherit-product-if-exists, vendor/google_devices/bluejay/proprietary/WallpapersBluejay.mk)

GOODIX_CONFIG_BUILD_VERSION := g7_trusty
DEVICE_PACKAGE_OVERLAYS += device/google/bluejay/bluejay/overlay

include device/google/gs101/fingerprint/extension/fingerprint.extension.mk
include device/google/bluejay-sepolicy/bluejay-sepolicy.mk
include device/google/bluejay/audio/bluejay/audio-tables.mk
include device/google/gs101/device-shipping-common.mk
include device/google/gs101/fingerprint/udfps_common.mk
include device/google/gs101/telephony/pktrouter.mk
include hardware/google/pixel/vibrator/cs40l26/device.mk
include device/google/gs101/bluetooth/bluetooth.mk

ifeq ($(filter factory_bluejay, $(TARGET_PRODUCT)),)
include device/google/gs101/fingerprint/udfps_shipping.mk
else
include device/google/gs101/fingerprint/udfps_factory.mk
endif

$(call soong_config_set,lyric,tuning_product,bluejay)
$(call soong_config_set,google3a_config,target_device,bluejay)

# Init files
PRODUCT_COPY_FILES += \
	device/google/bluejay/conf/init.blueport.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.blueport.rc \
	device/google/bluejay/conf/init.bluejay.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.bluejay.rc

# Recovery files
PRODUCT_COPY_FILES += \
	device/google/gs101/conf/init.recovery.device.rc:$(TARGET_COPY_OUT_RECOVERY)/root/init.recovery.bluejay.rc

# insmod files
PRODUCT_COPY_FILES += \
	device/google/bluejay/init.insmod.bluejay.cfg:$(TARGET_COPY_OUT_VENDOR)/etc/init.insmod.bluejay.cfg

# Thermal Config
PRODUCT_COPY_FILES += \
	device/google/bluejay/thermal_info_config_bluejay.json:$(TARGET_COPY_OUT_VENDOR)/etc/thermal_info_config.json

# Power HAL config
PRODUCT_COPY_FILES += \
	device/google/bluejay/powerhint.json:$(TARGET_COPY_OUT_VENDOR)/etc/powerhint.json

# Camera
PRODUCT_COPY_FILES += \
	device/google/bluejay/media_profiles_bluejay.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_V1_0.xml

PRODUCT_PROPERTY_OVERRIDES += \
    persist.vendor.camera.extended_launch_boost=1 \
    persist.vendor.camera.raise_buf_allocation_priority=1 \
    persist.vendor.camera.fixed_fps_range_boost=1

# Display Config
PRODUCT_COPY_FILES += \
	device/google/bluejay/display_colordata_dev_cal0.pb:$(TARGET_COPY_OUT_VENDOR)/etc/display_colordata_dev_cal0.pb \
	device/google/bluejay/display_golden_cal0.pb:$(TARGET_COPY_OUT_VENDOR)/etc/display_golden_cal0.pb

# Media Performance Class 12
PRODUCT_PROPERTY_OVERRIDES += ro.odm.build.media_performance_class=31

# NFC
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.nfc.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.xml \
	frameworks/native/data/etc/android.hardware.nfc.hce.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.hce.xml \
	frameworks/native/data/etc/android.hardware.nfc.hcef.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.hcef.xml \
	frameworks/native/data/etc/com.nxp.mifare.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/com.nxp.mifare.xml \
	frameworks/native/data/etc/android.hardware.nfc.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.uicc.xml \
	frameworks/native/data/etc/android.hardware.nfc.ese.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.ese.xml \
	device/google/bluejay/nfc/libnfc-hal-st.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-hal-st.conf \
	device/google/bluejay/nfc/libnfc-hal-st-GB17L.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-hal-st-GB17L.conf \
	device/google/bluejay/nfc/libnfc-nci.conf:$(TARGET_COPY_OUT_PRODUCT)/etc/libnfc-nci.conf

PRODUCT_PACKAGES += \
	NfcNci \
	Tag \
	android.hardware.nfc@1.2-service.st

# SecureElement
PRODUCT_PACKAGES += \
	android.hardware.secure_element@1.2-service-gto

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.se.omapi.ese.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.se.omapi.ese.xml \
	frameworks/native/data/etc/android.hardware.se.omapi.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.se.omapi.uicc.xml \
        device/google/bluejay/nfc/libse-gto-hal.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libse-gto-hal.conf

DEVICE_MANIFEST_FILE += \
	device/google/bluejay/nfc/manifest_nfc.xml \
	device/google/bluejay/nfc/manifest_se_bluejay.xml

# PowerStats HAL
PRODUCT_SOONG_NAMESPACES += \
    device/google/bluejay/powerstats/bluejay \
    device/google/bluejay

# Increment the SVN for any official public releases
PRODUCT_VENDOR_PROPERTIES += \
    ro.vendor.build.svn=14

# DCK properties based on target
PRODUCT_PROPERTY_OVERRIDES += \
    ro.gms.dck.eligible_wcc=2

# Trusty liboemcrypto.so
PRODUCT_SOONG_NAMESPACES += vendor/google_devices/bluejay/prebuilts

# Display LBE
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += vendor.display.lbe.supported=1

# Bluetooth Hal Extension test tools
PRODUCT_PACKAGES_DEBUG += \
    sar_test \
    hci_inject

# Config of primary display frames to reach LHBM peak brightness
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += vendor.primarydisplay.lhbm.frames_to_reach_peak_brightness=2

# Bluetooth Tx power caps for bluejay
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/bluetooth_power_limits.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits.csv \
    $(LOCAL_PATH)/bluetooth_power_limits_GB17L_JP.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_JP.csv \
    $(LOCAL_PATH)/bluetooth_power_limits_GX7AS_CA.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_CA.csv \
    $(LOCAL_PATH)/bluetooth_power_limits_GB62Z_US.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_GB62Z_US.csv \
    $(LOCAL_PATH)/bluetooth_power_limits_GX7AS_US.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_GX7AS_US.csv \
    $(LOCAL_PATH)/bluetooth_power_limits_G1AZG_EU.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_G1AZG_EU.csv \
    $(LOCAL_PATH)/bluetooth_power_limits_GB62Z_EU.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_GB62Z_EU.csv

# Bluetooth
PRODUCT_PRODUCT_PROPERTIES += \
    persist.bluetooth.a2dp_aac.vbr_supported=true

# Bluetooth HAL
PRODUCT_PACKAGES += \
	bt_vendor.conf

# Set zram size
PRODUCT_VENDOR_PROPERTIES += \
    vendor.zram.size=2g

# Enable camera 1080P 60FPS binning mode
PRODUCT_VENDOR_PROPERTIES += \
    persist.vendor.camera.1080P_60fps_binning=true

# Enable camera exif model/make reporting
PRODUCT_VENDOR_PROPERTIES += \
    persist.vendor.camera.exif_reveal_make_model=true

# Disable rear light sensor probing explicitly
PRODUCT_VENDOR_PROPERTIES += \
    persist.vendor.camera.rls_supported=false

# Fingerprint antispoof property
PRODUCT_PRODUCT_PROPERTIES +=\
    persist.vendor.fingerprint.disable.fake.override=none

# Fingerprint als feed forward
PRODUCT_VENDOR_PROPERTIES += \
    persist.vendor.udfps.als_feed_forward_supported=true \
    persist.vendor.udfps.lhbm_controlled_in_hal_supported=true

# Fingerprint MAX auth latency
PRODUCT_VENDOR_PROPERTIES += \
    vendor.gf.debug.timer.threshold=1,400,400,400,600,600,600

# Hide cutout overlays
PRODUCT_PACKAGES += \
    NoCutoutOverlay \
    AvoidAppsInCutoutOverlay

# SKU specific RROs
PRODUCT_PACKAGES += \
    SettingsOverlayGB17L \
    SettingsOverlayG1AZG \
    SettingsOverlayGB62Z \
    SettingsOverlayGX7AS

# Set support hide display cutout feature
PRODUCT_PRODUCT_PROPERTIES += \
    ro.support_hide_display_cutout=true

# Set support one-handed mode
PRODUCT_PRODUCT_PROPERTIES += \
    ro.support_one_handed_mode=true

# GPS xml
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
	PRODUCT_COPY_FILES += \
		device/google/bluejay/gps.xml.b3:$(TARGET_COPY_OUT_VENDOR)/etc/gnss/gps.xml
else
	PRODUCT_COPY_FILES += \
		device/google/bluejay/gps_user.xml.b3:$(TARGET_COPY_OUT_VENDOR)/etc/gnss/gps.xml
endif

# This device is shipped with 32 (Android S V2)
PRODUCT_SHIPPING_API_LEVEL := 32

# Vibrator HAL
PRODUCT_VENDOR_PROPERTIES += \
	ro.vendor.vibrator.hal.supported_primitives=243

# Device features
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml

# Keyboard bottom padding in dp for portrait mode and height ratio
PRODUCT_PRODUCT_PROPERTIES += \
    ro.com.google.ime.kb_pad_port_b=6.4 \
    ro.com.google.ime.height_ratio=1.05
