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

TARGET_BOARD_PLATFORM := gs101

ifneq (,$(filter %_64,$(TARGET_PRODUCT)))
LOCAL_64ONLY := _64
endif

AB_OTA_POSTINSTALL_CONFIG += \
	RUN_POSTINSTALL_system=true \
	POSTINSTALL_PATH_system=system/bin/otapreopt_script \
	FILESYSTEM_TYPE_system=ext4 \
POSTINSTALL_OPTIONAL_system=true

# Set Vendor SPL to match platform
VENDOR_SECURITY_PATCH = $(PLATFORM_SECURITY_PATCH)

# Set boot SPL
BOOT_SECURITY_PATCH = $(PLATFORM_SECURITY_PATCH)

PRODUCT_SOONG_NAMESPACES += \
	hardware/google/av \
	hardware/google/gchips \
	hardware/google/graphics/common \
	hardware/google/graphics/gs101 \
	hardware/google/interfaces \
	hardware/google/pixel \
	device/google/gs101 \
	vendor/google/whitechapel/tools \
	vendor/broadcom/bluetooth \
	vendor/google/camera \
	vendor/google/interfaces \
	vendor/google_devices/common/proprietary/confirmatioui_hal \
	vendor/google_nos/host/android \
	vendor/google_nos/test/system-test-harness

LOCAL_KERNEL := $(TARGET_KERNEL_DIR)/Image.lz4

# OEM Unlock reporting
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
	ro.oem_unlock_supported=1

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
include device/google/gs101/modem/userdebug.mk
endif

include device/google/gs101/modem/user.mk

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
	dev.usbsetting.embedded=on \
	ro.telephony.default_network=27 \
	persist.vendor.ril.use.iccid_to_plmn=1 \
	persist.vendor.ril.emergencynumber.mode=5
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

# Enable Early Camping
PRODUCT_PRODUCT_PROPERTIES += \
	persist.vendor.ril.camp_on_earlier=1

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

ifeq (,$(filter aosp_%,$(TARGET_PRODUCT)))
# Audio client implementation for RIL
USES_GAUDIO := true
endif

# ######################
# GRAPHICS - GPU (begin)

# Must match BOARD_USES_SWIFTSHADER in BoardConfig.mk
USE_SWIFTSHADER := false

# HWUI
TARGET_USES_VULKAN = true

PRODUCT_SOONG_NAMESPACES += \
	vendor/arm/mali/valhall \
	vendor/arm/mali/valhall/cl \
	vendor/arm/mali/valhall/libmali \
	vendor/arm/mali/valhall/cinstr/production/gpu-hwc-reader

include device/google/gs101/neuralnetwork/neuralnetwork.mk

PRODUCT_PACKAGES += \
	libGLES_mali \
	vulkan.gs101 \
	libOpenCL \
	libgpudataproducer
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
	debug.mali.disable_backend_affinity=true

ifeq ($(USE_SWIFTSHADER),true)
PRODUCT_PACKAGES += \
	libGLESv1_CM_swiftshader \
	libEGL_swiftshader \
	libGLESv2_swiftshader
endif

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml \
	frameworks/native/data/etc/android.hardware.vulkan.version-1_1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.version.xml \
	frameworks/native/data/etc/android.hardware.vulkan.level-1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.level.xml \
	frameworks/native/data/etc/android.hardware.vulkan.compute-0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.compute.xml \
	frameworks/native/data/etc/android.software.vulkan.deqp.level-2021-03-01.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.vulkan.deqp.level.xml \
	frameworks/native/data/etc/android.software.opengles.deqp.level-2021-03-01.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.opengles.deqp.level.xml

ifeq ($(USE_SWIFTSHADER),true)
PRODUCT_VENDOR_PROPERTIES += \
	ro.hardware.egl = swiftshader
else
PRODUCT_VENDOR_PROPERTIES += \
	ro.hardware.egl = mali
endif

PRODUCT_VENDOR_PROPERTIES += \
	ro.opengles.version=196610 \
	graphics.gpu.profiler.support=true \
	debug.renderengine.backend=skiaglthreaded

# GRAPHICS - GPU (end)
# ####################

# Device Manifest, Device Compatibility Matrix for Treble
DEVICE_MANIFEST_FILE := \
	device/google/gs101/manifest$(LOCAL_64ONLY).xml

