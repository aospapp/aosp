#
# Copyright (C) 2011 The Android Open-Source Project
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

include device/google/gs-common/device.mk
include device/google/gs-common/gs_watchdogd/watchdog.mk
include device/google/gs-common/ramdump/ramdump.mk
include device/google/gs-common/soc/soc.mk
include device/google/gs-common/soc/freq.mk
include device/google/gs-common/modem/modem.mk
include device/google/gs-common/aoc/aoc.mk
include device/google/gs-common/thermal/thermal.mk
include device/google/gs-common/pixel_metrics/pixel_metrics.mk
include device/google/gs-common/performance/perf.mk
include device/google/gs-common/display/dump.mk
include device/google/gs-common/camera/dump.mk
include device/google/gs-common/gxp/dump.mk
include device/google/gs-common/gps/dump/log.mk
include device/google/gs-common/radio/dump.mk
include device/google/gs-common/umfw_stat/umfw_stat.mk
include device/google/gs-common/gear/dumpstate/aidl.mk
include device/google/gs-common/widevine/widevine.mk

TARGET_BOARD_PLATFORM := gs201

AB_OTA_POSTINSTALL_CONFIG += \
	RUN_POSTINSTALL_system=true \
	POSTINSTALL_PATH_system=system/bin/otapreopt_script \
	FILESYSTEM_TYPE_system=ext4 \
POSTINSTALL_OPTIONAL_system=true

# Set Vendor SPL to match platform
VENDOR_SECURITY_PATCH = $(PLATFORM_SECURITY_PATCH)

# Set boot SPL
BOOT_SECURITY_PATCH = $(PLATFORM_SECURITY_PATCH)

# TODO(b/207450311): Remove this flag once implemented
USE_PIXEL_GRALLOC := false
ifeq ($(USE_PIXEL_GRALLOC),true)
	PRODUCT_SOONG_NAMESPACES += hardware/google/gchips/GrallocHAL
endif

PRODUCT_SOONG_NAMESPACES += \
	hardware/google/av \
	hardware/google/gchips \
	hardware/google/gchips/gralloc4 \
	hardware/google/graphics/common \
	hardware/google/graphics/gs201 \
	hardware/google/interfaces \
	hardware/google/pixel \
	device/google/gs201 \
	device/google/gs201/powerstats \
	system/chre/host/hal_generic \
	vendor/google/whitechapel/tools \
	vendor/google/interfaces \
	vendor/google_devices/common/proprietary/confirmatioui_hal \
	vendor/google_nos/host/android \
	vendor/google_nos/test/system-test-harness \
	vendor/google/camera

LOCAL_KERNEL := $(TARGET_KERNEL_DIR)/Image.lz4

# OEM Unlock reporting
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
	ro.oem_unlock_supported=1

ifneq ($(BOARD_WITHOUT_RADIO),true)
# Include vendor telephony soong namespace
PRODUCT_SOONG_NAMESPACES += \
	vendor/samsung_slsi/telephony/$(BOARD_USES_SHARED_VENDOR_TELEPHONY)
endif

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
#Set IKE logs to verbose for WFC
PRODUCT_PROPERTY_OVERRIDES += log.tag.IKE=VERBOSE

#Set Shannon IMS logs to debug
PRODUCT_PROPERTY_OVERRIDES += log.tag.SHANNON_IMS=DEBUG

#Set Shannon QNS logs to debug
PRODUCT_PROPERTY_OVERRIDES += log.tag.ShannonQNS=DEBUG
PRODUCT_PROPERTY_OVERRIDES += log.tag.ShannonQNS-ims=DEBUG
PRODUCT_PROPERTY_OVERRIDES += log.tag.ShannonQNS-emergency=DEBUG
PRODUCT_PROPERTY_OVERRIDES += log.tag.ShannonQNS-mms=DEBUG
PRODUCT_PROPERTY_OVERRIDES += log.tag.ShannonQNS-xcap=DEBUG
PRODUCT_PROPERTY_OVERRIDES += log.tag.ShannonQNS-HC=DEBUG

# Modem userdebug
include device/google/gs201/modem/userdebug.mk
endif

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
# b/36703476: Set default log size to 1M
PRODUCT_PROPERTY_OVERRIDES += \
	ro.logd.size=1M
# b/114766334: persist all logs by default rotating on 30 files of 1MiB
PRODUCT_PROPERTY_OVERRIDES += \
	logd.logpersistd=logcatd \
	logd.logpersistd.size=30
endif

# From system.property
PRODUCT_PROPERTY_OVERRIDES += \
	ro.telephony.default_network=27 \
	persist.vendor.ril.db_ecc.use.iccid_to_plmn=1 \
	persist.vendor.ril.db_ecc.id.type=5
	#rild.libpath=/system/lib64/libsec-ril.so \
	#rild.libargs=-d /dev/umts_ipc0

# SIT-RIL Logging setting
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.ril.log_mask=3 \
	persist.vendor.ril.log.base_dir=/data/vendor/radio/sit-ril \
	persist.vendor.ril.log.chunk_size=5242880 \
	persist.vendor.ril.log.num_file=3

# Enable reboot free DSDS
PRODUCT_PRODUCT_PROPERTIES += \
	persist.radio.reboot_on_modem_change=false

# Configure DSDS by default
PRODUCT_PRODUCT_PROPERTIES += \
	persist.radio.multisim.config=dsds

# Enable Early Camping
PRODUCT_PRODUCT_PROPERTIES += \
	persist.vendor.ril.camp_on_earlier=1

# Enable SET_SCREEN_STATE request
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.ril.enable_set_screen_state=1

