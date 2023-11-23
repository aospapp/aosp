#
# Copyright (C) 2018 Texas Instruments Incorporated - http://www.ti.com/
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

PRODUCT_HARDWARE := beagle_x15board

# Enable updating of APEXes
$(call inherit-product, $(SRC_TARGET_DIR)/product/updatable_apex.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/emulated_storage.mk)

PRODUCT_SOONG_NAMESPACES += \
	device/ti/beagle_x15 \
	hardware/ti/am57x

# Adjust the dalvik heap to be appropriate for a tablet.
$(call inherit-product, frameworks/native/build/tablet-7in-xhdpi-2048-dalvik-heap.mk)

PRODUCT_SHIPPING_API_LEVEL := 29
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false

# Set custom settings
DEVICE_PACKAGE_OVERLAYS := device/ti/beagle_x15/overlay
PREBUILT_DIR := device/ti/beagle_x15-kernel

# Helper variables for working with kernel files
ifneq ($(KERNELDIR),)
  KERNEL_MAJ := $(shell grep '^VERSION' $(KERNELDIR)/Makefile | cut -d " " -f 3)
  KERNEL_MIN := $(shell grep '^PATCHLEVEL' $(KERNELDIR)/Makefile | cut -d " " -f 3)
  TARGET_KERNEL_USE := $(KERNEL_MAJ).$(KERNEL_MIN)

  LOCAL_KERNEL_HOME := $(KERNELDIR)
  LOCAL_KERNEL := $(KERNELDIR)/arch/arm/boot/zImage

  # Check if kernel/omap or linux-mainline is used
  ifneq ($(wildcard $(KERNELDIR)/arch/arm/boot/dts/ti/.*),)
    DTB_DIR := $(KERNELDIR)/arch/arm/boot/dts/ti
  else
    DTB_DIR := $(KERNELDIR)/arch/arm/boot/dts
  endif
  DTBO_DIR := $(KERNELDIR)/arch/arm/boot/dts/ti
else
  TARGET_KERNEL_USE ?= 4.14
  KERNEL_MAJ := $(word 1, $(subst ., ,$(TARGET_KERNEL_USE)))
  KERNEL_MIN := $(word 2, $(subst ., ,$(TARGET_KERNEL_USE)))

  LOCAL_KERNEL_HOME ?= $(PREBUILT_DIR)/$(TARGET_KERNEL_USE)
  LOCAL_KERNEL := $(LOCAL_KERNEL_HOME)/zImage
  DTB_DIR := $(LOCAL_KERNEL_HOME)
  DTBO_DIR := $(DTB_DIR)
endif

TARGET_PREBUILT_KERNEL := $(LOCAL_KERNEL)
PRODUCT_COPY_FILES += $(LOCAL_KERNEL):kernel

# Graphics
PRODUCT_PACKAGES += \
	android.hardware.graphics.allocator@2.0-impl \
	android.hardware.graphics.allocator@2.0-service \
	android.hardware.graphics.mapper@2.0-impl \
	android.hardware.graphics.mapper@2.0-service \
	android.hardware.graphics.composer@2.1-impl \
	android.hardware.graphics.composer@2.1-service \
	android.hardware.boot@1.0-impl:64 \
	android.hardware.boot@1.0-service \
	android.hardware.fastboot@1.0 \
	android.hardware.fastboot@1.0-impl-mock \
	libdrm \
	libdrm_omap \
	gralloc.am57x \
	libEGL_POWERVR_SGX544_116 \
	libGLESv1_CM_POWERVR_SGX544_116 \
	libGLESv2_POWERVR_SGX544_116 \
	libPVRScopeServices \
	memtrack.am57x \
	pvrsrvctl \

ifeq ($(USE_TI_HWC), y)
PRODUCT_PACKAGES += hwcomposer.am57x
else
PRODUCT_PACKAGES += hwcomposer.drm_imagination
PRODUCT_PROPERTY_OVERRIDES += ro.hardware.hwcomposer=drm_imagination
endif

# Software Gatekeeper HAL
PRODUCT_PACKAGES += \
	android.hardware.gatekeeper@1.0-service.software \

#Health
PRODUCT_PACKAGES += \
	android.hardware.health@2.1-impl \
	android.hardware.health@2.1-service \

#Security
PRODUCT_PACKAGES += \
	android.hardware.keymaster@3.0-impl \
	android.hardware.keymaster@3.0-service \
	android.hardware.drm@1.0-impl \
	android.hardware.drm@1.0-service \

# Audio
PRODUCT_PACKAGES += \
	android.hardware.audio@6.0-impl \
	android.hardware.audio.service \
	android.hardware.audio.effect@6.0-impl \

# Audio policy configuration
USE_XML_AUDIO_POLICY_CONF := 1
PRODUCT_COPY_FILES += \
	device/ti/beagle_x15/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/a2dp_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/a2dp_in_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_in_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml \
	frameworks/av/services/audiopolicy/config/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \

# Memtrack
PRODUCT_PACKAGES += \
	android.hardware.memtrack@1.0-impl \
	android.hardware.memtrack@1.0-service \

PRODUCT_PROPERTY_OVERRIDES += \
	ro.opengles.version=131072 \
	ro.sf.lcd_density=160 \

# All VNDK libraries (HAL interfaces, VNDK, VNDK-SP, LL-NDK)
PRODUCT_PACKAGES += vndk_package

# USB
PRODUCT_PACKAGES += \
	android.hardware.usb@1.0-service \

PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
	frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml \

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
	persist.sys.usb.config=mtp \

ifeq ($(KERNEL_MAJ),4)
  TARGET_FSTAB := fstab.beagle_x15board_v4
else
  TARGET_FSTAB := fstab.beagle_x15board_v5
endif

PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/init.recovery.hardware.rc:recovery/root/init.recovery.$(PRODUCT_HARDWARE).rc \
	device/ti/beagle_x15/tablet_core_hardware_beagle_x15.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/tablet_core_hardware_beagle_x15.xml \
	device/ti/beagle_x15/init.beagle_x15board.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.beagle_x15board.rc \
	device/ti/beagle_x15/init.beagle_x15board.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.beagle_x15board.usb.rc \
	device/ti/beagle_x15/ueventd.beagle_x15board.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
	device/ti/beagle_x15/$(TARGET_FSTAB):$(TARGET_COPY_OUT_RAMDISK)/fstab.beagle_x15board \
	device/ti/beagle_x15/$(TARGET_FSTAB):$(TARGET_COPY_OUT_VENDOR)/etc/fstab.beagle_x15board \
	frameworks/native/data/etc/android.hardware.ethernet.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.ethernet.xml \

#FIXME: this feature should be turned off as soon as google start checking for WIFI support before wifi calls
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \

# Static modprobe for recovery image
PRODUCT_PACKAGES += \
	toybox_static \

PRODUCT_CHARACTERISTICS := tablet,nosdcard

PRODUCT_PACKAGES += \
	toybox_vendor \
	Launcher3 \
	WallpaperPicker \
	sh_vendor \
	vintf \
	netutils-wrapper-1.0 \
	messaging \
	healthd \
	gatekeeperd \

# Boot control
PRODUCT_PACKAGES += \
	bootctrl.am57x \

PRODUCT_PACKAGES_DEBUG += \
	bootctl \
	fastbootd \
# A/B
PRODUCT_PACKAGES += \
	update_engine \
	update_verifier

PRODUCT_PACKAGES += \
	update_engine_sideload

PRODUCT_PACKAGES_DEBUG += \
	update_engine_client

PRODUCT_USE_DYNAMIC_PARTITIONS := true