ifneq (,$(filter aosp_%,$(TARGET_PRODUCT)))
DEVICE_MANIFEST_FILE += \
	device/google/gs101/manifest_media_aosp.xml

PRODUCT_COPY_FILES += \
	device/google/gs101/media_codecs_aosp_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_c2.xml
else
DEVICE_MANIFEST_FILE += \
	device/google/gs101/manifest_media.xml

PRODUCT_COPY_FILES += \
	device/google/gs101/media_codecs_bo_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_c2.xml \
	device/google/gs101/media_codecs_aosp_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_aosp_c2.xml
endif

DEVICE_MATRIX_FILE := \
	device/google/gs101/compatibility_matrix.xml

DEVICE_PACKAGE_OVERLAYS += device/google/gs101/overlay

# Enforce the Product interface
PRODUCT_PRODUCT_VNDK_VERSION := current
PRODUCT_ENFORCE_PRODUCT_PARTITION_INTERFACE := true

# Init files
PRODUCT_COPY_FILES += \
	$(LOCAL_KERNEL):kernel \
	device/google/gs101/conf/init.gs101.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.gs101.usb.rc \
	device/google/gs101/conf/ueventd.gs101.rc:$(TARGET_COPY_OUT_VENDOR)/etc/ueventd.rc

PRODUCT_COPY_FILES += \
	device/google/gs101/conf/init.gs101.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.gs101.rc

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_COPY_FILES += \
	device/google/gs101/conf/init.debug.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.debug.rc
endif

# If AoC Daemon is not present on this build, load firmware at boot via rc
ifeq ($(wildcard vendor/google/whitechapel/aoc/aocd),)
PRODUCT_COPY_FILES += \
	device/google/gs101/conf/init.aoc.nodaemon.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.aoc.rc
else
PRODUCT_COPY_FILES += \
	device/google/gs101/conf/init.aoc.daemon.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.aoc.rc
endif

# Recovery files
PRODUCT_COPY_FILES += \
	device/google/gs101/conf/init.recovery.device.rc:$(TARGET_COPY_OUT_RECOVERY)/root/init.recovery.gs101.rc

# Fstab files
PRODUCT_PACKAGES += \
	fstab.gs101 \
	fstab.gs101.vendor_ramdisk \
	fstab.gs101-fips \
	fstab.gs101-fips.vendor_ramdisk
PRODUCT_COPY_FILES += \
	device/google/gs101/conf/fstab.persist:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.persist

# Shell scripts
PRODUCT_COPY_FILES += \
	device/google/gs101/init.insmod.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.insmod.sh \
	device/google/gs101/set_usb_irq.sh:$(TARGET_COPY_OUT_VENDOR)/bin/hw/set_usb_irq.sh \
	device/google/gs101/disable_contaminant_detection.sh:$(TARGET_COPY_OUT_VENDOR)/bin/hw/disable_contaminant_detection.sh

# insmod files
PRODUCT_COPY_FILES += \
	device/google/gs101/init.insmod.gs101.cfg:$(TARGET_COPY_OUT_VENDOR)/etc/init.insmod.gs101.cfg

# For creating dtbo image
PRODUCT_HOST_PACKAGES += \
	mkdtimg

PRODUCT_PACKAGES += \
	messaging

# Contexthub HAL
PRODUCT_PACKAGES += \
	android.hardware.contexthub@1.2-service.generic

# CHRE tools
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += \
	chre_power_test_client \
	chre_test_client
endif

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.context_hub.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.context_hub.xml

# Enable the CHRE Daemon
CHRE_USF_DAEMON_ENABLED := true
PRODUCT_PACKAGES += \
	chre \
	preloaded_nanoapps.json

# Filesystem management tools
PRODUCT_PACKAGES += \
	linker.vendor_ramdisk \
	tune2fs.vendor_ramdisk \
	resize2fs.vendor_ramdisk

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

# CP Logging properties
PRODUCT_PROPERTY_OVERRIDES += \
	ro.vendor.sys.modem.logging.loc = /data/vendor/slog \
	persist.vendor.sys.silentlog.tcp = "On" \
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

# RPMB TA
PRODUCT_PACKAGES += \
	tlrpmb

# Touch firmware
#PRODUCT_COPY_FILES += \
	device/google/gs101/firmware/touch/s6sy761.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/s6sy761.fw