# Set the Bluetooth Class of Device
ifneq ($(USE_TABLET_BT_COD),true)
# Service Field: 0x5A -> 90
#    Bit 14: LE audio
#    Bit 17: Networking
#    Bit 19: Capturing
#    Bit 20: Object Transfer
#    Bit 22: Telephony
# MAJOR_CLASS: 0x42 -> 66 (Phone)
# MINOR_CLASS: 0x0C -> 12 (Smart Phone)
PRODUCT_PRODUCT_PROPERTIES += \
    bluetooth.device.class_of_device=90,66,12
else
# Service Field: 0x5A -> 90
#    Bit 14: LE audio
#    Bit 17: Networking
#    Bit 19: Capturing
#    Bit 20: Object Transfer
#    Bit 22: Telephony
# MAJOR_CLASS: 0x41 -> 65 (Computer)
# MINOR_CLASS: 0x10 -> 16 (Handheld PC/PDA clamshell)
PRODUCT_PRODUCT_PROPERTIES += \
    bluetooth.device.class_of_device=90,65,16
endif

# Set supported Bluetooth profiles to enabled
PRODUCT_PRODUCT_PROPERTIES += \
	bluetooth.profile.asha.central.enabled?=true \
	bluetooth.profile.a2dp.source.enabled?=true \
	bluetooth.profile.avrcp.target.enabled?=true \
	bluetooth.profile.bas.client.enabled?=true \
	bluetooth.profile.gatt.enabled?=true \
	bluetooth.profile.hfp.ag.enabled?=true \
	bluetooth.profile.hid.device.enabled?=true \
	bluetooth.profile.hid.host.enabled?=true \
	bluetooth.profile.map.server.enabled?=true \
	bluetooth.profile.opp.enabled?=true \
	bluetooth.profile.pan.nap.enabled?=true \
	bluetooth.profile.pan.panu.enabled?=true \
	bluetooth.profile.pbap.server.enabled?=true \
	bluetooth.profile.sap.server.enabled?=true \

# Carrier configuration default location
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.radio.config.carrier_config_dir=/mnt/vendor/modem_img/images/default/confpack

PRODUCT_PROPERTY_OVERRIDES += \
	telephony.active_modems.max_count=2

USE_LASSEN_OEMHOOK := true

# Use for GRIL
USES_LASSEN_MODEM := true

ifeq ($(USES_GOOGLE_DIALER_CARRIER_SETTINGS),true)
USE_GOOGLE_DIALER := true
USE_GOOGLE_CARRIER_SETTINGS := true
endif

ifeq ($(USES_GOOGLE_PREBUILT_MODEM_SVC),true)
USE_GOOGLE_PREBUILT_MODEM_SVC := true
endif

# Audio client implementation for RIL
USES_GAUDIO := true

# ######################
# GRAPHICS - GPU (begin)

# Must match BOARD_USES_SWIFTSHADER in BoardConfig.mk
USE_SWIFTSHADER := false

# HWUI
TARGET_USES_VULKAN = true

PRODUCT_SOONG_NAMESPACES += \
	vendor/arm/mali/valhall

$(call soong_config_set,pixel_mali,soc,$(TARGET_BOARD_PLATFORM))

include device/google/gs101/neuralnetwork/neuralnetwork.mk

PRODUCT_PACKAGES += \
	csffw_image_prebuilt__firmware_prebuilt_todx_mali_csffw.bin \
	libGLES_mali \
	vulkan.mali \
	libOpenCL \
	libgpudataproducer \

PRODUCT_VENDOR_PROPERTIES += \
	ro.hardware.vulkan=mali

# Mali Configuration Properties
# b/221255664 prevents setting PROTECTED_MAX_CORE_COUNT=2
PRODUCT_VENDOR_PROPERTIES += \
	vendor.mali.platform.config=/vendor/etc/mali/platform.config \
	vendor.mali.debug.config=/vendor/etc/mali/debug.config \
      	vendor.mali.base_protected_max_core_count=1 \
	vendor.mali.base_protected_tls_max=67108864 \
	vendor.mali.platform_agt_frequency_khz=24576

ifeq ($(USE_SWIFTSHADER),true)
PRODUCT_PACKAGES += \
   libGLESv1_CM_swiftshader \
   libEGL_swiftshader \
   libGLESv2_swiftshader
endif

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml \
	frameworks/native/data/etc/android.hardware.vulkan.version-1_3.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.version.xml \
	frameworks/native/data/etc/android.hardware.vulkan.level-1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.level.xml \
	frameworks/native/data/etc/android.hardware.vulkan.compute-0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.compute.xml \
	frameworks/native/data/etc/android.software.vulkan.deqp.level-2023-03-01.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.vulkan.deqp.level.xml \
	frameworks/native/data/etc/android.software.opengles.deqp.level-2023-03-01.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.opengles.deqp.level.xml

ifeq ($(USE_SWIFTSHADER),true)
PRODUCT_VENDOR_PROPERTIES += \
	ro.hardware.egl = swiftshader
else
PRODUCT_VENDOR_PROPERTIES += \
	ro.hardware.egl = mali
endif

# Configure EGL blobcache
PRODUCT_VENDOR_PROPERTIES += \
	ro.egl.blobcache.multifile=true \
	ro.egl.blobcache.multifile_limit=33554432 \

PRODUCT_VENDOR_PROPERTIES += \
	ro.opengles.version=196610 \
	graphics.gpu.profiler.support=true \
	debug.renderengine.backend=skiaglthreaded

# GRAPHICS - GPU (end)
# ####################