# Touch
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.touchscreen.multitouch.jazzhand.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.touchscreen.multitouch.jazzhand.xml

# Sensors
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.sensor.accelerometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.accelerometer.xml \
	frameworks/native/data/etc/android.hardware.sensor.barometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.barometer.xml \
	frameworks/native/data/etc/android.hardware.sensor.compass.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.compass.xml \
	frameworks/native/data/etc/android.hardware.sensor.gyroscope.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.gyroscope.xml \
	frameworks/native/data/etc/android.hardware.sensor.hifi_sensors.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.hifi_sensors.xml \
	frameworks/native/data/etc/android.hardware.sensor.light.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.light.xml\
	frameworks/native/data/etc/android.hardware.sensor.proximity.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.proximity.xml \
	frameworks/native/data/etc/android.hardware.sensor.stepcounter.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.stepcounter.xml \
	frameworks/native/data/etc/android.hardware.sensor.stepdetector.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.stepdetector.xml

# Add sensor HAL 2.1 product packages
PRODUCT_PACKAGES += android.hardware.sensors@2.1-service.multihal

# Debug property for sensor.
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += \
	vendor.debug.sensor.hal.event_logger=true
endif

# USB HAL
PRODUCT_PACKAGES += \
	android.hardware.usb@1.3-service.gs101

# MIDI feature
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.software.midi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.midi.xml

# default usb debug functions
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.usb.usbradio.config=dm
endif

# Power HAL
PRODUCT_COPY_FILES += \
	device/google/gs101/powerhint.json:$(TARGET_COPY_OUT_VENDOR)/etc/powerhint.json

PRODUCT_COPY_FILES += \
	device/google/gs101/task_profiles.json:$(TARGET_COPY_OUT_VENDOR)/etc/task_profiles.json

PRODUCT_COPY_FILES += \
	device/google/gs101/powerhint_a0.json:$(TARGET_COPY_OUT_VENDOR)/etc/powerhint_a0.json

PRODUCT_COPY_FILES += \
	device/google/gs101/powerhint_a1.json:$(TARGET_COPY_OUT_VENDOR)/etc/powerhint_a1.json
-include hardware/google/pixel/power-libperfmgr/aidl/device.mk

# IRQ rebalancing.
include hardware/google/pixel/rebalance_interrupts/rebalance_interrupts.mk

# PowerStats HAL
PRODUCT_PACKAGES += \
	android.hardware.power.stats-service.pixel

# dumpstate HAL
PRODUCT_PACKAGES += \
	android.hardware.dumpstate@1.1-service.gs101

# AoC support
PRODUCT_PACKAGES += \
	aocd

# AoC debug support
PRODUCT_PACKAGES_DEBUG += \
	aocdump \
	aocutil \
	aoc_audio_cfg \
	vp_util

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
	device/google/gs101/firmware/calliope_dram.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_dram.bin \
	device/google/gs101/firmware/calliope_sram.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_sram.bin \
	device/google/gs101/firmware/calliope_dram_2.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_dram_2.bin \
	device/google/gs101/firmware/calliope_sram_2.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope_sram_2.bin \
	device/google/gs101/firmware/calliope2.dt:$(TARGET_COPY_OUT_VENDOR)/firmware/calliope2.dt \

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

# for now include gralloc here. should come from hardware/google_devices/exynos5
PRODUCT_PACKAGES += \
	android.hardware.graphics.mapper@4.0-impl \
	android.hardware.graphics.allocator@4.0-service \
	android.hardware.graphics.allocator@4.0-impl

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
PRODUCT_PACKAGES += \
	android.hardware.drm@1.4-service.clearkey \
	android.hardware.drm@1.4-service.widevine \
	liboemcrypto \

SOONG_CONFIG_NAMESPACES += google3a_config
SOONG_CONFIG_google3a_config += \
	soc \
	gcam_awb \
	ghawb_truetone \
	target_device

SOONG_CONFIG_google3a_config_soc := gs101
SOONG_CONFIG_google3a_config_gcam_awb := true
SOONG_CONFIG_google3a_config_ghawb_truetone := true

# Determine if Lyric is in the tree, and only have GCH build against it
# if it is. Cases when Lyric isn't going to be in the tree:
#    - Non-pixel gs101 devices that exclude vendor/google/services/LyricCameraHAL/src (none as of now)
#    - master-without-vendor and other types of AOSP builds (those won't built GCH either, but need this to actually start building)
#
# Builds that will have it are
#    - Regular gs101 builds
#    - PDK gs101 builds because they still have vendor/google/services/LyricCameraHAL/src