# Device Manifest, Device Compatibility Matrix for Treble
DEVICE_MANIFEST_FILE := \
	device/google/gs201/manifest.xml

ifneq (,$(filter aosp_%,$(TARGET_PRODUCT)))
DEVICE_MANIFEST_FILE += \
	device/google/gs201/manifest_media_aosp.xml

PRODUCT_COPY_FILES += \
	device/google/gs201/media_codecs_aosp_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_c2.xml
else
DEVICE_MANIFEST_FILE += \
	device/google/gs201/manifest_media.xml

PRODUCT_COPY_FILES += \
	device/google/gs201/media_codecs_bo_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_c2.xml \
	device/google/gs201/media_codecs_aosp_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_aosp_c2.xml
endif

DEVICE_MATRIX_FILE := \
	device/google/gs201/compatibility_matrix.xml

DEVICE_PACKAGE_OVERLAYS += device/google/gs201/overlay

# This device is shipped with 33 (Android T)
PRODUCT_SHIPPING_API_LEVEL := 33

# RKP VINTF
-include vendor/google_nos/host/android/hals/keymaster/aidl/strongbox/RemotelyProvisionedComponent-citadel.mk

# Enforce the Product interface
PRODUCT_PRODUCT_VNDK_VERSION := current
PRODUCT_ENFORCE_PRODUCT_PARTITION_INTERFACE := true

# Init files
PRODUCT_COPY_FILES += \
	$(LOCAL_KERNEL):kernel \
	device/google/gs201/conf/init.gs201.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.gs201.usb.rc \
	device/google/gs201/conf/ueventd.gs201.rc:$(TARGET_COPY_OUT_VENDOR)/etc/ueventd.rc

PRODUCT_COPY_FILES += \
	device/google/gs201/conf/init.gs201.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.gs201.rc

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_COPY_FILES += \
	device/google/gs201/conf/init.debug.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.debug.rc
endif

# Recovery files
PRODUCT_COPY_FILES += \
	device/google/gs201/conf/init.recovery.device.rc:$(TARGET_COPY_OUT_RECOVERY)/root/init.recovery.gs201.rc

# Fstab files
PRODUCT_PACKAGES += \
	fstab.gs201 \
	fstab.gs201.vendor_ramdisk \
	fstab.gs201-fips \
	fstab.gs201-fips.vendor_ramdisk

PRODUCT_COPY_FILES += \
	device/google/$(TARGET_BOARD_PLATFORM)/conf/fstab.persist:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.persist \

# Shell scripts
PRODUCT_COPY_FILES += \
	device/google/gs201/init.display.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.display.sh \
	device/google/gs201/disable_contaminant_detection.sh:$(TARGET_COPY_OUT_VENDOR)/bin/hw/disable_contaminant_detection.sh

include device/google/gs-common/insmod/insmod.mk

# For creating dtbo image
PRODUCT_HOST_PACKAGES += \
	mkdtimg

PRODUCT_PACKAGES += \
	messaging

# Contexthub HAL
PRODUCT_PACKAGES += \
	android.hardware.contexthub-service.generic

# CHRE tools
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += \
	chre_power_test_client \
	chre_test_client
endif

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.context_hub.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.context_hub.xml

## Enable the CHRE Daemon
CHRE_USF_DAEMON_ENABLED := true
PRODUCT_PACKAGES += \
	chre \
	preloaded_nanoapps.json

# Filesystem management tools
PRODUCT_PACKAGES += \
	linker.vendor_ramdisk \
	tune2fs.vendor_ramdisk \
	resize2fs.vendor_ramdisk

# Filesystem: convert /dev/block/by-name/persist to ext4 (b/239632964)
PRODUCT_COPY_FILES += \
	device/google/gs201/convert_to_ext4.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/bin/convert_to_ext4.sh \

# Userdata Checkpointing OTA GC
PRODUCT_PACKAGES += \
	checkpoint_gc

# Vendor verbose logging default property
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.verbose_logging_enabled=true
else
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.verbose_logging_enabled=false
endif

# RPMB TA
PRODUCT_PACKAGES += \
	tlrpmb

# Touch firmware
#PRODUCT_COPY_FILES += \
	device/google/gs201/firmware/touch/s6sy761.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/s6sy761.fw
# Touch
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.touchscreen.multitouch.jazzhand.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.touchscreen.multitouch.jazzhand.xml

# Sensors
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.sensor.accelerometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.accelerometer.xml \
	frameworks/native/data/etc/android.hardware.sensor.compass.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.compass.xml \
	frameworks/native/data/etc/android.hardware.sensor.dynamic.head_tracker.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.dynamic.head_tracker.xml \
	frameworks/native/data/etc/android.hardware.sensor.gyroscope.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.gyroscope.xml \
	frameworks/native/data/etc/android.hardware.sensor.light.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.light.xml\
	frameworks/native/data/etc/android.hardware.sensor.stepcounter.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.stepcounter.xml \
	frameworks/native/data/etc/android.hardware.sensor.stepdetector.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.stepdetector.xml
# (See b/240652154)
ifneq ($(DISABLE_SENSOR_BARO_PROX_HIFI),true)
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.sensor.barometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.barometer.xml \
	frameworks/native/data/etc/android.hardware.sensor.hifi_sensors.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.hifi_sensors.xml \
	frameworks/native/data/etc/android.hardware.sensor.proximity.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.proximity.xml
endif

# Add sensor HAL AIDL product packages
PRODUCT_PACKAGES += android.hardware.sensors-service.multihal

# USB HAL
PRODUCT_PACKAGES += \
	android.hardware.usb-service
PRODUCT_PACKAGES += \
	android.hardware.usb.gadget-service

# MIDI feature
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.software.midi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.midi.xml

# eSIM MEP Feature
ifneq ($(DISABLE_TELEPHONY_EUICC),true)
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.telephony.euicc.mep.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/android.hardware.telephony.euicc.mep.xml
endif

# default usb debug functions
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.usb.usbradio.config=dm
endif


PRODUCT_COPY_FILES += \
	device/google/gs201/task_profiles.json:$(TARGET_COPY_OUT_VENDOR)/etc/task_profiles.json

-include hardware/google/pixel/power-libperfmgr/aidl/device.mk

# IRQ rebalancing.
include hardware/google/pixel/rebalance_interrupts/rebalance_interrupts.mk

# PowerStats HAL
PRODUCT_PACKAGES += \
	android.hardware.power.stats-service.pixel

#
# Audio HALs
#

# Audio Configurations
USE_LEGACY_LOCAL_AUDIO_HAL := false
USE_XML_AUDIO_POLICY_CONF := 1

# Enable AAudio MMAP/NOIRQ data path.
PRODUCT_PROPERTY_OVERRIDES += aaudio.mmap_policy=2
PRODUCT_PROPERTY_OVERRIDES += aaudio.mmap_exclusive_policy=2
PRODUCT_PROPERTY_OVERRIDES += aaudio.hw_burst_min_usec=2000

# Calliope firmware overwrite
#PRODUCT_COPY_FILES += \
	device/google/gs201/firmware/calliope_dram.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_dram.bin \
	device/google/gs201/firmware/calliope_sram.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_sram.bin \
	device/google/gs201/firmware/calliope_dram_2.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_dram_2.bin \
	device/google/gs201/firmware/calliope_sram_2.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_sram_2.bin \
	device/google/gs201/firmware/calliope2.dt:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope2.dt \

# Cannot reference variables defined in BoardConfig.mk, uncomment this if
# BOARD_USE_OFFLOAD_AUDIO and BOARD_USE_OFFLOAD_EFFECT are true
## AudioEffectHAL library
#PRODUCT_PACKAGES += \
#	libexynospostprocbundle

# Cannot reference variables defined in BoardConfig.mk, uncomment this if
# BOARD_USE_SOUNDTRIGGER_HAL is true
#PRODUCT_PACKAGES += \
#	sound_trigger.primary.maran9820

# A-Box Service Daemon
#PRODUCT_PACKAGES += main_abox

# Libs
PRODUCT_PACKAGES += \
	com.android.future.usb.accessory

PRODUCT_PACKAGES += \
	android.hardware.graphics.mapper@4.0-impl \
	android.hardware.graphics.allocator-V1-service

PRODUCT_PACKAGES += \
	android.hardware.memtrack-service.pixel \
	libion_exynos \
	libion

PRODUCT_PACKAGES += \
	libhwjpeg

# Video Editor
PRODUCT_PACKAGES += \
	VideoEditorGoogle

# WideVine modules
include device/google/gs201/widevine/device.mk
PRODUCT_PACKAGES += \
	liboemcrypto \

PANTHER_PRODUCT := %panther
CHEETAH_PRODUCT := %cheetah
LYNX_PRODUCT := %lynx
FELIX_PRODUCT := %felix
CLOUDRIPPER_PRODUCT := %cloudripper
TANGOR_PRODUCT := %tangorpro
ifneq (,$(filter $(PANTHER_PRODUCT), $(TARGET_PRODUCT)))
        LOCAL_TARGET_PRODUCT := panther
else ifneq (,$(filter $(CHEETAH_PRODUCT), $(TARGET_PRODUCT)))
        LOCAL_TARGET_PRODUCT := cheetah
else ifneq (,$(filter $(LYNX_PRODUCT), $(TARGET_PRODUCT)))
        LOCAL_TARGET_PRODUCT := lynx
else ifneq (,$(filter $(FELIX_PRODUCT), $(TARGET_PRODUCT)))
        LOCAL_TARGET_PRODUCT := felix
else ifneq (,$(filter $(CLOUDRIPPER_PRODUCT), $(TARGET_PRODUCT)))
        LOCAL_TARGET_PRODUCT := cloudripper
else ifneq (,$(filter $(TANGOR_PRODUCT), $(TARGET_PRODUCT)))
        LOCAL_TARGET_PRODUCT := tangorpro
else
        # WAR: continue defaulting to slider build on gs201 to not
        # break dev targets such as ravenclaw
        LOCAL_TARGET_PRODUCT := slider
endif

$(call soong_config_set,google3a_config,soc,gs201)
$(call soong_config_set,google3a_config,gcam_awb,true)
$(call soong_config_set,google3a_config,ghawb_truetone,true)

ifneq ($(wildcard vendor/google/services/LyricCameraHAL/src),)
$(call soong_config_set,lyric,soc,gs201)
$(call soong_config_set,lyric,use_lyric_camera_hal,true)
# lyric::tuning_product is set in device-specific makefiles,
# such as device/google/${DEVICE}/device-${DEVICE}.mk

$(call soong_config_set,gch,hwl_library,lyric)
endif

# WiFi
PRODUCT_PACKAGES += \
	android.hardware.wifi-service \
	wificond \
	libwpa_client

# Connectivity
PRODUCT_PACKAGES += \
        ConnectivityOverlay

PRODUCT_PACKAGES_DEBUG += \
	f2fs_io \
	check_f2fs \
	f2fs.fibmap \
	dump.f2fs

# Storage dump
include device/google/gs-common/storage/storage.mk