ifneq ($(wildcard vendor/google/services/LyricCameraHAL/src),)
SOONG_CONFIG_NAMESPACES += lyric
SOONG_CONFIG_lyric += \
	use_lyric_camera_hal \
	tuning_product

SOONG_CONFIG_lyric_soc := gs101
SOONG_CONFIG_lyric_use_lyric_camera_hal := true
# SOONG_CONFIG_lyric_tuning_product is set in device-specific makefiles,
# such as device/google/raviole/device-oriole.mk

# Camera HAL library selection
SOONG_CONFIG_NAMESPACES += gch
SOONG_CONFIG_gch += hwl_library
SOONG_CONFIG_gch_hwl_library := lyric
endif

# WiFi
PRODUCT_PACKAGES += \
	android.hardware.wifi@1.0-service \
	wificond \
	libwpa_client \
	WifiOverlay \

# Connectivity
PRODUCT_PACKAGES += \
        ConnectivityOverlay

PRODUCT_PACKAGES_DEBUG += \
	sg_write_buffer \
	f2fs_io \
	check_f2fs \
	f2fsstat \
	f2fs.fibmap \
	dump.f2fs

# Storage health HAL
PRODUCT_PACKAGES += \
	android.hardware.health.storage-service.default

# storage pixelstats
-include hardware/google/pixel/pixelstats/device.mk

# Enable project quotas and casefolding for emulated storage without sdcardfs
$(call inherit-product, $(SRC_TARGET_DIR)/product/emulated_storage.mk)

$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/compression.mk)
# Enforce generic ramdisk allow list
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)

# Titan-M
ifeq (,$(filter true, $(BOARD_WITHOUT_DTLS)))
include hardware/google/pixel/dauntless/dauntless.mk
endif

# Copy Camera HFD Setfiles
#PRODUCT_COPY_FILES += \
	device/google/gs101/firmware/camera/libhfd/default_configuration.hfd.cfg.json:$(TARGET_COPY_OUT_VENDOR)/firmware/default_configuration.hfd.cfg.json \
	device/google/gs101/firmware/camera/libhfd/pp_cfg.json:$(TARGET_COPY_OUT_VENDOR)/firmware/pp_cfg.json \
	device/google/gs101/firmware/camera/libhfd/tracker_cfg.json:$(TARGET_COPY_OUT_VENDOR)/firmware/tracker_cfg.json \
	device/google/gs101/firmware/camera/libhfd/WithLightFixNoBN.SDNNmodel:$(TARGET_COPY_OUT_VENDOR)/firmware/WithLightFixNoBN.SDNNmodel

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
	frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
	frameworks/native/data/etc/android.hardware.wifi.aware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.aware.xml \
	frameworks/native/data/etc/android.hardware.wifi.passpoint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.passpoint.xml \
	frameworks/native/data/etc/android.hardware.wifi.rtt.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.rtt.xml \
	frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
	frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.camera.flash-autofocus.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.flash-autofocus.xml \
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
	frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
	frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml \

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.audio.low_latency.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.low_latency.xml \
	frameworks/native/data/etc/android.hardware.audio.pro.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.pro.xml \

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.software.ipsec_tunnels.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.ipsec_tunnels.xml \

PRODUCT_PROPERTY_OVERRIDES += \
	debug.slsi_platform=1 \
	debug.hwc.winupdate=1

# hw composer HAL
PRODUCT_PACKAGES += \
	libdisplaycolor \
	hwcomposer.$(TARGET_BOARD_PLATFORM)

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += displaycolor_service
endif

PRODUCT_PROPERTY_OVERRIDES += \
	debug.sf.disable_backpressure=0 \
	debug.sf.enable_gl_backpressure=1

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.use_phase_offsets_as_durations=1
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.late.sf.duration=10500000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.late.app.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.early.sf.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.early.app.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.earlyGl.sf.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.earlyGl.app.duration=16600000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.frame_rate_multiple_threshold=120
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += debug.sf.layer_caching_active_layer_timeout_ms=1000

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
	device/google/gs101/display/display_colordata_cal0.pb:$(TARGET_COPY_OUT_VENDOR)/etc/display_colordata_cal0.pb

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