# Storage health HAL
PRODUCT_PACKAGES += \
	android.hardware.health.storage-service.default

# Battery Mitigation
include device/google/gs-common/battery_mitigation/bcl.mk
# storage pixelstats
-include hardware/google/pixel/pixelstats/device.mk

# Enable project quotas and casefolding for emulated storage without sdcardfs
$(call inherit-product, $(SRC_TARGET_DIR)/product/emulated_storage.mk)

$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/android_t_baseline.mk)
PRODUCT_VIRTUAL_AB_COMPRESSION_METHOD := gz

# Enforce generic ramdisk allow list
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)

# Titan-M
ifeq (,$(filter true, $(BOARD_WITHOUT_DTLS)))
include device/google/gs-common/dauntless/gsc.mk
endif

PRODUCT_PACKAGES_DEBUG += \
	WvInstallKeybox

# Copy Camera HFD Setfiles
#PRODUCT_COPY_FILES += \
	device/google/gs201/firmware/camera/libhfd/default_configuration.hfd.cfg.json:$(TARGET_COPY_OUT_VENDOR)/firmware/default_configuration.hfd.cfg.json \
	device/google/gs201/firmware/camera/libhfd/pp_cfg.json:$(TARGET_COPY_OUT_VENDOR)/firmware/pp_cfg.json \
	device/google/gs201/firmware/camera/libhfd/tracker_cfg.json:$(TARGET_COPY_OUT_VENDOR)/firmware/tracker_cfg.json \
	device/google/gs201/firmware/camera/libhfd/WithLightFixNoBN.SDNNmodel:$(TARGET_COPY_OUT_VENDOR)/firmware/WithLightFixNoBN.SDNNmodel

# WiFi
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
	frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
	frameworks/native/data/etc/android.hardware.wifi.aware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.aware.xml \
	frameworks/native/data/etc/android.hardware.wifi.passpoint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.passpoint.xml \
	frameworks/native/data/etc/android.hardware.wifi.rtt.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.rtt.xml

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
	frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml

# (See b/239142680, b/211840489, b/225749853)
ifneq ($(DISABLE_CAMERA_FS_AF),true)
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.camera.flash-autofocus.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.flash-autofocus.xml
else
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.camera.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.xml
endif

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.camera.front.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.front.xml \
	frameworks/native/data/etc/android.hardware.camera.concurrent.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.concurrent.xml \
	frameworks/native/data/etc/android.hardware.camera.full.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.full.xml\
	frameworks/native/data/etc/android.hardware.camera.raw.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.raw.xml\
	vendor/google/services/LyricCameraHAL/src/vendor.android.hardware.camera.preview-dis.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/vendor.android.hardware.camera.preview-dis.xml\

#PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml \
	frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
	frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
	frameworks/native/data/etc/android.hardware.wifi.passpoint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.passpoint.xml \

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.audio.low_latency.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.low_latency.xml \
	frameworks/native/data/etc/android.hardware.audio.pro.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.pro.xml \

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.software.ipsec_tunnels.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.ipsec_tunnels.xml \

PRODUCT_PROPERTY_OVERRIDES += \
	debug.slsi_platform=1 \
	debug.hwc.winupdate=1

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += displaycolor_service
endif

PRODUCT_PROPERTY_OVERRIDES += \
	debug.sf.disable_backpressure=0 \
	debug.sf.enable_gl_backpressure=1 \
	debug.sf.enable_sdr_dimming=1

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.use_phase_offsets_as_durations=1
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.late.sf.duration=10500000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.late.app.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.early.sf.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.early.app.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.earlyGl.sf.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.earlyGl.app.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.frame_rate_multiple_threshold=120
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.layer_caching_active_layer_timeout_ms=1000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.treat_170m_as_sRGB=1

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.enable_layer_caching=true
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.set_idle_timer_ms?=80
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.set_touch_timer_ms=200
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.set_display_power_timer_ms=1000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.use_content_detection_for_refresh_rate=true
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.max_frame_buffer_acquired_buffers=3

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.supports_background_blur=1
PRODUCT_SYSTEM_PROPERTIES += ro.launcher.blur.appLaunch=0

# Must align with HAL types Dataspace
# The data space of wide color gamut composition preference is Dataspace::DISPLAY_P3
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.wcg_composition_dataspace=143261696

# Display
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.has_wide_color_display=true
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.has_HDR_display=true
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.use_color_management=true
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.protected_contents=true
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.display_update_imminent_timeout_ms=50

# force to blend in P3 mode
PRODUCT_PROPERTY_OVERRIDES += \
	persist.sys.sf.native_mode=2 \
	persist.sys.sf.color_mode=9
PRODUCT_COPY_FILES += \
	device/google/gs201/display/display_colordata_cal0.pb:$(TARGET_COPY_OUT_VENDOR)/etc/display_colordata_cal0.pb

# limit DPP downscale ratio
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += vendor.hwc.dpp.downscale=2

# Cannot reference variables defined in BoardConfig.mk, uncomment this if
# BOARD_USES_EXYNOS_DSS_FEATURE is true
## set the dss enable status setup
#PRODUCT_PROPERTY_OVERRIDES += \
#        ro.exynos.dss=1

# Cannot reference variables defined in BoardConfig.mk, uncomment this if
# BOARD_USES_EXYNOS_AFBC_FEATURE is true
# set the dss enable status setup
PRODUCT_PROPERTY_OVERRIDES += \
	ro.vendor.ddk.set.afbc=1

PRODUCT_CHARACTERISTICS := nosdcard

# WIFI COEX
PRODUCT_COPY_FILES += \
	device/google/gs201/wifi/coex_table.xml:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/coex_table.xml

PRODUCT_PACKAGES += hostapd
PRODUCT_PACKAGES += wpa_supplicant
PRODUCT_PACKAGES += wpa_supplicant.conf

WIFI_PRIV_CMD_UPDATE_MBO_CELL_STATUS := enabled

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += wpa_cli
PRODUCT_PACKAGES += hostapd_cli
endif

####################################
## VIDEO
####################################

$(call soong_config_set,bigo,soc,gs201)

# 1. Codec 2.0
# exynos service
PRODUCT_SOONG_NAMESPACES += vendor/samsung_slsi/codec2

PRODUCT_COPY_FILES += \
	device/google/gs201/media_codecs_performance_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance_c2.xml \

PRODUCT_PACKAGES += \
	samsung.hardware.media.c2@1.0-service \
	codec2.vendor.base.policy \
	codec2.vendor.ext.policy \
	libExynosC2ComponentStore \
	libExynosC2H264Dec \
	libExynosC2H264Enc \
	libExynosC2HevcDec \
	libExynosC2HevcEnc \
	libExynosC2Mpeg4Dec \
	libExynosC2Mpeg4Enc \
	libExynosC2H263Dec \
	libExynosC2H263Enc \
	libExynosC2Vp8Dec \
	libExynosC2Vp8Enc \
	libExynosC2Vp9Dec \
	libExynosC2Vp9Enc

PRODUCT_PROPERTY_OVERRIDES += \
       debug.stagefright.c2-poolmask=458752 \
       debug.c2.use_dmabufheaps=1 \
       media.c2.dmabuf.padding=512 \
       debug.stagefright.ccodec_delayed_params=1 \
       ro.vendor.gpu.dataspace=1

# Create input surface on the framework side
PRODUCT_PROPERTY_OVERRIDES += \
	debug.stagefright.c2inputsurface=-1 \

# 2. OpenMAX IL
PRODUCT_COPY_FILES += \
	device/google/gs201/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml \
	device/google/gs201/media_codecs_performance.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance.xml
####################################

# Telephony
#PRODUCT_COPY_FILES += \
	frameworks/av/media/libstagefright/data/media_codecs_google_telephony.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_telephony.xml

# CBD (CP booting deamon)
CBD_USE_V2 := true
CBD_PROTOCOL_SIT := true

# setup dalvik vm configs.
$(call inherit-product, frameworks/native/build/phone-xhdpi-6144-dalvik-heap.mk)

PRODUCT_TAGS += dalvik.gc.type-precise

# Exynos OpenVX framework
PRODUCT_PACKAGES += \
		libexynosvision

ifeq ($(TARGET_USES_CL_KERNEL),true)
PRODUCT_PACKAGES += \
	libopenvx-opencl
endif

# Trusty (KM, GK, Storage)
$(call inherit-product, system/core/trusty/trusty-storage.mk)
$(call inherit-product, system/core/trusty/trusty-base.mk)

# Trusty dump
include device/google/gs-common/trusty/trusty.mk

# Trusty unit test tool
PRODUCT_PACKAGES_DEBUG += \
   trusty-ut-ctrl \
   tipc-test \
   trusty_stats_test \

include device/google/gs101/confirmationui/confirmationui.mk

# Trusty Secure DPU Daemon
PRODUCT_PACKAGES += \
	securedpud.slider

# Trusty Metrics Daemon
PRODUCT_SOONG_NAMESPACES += \
	vendor/google/trusty/common

PRODUCT_PACKAGES += \
	trusty_metricsd

$(call soong_config_set,google_displaycolor,displaycolor_platform,gs201)
PRODUCT_PACKAGES += \
	android.hardware.composer.hwc3-service.pixel \
	libdisplaycolor

# Storage: for factory reset protection feature
PRODUCT_PROPERTY_OVERRIDES += \
	ro.frp.pst=/dev/block/by-name/frp

# System props to enable Bluetooth Quality Report (BQR) feature
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PRODUCT_PROPERTIES += \
	persist.bluetooth.bqr.event_mask?=262174 \
	persist.bluetooth.bqr.min_interval_ms=500
else
PRODUCT_PRODUCT_PROPERTIES += \
	persist.bluetooth.bqr.event_mask?=30 \
	persist.bluetooth.bqr.min_interval_ms=500
endif

#VNDK
PRODUCT_PACKAGES += \
	vndk-libs

PRODUCT_ENFORCE_RRO_TARGETS := \
	framework-res

# Dynamic Partitions
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# Use FUSE passthrough
PRODUCT_PRODUCT_PROPERTIES += \
	persist.sys.fuse.passthrough.enable=true

# Use FUSE BPF
PRODUCT_PRODUCT_PROPERTIES += \
	ro.fuse.bpf.enabled=true

# Use /product/etc/fstab.postinstall to mount system_other
PRODUCT_PRODUCT_PROPERTIES += \
	ro.postinstall.fstab.prefix=/product

PRODUCT_COPY_FILES += \
	device/google/gs201/conf/fstab.postinstall:$(TARGET_COPY_OUT_PRODUCT)/etc/fstab.postinstall

# fastbootd
PRODUCT_PACKAGES += \
	android.hardware.fastboot-service.pixel_recovery \
	fastbootd

#google iwlan
PRODUCT_PACKAGES += \
	Iwlan

#Iwlan test app for userdebug/eng builds
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += \
	IwlanTestApp
endif

PRODUCT_PACKAGES += \
	whitelist \
	libstagefright_hdcp \
	libskia_opt