# WPA SUPPLICANT
PRODUCT_COPY_FILES += \
	device/google/gs101/wifi/p2p_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/p2p_supplicant.conf \
	device/google/gs101/wifi/wpa_supplicant_overlay.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant_overlay.conf

# WIFI COEX
PRODUCT_COPY_FILES += \
	device/google/gs101/wifi/coex_table.xml:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/coex_table.xml

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

SOONG_CONFIG_NAMESPACES += bigo
SOONG_CONFIG_bigo += soc
SOONG_CONFIG_bigo_soc := gs101

# MFC firmware
PRODUCT_COPY_FILES += \
	device/google/gs101/firmware/mfc/mfc_fw.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mfc_fw.bin

# 1. Codec 2.0
# exynos service
PRODUCT_SOONG_NAMESPACES += vendor/samsung_slsi/codec2

PRODUCT_COPY_FILES += \
	device/google/gs101/media_codecs_performance_c2.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance_c2.xml \

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
       debug.c2.use_dmabufheaps=1 \
       media.c2.dmabuf.padding=512 \
       debug.stagefright.ccodec_delayed_params=1 \
       ro.vendor.gpu.dataspace=1

# Create input surface on the framework side
PRODUCT_PROPERTY_OVERRIDES += \
	debug.stagefright.c2inputsurface=-1 \

# 2. OpenMAX IL
PRODUCT_COPY_FILES += \
	device/google/gs101/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml \
	device/google/gs101/media_codecs_performance.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance.xml
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

# GPS HAL
include device/google/gs101/gnss/device-gnss.mk

# Trusty (KM, GK, Storage)
$(call inherit-product, system/core/trusty/trusty-storage.mk)
$(call inherit-product, system/core/trusty/trusty-base.mk)

# Trusty unit test tool
PRODUCT_PACKAGES_DEBUG += trusty-ut-ctrl

include device/google/gs101/confirmationui/confirmationui.mk

include device/google/gs101/trusty_metricsd/trusty_metricsd.mk

PRODUCT_PACKAGES += \
	android.hardware.graphics.composer@2.4-impl \
	android.hardware.graphics.composer@2.4-service

# Storage: for factory reset protection feature
PRODUCT_PROPERTY_OVERRIDES += \
	ro.frp.pst=/dev/block/by-name/frp

# Bluetooth HAL
PRODUCT_PACKAGES += \
	android.hardware.bluetooth@1.1-service.bcmbtlinux \
	bt_vendor.conf
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
	frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml

# System props to enable Bluetooth Quality Report (BQR) feature
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PRODUCT_PROPERTIES += \
	persist.bluetooth.bqr.event_mask=262174 \
	persist.bluetooth.bqr.min_interval_ms=500
else
PRODUCT_PRODUCT_PROPERTIES += \
	persist.bluetooth.bqr.event_mask=30 \
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

# Use /product/etc/fstab.postinstall to mount system_other
PRODUCT_PRODUCT_PROPERTIES += \
	ro.postinstall.fstab.prefix=/product

PRODUCT_COPY_FILES += \
	device/google/gs101/conf/fstab.postinstall:$(TARGET_COPY_OUT_PRODUCT)/etc/fstab.postinstall

# fastbootd
PRODUCT_PACKAGES += \
	android.hardware.fastboot@1.1-impl.pixel \
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

# This will be called only if IMSService is building with source code for dev branches.
$(call inherit-product-if-exists, vendor/samsung_slsi/telephony/shannon-ims/device-vendor.mk)

PRODUCT_PACKAGES += ShannonIms

$(call inherit-product-if-exists, vendor/samsung_slsi/telephony/shannon-iwlan/device-vendor.mk)
include device/google/gs101/telephony/pktrouter.mk

#RCS Test Messaging App
PRODUCT_PACKAGES_DEBUG += \
	TestRcsApp

PRODUCT_PACKAGES += ShannonRcs

# Boot Control HAL
PRODUCT_PACKAGES += \
	android.hardware.boot@1.2-impl-gs101 \
	android.hardware.boot@1.2-service-gs101

# Exynos RIL and telephony
# Multi SIM(DSDS)
SIM_COUNT := 2
SUPPORT_MULTI_SIM := true
# Support NR
SUPPORT_NR := true
# Using IRadio 1.6
USE_RADIO_HAL_1_6 := true