#PRODUCT_PACKAGES += \
	mfc_fw.bin \
	calliope_sram.bin \
	calliope_dram.bin \
	calliope_iva.bin \
	vts.bin

ifneq ($(BOARD_WITHOUT_RADIO),true)
# This will be called only if IMSService is building with source code for dev branches.
$(call inherit-product-if-exists, vendor/samsung_slsi/telephony/$(BOARD_USES_SHARED_VENDOR_TELEPHONY)/shannon-ims/device-vendor.mk)

PRODUCT_PACKAGES += ShannonIms

#RCS Test Messaging App
PRODUCT_PACKAGES_DEBUG += \
	preinstalled-packages-product-gs201-device-debug.xml \
	TestRcsApp

PRODUCT_PACKAGES += ShannonRcs
endif

# Boot Control HAL
PRODUCT_PACKAGES += \
	android.hardware.boot@1.2-impl-gs201 \
	android.hardware.boot@1.2-service-gs201

# Exynos RIL and telephony
# Multi SIM(DSDS)
SIM_COUNT := 2
SUPPORT_MULTI_SIM := true
# Support NR
SUPPORT_NR := true
# Support 5G on both stacks
SUPPORT_NR_DS := true
# Using IRadio 2.0
USE_RADIO_HAL_2_0 := true
# Support SecureElement HAL for HIDL
USE_SE_HIDL := true
# Using Early Send Device Info
USE_EARLY_SEND_DEVICE_INFO := true

#$(call inherit-product, vendor/google_devices/telephony/common/device-vendor.mk)
#$(call inherit-product, vendor/google_devices/gs201/proprietary/device-vendor.mk)

ifneq ($(BOARD_WITHOUT_RADIO),true)
$(call inherit-product-if-exists, vendor/samsung_slsi/telephony/$(BOARD_USES_SHARED_VENDOR_TELEPHONY)/common/device-vendor.mk)

# modem_svc_sit daemon
PRODUCT_PACKAGES += modem_svc_sit

# modem logging binary/configs
PRODUCT_PACKAGES += modem_logging_control

# CP Logging properties
PRODUCT_PROPERTY_OVERRIDES += \
	ro.vendor.sys.modem.logging.loc = /data/vendor/slog \
	ro.vendor.cbd.modem_removable = "1" \
	ro.vendor.cbd.modem_type = "s5100sit" \
	persist.vendor.sys.modem.logging.br_num=5 \
	persist.vendor.sys.modem.logging.enable=true

# Enable silent CP crash handling
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.ril.crash_handling_mode=1
else
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.ril.crash_handling_mode=2
endif

# Add support dual SIM mode
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.radio.multisim_switch_support=true

PRODUCT_COPY_FILES += \
	device/google/$(TARGET_BOARD_PLATFORM)/conf/init.modem.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.modem.rc \
	device/google/$(TARGET_BOARD_PLATFORM)/conf/fstab.modem:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.modem \
	device/google/gs201/location/gps.cer:$(TARGET_COPY_OUT_VENDOR)/etc/gnss/gps.cer


include device/google/gs-common/gps/brcm/device.mk
endif


$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
#$(call inherit-product, hardware/google_devices/exynos5/exynos5.mk)
#$(call inherit-product-if-exists, hardware/google_devices/gs201/gs201.mk)
#$(call inherit-product-if-exists, vendor/google_devices/common/exynos-vendor.mk)
#$(call inherit-product-if-exists, hardware/broadcom/wlan/bcmdhd/firmware/bcm4375/device-bcm.mk)
include device/google/gs-common/sensors/sensors.mk
$(call inherit-product-if-exists, vendor/google/services/LyricCameraHAL/src/build/device-vendor.mk)
$(call inherit-product-if-exists, vendor/google/camera/devices/whi/device-vendor.mk)

PRODUCT_COPY_FILES += \
	device/google/gs201/default-permissions.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-permissions.xml \
	device/google/gs201/component-overrides.xml:$(TARGET_COPY_OUT_VENDOR)/etc/sysconfig/component-overrides.xml

# modem logging configs
PRODUCT_COPY_FILES += \
	device/google/$(TARGET_BOARD_PLATFORM)/radio/config/logging.conf:$(TARGET_COPY_OUT_VENDOR)/etc/modem/logging.conf \
	device/google/$(TARGET_BOARD_PLATFORM)/radio/config/default.cfg:$(TARGET_COPY_OUT_VENDOR)/etc/modem/default.cfg \
	device/google/$(TARGET_BOARD_PLATFORM)/radio/config/default.nprf:$(TARGET_COPY_OUT_VENDOR)/etc/modem/default.nprf \
	device/google/$(TARGET_BOARD_PLATFORM)/radio/config/default_metrics.xml:$(TARGET_COPY_OUT_VENDOR)/etc/modem/default_metrics.xml \
	device/google/$(TARGET_BOARD_PLATFORM)/radio/config/Pixel_Default_metrics.xml:$(TARGET_COPY_OUT_VENDOR)/etc/modem/Pixel_Default_metrics.xml \
	device/google/$(TARGET_BOARD_PLATFORM)/radio/config/Pixel_stability.cfg:$(TARGET_COPY_OUT_VENDOR)/etc/modem/Pixel_stability.cfg \
	device/google/$(TARGET_BOARD_PLATFORM)/radio/config/Pixel_stability.nprf:$(TARGET_COPY_OUT_VENDOR)/etc/modem/Pixel_stability.nprf \

# ARM NN files
ARM_COMPUTE_CL_ENABLE := 1

# Vibrator Diag
PRODUCT_PACKAGES_DEBUG += \
	diag-vibrator \
	diag-vibrator-cs40l25a \
	diag-vibrator-drv2624 \
	$(NULL)

PRODUCT_PACKAGES += \
	android.hardware.health-service.gs201 \
	android.hardware.health-service.gs201_recovery \

# Audio
# Audio HAL Server & Default Implementations
include device/google/gs-common/audio/hidl_gs201.mk

## AoC soong
PRODUCT_SOONG_NAMESPACES += \
        vendor/google/whitechapel/aoc

$(call soong_config_set,aoc,target_soc,$(TARGET_BOARD_PLATFORM))
$(call soong_config_set,aoc,target_product,$(TARGET_PRODUCT))

#
## Audio properties
ifneq (,$(filter $(TANGOR_PRODUCT), $(TARGET_PRODUCT)))
PRODUCT_PROPERTY_OVERRIDES += \
	ro.config.vc_call_vol_steps=7 \
	ro.config.media_vol_steps=20 \
	ro.audio.monitorRotation = true \
	ro.audio.offload_wakelock=false
else
PRODUCT_PROPERTY_OVERRIDES += \
	ro.config.vc_call_vol_steps=7 \
	ro.config.media_vol_steps=25 \
	ro.audio.monitorRotation = true \
	ro.audio.offload_wakelock=false
endif

# vndservicemanager and vndservice no longer included in API 30+, however needed by vendor code.
# See b/148807371 for reference
PRODUCT_PACKAGES += vndservicemanager
PRODUCT_PACKAGES += vndservice

## TinyTools, debug tool and cs35l41 speaker calibration tool for Audio
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += \
	tinyplay \
	tinycap \
	tinymix \
	tinypcminfo \
	tinyhostless \
	cplay \
	aoc_hal \
	aoc_tuning_inft \
	mahal_test \
	ma_aoc_tuning_test \
	crus_sp_cal
endif

PRODUCT_PACKAGES += \
	google.hardware.media.c2@1.0-service \
	libgc2_store \
	libgc2_base \
	libgc2_av1_dec \
	libbo_av1 \
	libgc2_cwl \
	libgc2_utils

## Start packet router
include device/google/gs101/telephony/pktrouter.mk

# Thermal HAL
PRODUCT_PROPERTY_OVERRIDES += persist.vendor.enable.thermal.genl=true
include hardware/google/pixel/thermal/device.mk

# EdgeTPU
include device/google/gs-common/edgetpu/edgetpu.mk
# Config variables for TPU chip on device.
$(call soong_config_set,edgetpu_config,chip,janeiro)
# TPU firmware
PRODUCT_PACKAGES += edgetpu-janeiro.fw

# Connectivity Thermal Power Manager
PRODUCT_PACKAGES += \
	ConnectivityThermalPowerManager

# A/B support
PRODUCT_PACKAGES += \
	otapreopt_script \
	cppreopts.sh \
	update_engine \
	update_engine_sideload \
	update_verifier

# pKVM
$(call inherit-product, packages/modules/Virtualization/apex/product_packages.mk)
PRODUCT_BUILD_PVMFW_IMAGE := true

# Enable to build standalone vendor_kernel_boot image.
PRODUCT_BUILD_VENDOR_KERNEL_BOOT_IMAGE := true

# Enable watchdog timeout loop breaker.
PRODUCT_PROPERTY_OVERRIDES += \
	framework_watchdog.fatal_window.second=600 \
	framework_watchdog.fatal_count=3

# Enable zygote critical window.
PRODUCT_PROPERTY_OVERRIDES += \
	zygote.critical_window.minute=10

# Suspend properties
PRODUCT_PROPERTY_OVERRIDES += \
    suspend.short_suspend_threshold_millis=2000

# Enable Incremental on the device
PRODUCT_PROPERTY_OVERRIDES += \
	ro.incremental.enable=true

# Project
include hardware/google/pixel/common/pixel-common-device.mk

# Pixel Logger
include hardware/google/pixel/PixelLogger/PixelLogger.mk

# sscoredump
include hardware/google/pixel/sscoredump/device.mk

# RadioExt Version
USES_RADIOEXT_V1_5 = true

# Wifi ext
include hardware/google/pixel/wifi_ext/device.mk

# Battery Stats Viewer
PRODUCT_PACKAGES_DEBUG += BatteryStatsViewer
PRODUCT_PACKAGES += dump_power_gs201.sh

# Install product specific framework compatibility matrix
# (TODO: b/169535506) This includes the FCM for system_ext and product partition.
# It must be split into the FCM of each partition.
DEVICE_PRODUCT_COMPATIBILITY_MATRIX_FILE += device/google/gs201/device_framework_matrix_product.xml

# Preopt SystemUI
PRODUCT_DEXPREOPT_SPEED_APPS += SystemUIGoogle  # For internal
PRODUCT_DEXPREOPT_SPEED_APPS += SystemUI  # For AOSP

# Compile SystemUI on device with `speed`.
PRODUCT_PROPERTY_OVERRIDES += \
    dalvik.vm.systemuicompilerfilter=speed

# Keymint configuration
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.device_id_attestation.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.device_id_attestation.xml \
    frameworks/native/data/etc/android.hardware.device_unique_attestation.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.device_unique_attestation.xml

# Call deleteAllKeys if vold detects a factory reset
PRODUCT_VENDOR_PROPERTIES += ro.crypto.metadata_init_delete_all_keys.enabled?=true

# Hardware Info
include hardware/google/pixel/HardwareInfo/HardwareInfo.mk

# UFS: the script is used to select the corresponding firmware to run FFU.
PRODUCT_PACKAGES += ufs_firmware_update.sh