#$(call inherit-product, vendor/google_devices/telephony/common/device-vendor.mk)
#$(call inherit-product, vendor/google_devices/gs101/proprietary/device-vendor.mk)

ifneq ($(BOARD_WITHOUT_RADIO),true)
$(call inherit-product-if-exists, vendor/samsung_slsi/telephony/common/device-vendor.mk)
endif

ifeq (,$(filter %_64,$(TARGET_PRODUCT)))
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
else
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
endif
#$(call inherit-product, hardware/google_devices/exynos5/exynos5.mk)
#$(call inherit-product-if-exists, hardware/google_devices/gs101/gs101.mk)
#$(call inherit-product-if-exists, vendor/google_devices/common/exynos-vendor.mk)
#$(call inherit-product-if-exists, hardware/broadcom/wlan/bcmdhd/firmware/bcm4375/device-bcm.mk)
$(call inherit-product-if-exists, vendor/google/sensors/usf/android/usf_efw_product.mk)
$(call inherit-product-if-exists, vendor/google/services/LyricCameraHAL/src/build/device-vendor.mk)
$(call inherit-product-if-exists, vendor/google/camera/devices/whi/device-vendor.mk)

PRODUCT_COPY_FILES += \
	device/google/gs101/default-permissions.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-permissions.xml \
	device/google/gs101/component-overrides.xml:$(TARGET_COPY_OUT_VENDOR)/etc/sysconfig/component-overrides.xml \
	frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml \

# modem_svc_sit daemon
PRODUCT_PACKAGES += modem_svc_sit

# modem logging binary/configs
PRODUCT_PACKAGES += modem_logging_control

PRODUCT_COPY_FILES += \
	device/google/gs101/radio/gnss_blanking.csv:$(TARGET_COPY_OUT_VENDOR)/etc/modem/gnss_blanking.csv

# ARM NN files
ARM_COMPUTE_CL_ENABLE := 1

# Vibrator Diag
PRODUCT_PACKAGES_DEBUG += \
	diag-vibrator \
	diag-vibrator-cs40l25a \
	diag-vibrator-drv2624 \
	$(NULL)

PRODUCT_PACKAGES += \
	android.hardware.health@2.1-impl-gs101 \
	android.hardware.health@2.1-service

# Audio
# Audio HAL Server & Default Implementations
PRODUCT_PACKAGES += \
	android.hardware.audio.service \
	android.hardware.audio@7.0-impl \
	android.hardware.audio.effect@7.0-impl \
	android.hardware.bluetooth.audio@2.1-impl \
	android.hardware.soundtrigger@2.3-impl \
	vendor.google.whitechapel.audio.audioext@2.0-impl

#Audio HAL libraries
PRODUCT_PACKAGES += \
	audio.primary.$(TARGET_BOARD_PLATFORM) \
	audio.platform.aoc \
	sound_trigger.primary.$(TARGET_BOARD_PLATFORM) \
	audio_bt_aoc \
	audio_tunnel_aoc \
	aoc_aud_ext \
	libaoctuningdecoder \
	libaoc_waves \
	liboffloadeffect \
	audio_waves_aoc \
	audio_fortemedia_aoc \
	audio_bluenote_aoc \
	audio_usb_aoc \
	audio_spk_35l41 \
	audio.usb.default \
	audio.usbv2.default \
	audio.a2dp.default \
	audio.bluetooth.default \
	audio.r_submix.default \
	libamcsextfile \
	audio_amcs_ext \


#Audio Vendor libraries
PRODUCT_PACKAGES += \
	libfvsam_prm_parser \
	libmahalcontroller \
	libAlgFx_HiFi3z

# AudioHAL Configurations
PRODUCT_COPY_FILES += \
	frameworks/av/services/audiopolicy/config/a2dp_audio_policy_configuration_7_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_audio_policy_configuration_7_0.xml \
	frameworks/av/services/audiopolicy/config/a2dp_in_audio_policy_configuration_7_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_in_audio_policy_configuration_7_0.xml \
	frameworks/av/services/audiopolicy/config/bluetooth_audio_policy_configuration_7_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_audio_policy_configuration_7_0.xml \
	frameworks/av/services/audiopolicy/config/hearing_aid_audio_policy_configuration_7_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/hearing_aid_audio_policy_configuration_7_0.xml \
	frameworks/av/services/audiopolicy/config/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
	frameworks/av/services/audiopolicy/config/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml \

#Audio soong
PRODUCT_SOONG_NAMESPACES += \
	vendor/google/whitechapel/audio/hal \
	vendor/google/whitechapel/audio/interfaces

SOONG_CONFIG_NAMESPACES += aoc_audio_board
SOONG_CONFIG_aoc_audio_board += \
	platform

SOONG_CONFIG_aoc_audio_board_platform := $(TARGET_BOARD_PLATFORM)

# Audio properties
PRODUCT_PROPERTY_OVERRIDES += \
	ro.config.vc_call_vol_steps=7 \
	ro.config.media_vol_steps=25 \
	ro.audio.monitorRotation = true \
	ro.audio.offload_wakelock=false

ifeq (,$(filter aosp_%,$(TARGET_PRODUCT)))
# IAudioMetricExt HIDL
PRODUCT_PACKAGES += \
    vendor.google.audiometricext@1.0-service-vendor
endif

# vndservicemanager and vndservice no longer included in API 30+, however needed by vendor code.
# See b/148807371 for reference
PRODUCT_PACKAGES += vndservicemanager
PRODUCT_PACKAGES += vndservice

# TinyTools, debug tool and cs35l41 speaker calibration tool for Audio
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
	crus_sp_cal \
	mahal_test \
	ma_aoc_tuning_test
endif

PRODUCT_PACKAGES += \
	google.hardware.media.c2@1.0-service \
	libgc2_store \
	libgc2_base \
	libgc2_av1_dec \
	libbo_av1 \
	libgc2_cwl \
	libgc2_utils

# Thermal HAL
include hardware/google/pixel/thermal/device.mk
PRODUCT_PROPERTY_OVERRIDES += persist.vendor.enable.thermal.genl=true

include device/google/gs101/edgetpu/edgetpu.mk

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

# tetheroffload HAL
PRODUCT_PACKAGES += \
	vendor.samsung_slsi.hardware.tetheroffload@1.1-service

# pKVM
ifeq ($(TARGET_PKVM_ENABLED),true)
    $(call inherit-product, packages/modules/Virtualization/apex/product_packages.mk)
endif

# Enable watchdog timeout loop breaker.
PRODUCT_PROPERTY_OVERRIDES += \
	framework_watchdog.fatal_window.second=600 \
	framework_watchdog.fatal_count=3

# Enable zygote critical window.
PRODUCT_PROPERTY_OVERRIDES += \
	zygote.critical_window.minute=10

# Suspend properties
# (b/171433995): Enable short suspend backoffs and reduce max backoff time
PRODUCT_PROPERTY_OVERRIDES += \
    suspend.short_suspend_threshold_millis=2000 \
    suspend.short_suspend_backoff_enabled=true \
    suspend.max_sleep_time_millis=40000

# Enable Incremental on the device
PRODUCT_PROPERTY_OVERRIDES += \
	ro.incremental.enable=true

# Project
include hardware/google/pixel/common/pixel-common-device.mk

# Pixel Logger
include hardware/google/pixel/PixelLogger/PixelLogger.mk

# Telephony
include device/google/gs101/telephony/user.mk

# Battery Stats Viewer
PRODUCT_PACKAGES_DEBUG += BatteryStatsViewer

# Install product specific framework compatibility matrix
# (TODO: b/169535506) This includes the FCM for system_ext and product partition.
# It must be split into the FCM of each partition.
DEVICE_PRODUCT_COMPATIBILITY_MATRIX_FILE := device/google/gs101/device_framework_matrix_product.xml

# Preopt SystemUI
PRODUCT_DEXPREOPT_SPEED_APPS += \
    SystemUIGoogle

# Keymaster configuration
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.device_id_attestation.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.device_id_attestation.xml \
    frameworks/native/data/etc/android.hardware.device_unique_attestation.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.device_unique_attestation.xml

# Use 64-bit dex2oat for better dexopt time.
PRODUCT_PROPERTY_OVERRIDES += \
    dalvik.vm.dex2oat64.enabled=true

# Call deleteAllKeys if vold detects a factory reset
PRODUCT_VENDOR_PROPERTIES += ro.crypto.metadata_init_delete_all_keys.enabled=true
