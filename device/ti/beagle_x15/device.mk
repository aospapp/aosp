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

# Adjust the dalvik heap to be appropriate for a tablet.
$(call inherit-product, frameworks/native/build/tablet-7in-xhdpi-2048-dalvik-heap.mk)

# Set custom settings
DEVICE_PACKAGE_OVERLAYS := device/ti/beagle_x15/overlay

LOCAL_KERNEL := device/ti/beagle_x15-kernel
TARGET_PREBUILT_KERNEL := $(LOCAL_KERNEL)/zImage
PRODUCT_COPY_FILES += $(TARGET_PREBUILT_KERNEL):kernel

# Graphics
PRODUCT_PACKAGES += \
	android.hardware.graphics.allocator@2.0-impl \
	android.hardware.graphics.allocator@2.0-service \
	android.hardware.graphics.mapper@2.0-impl \
	android.hardware.graphics.mapper@2.0-service \
	android.hardware.graphics.composer@2.1-impl \
	android.hardware.graphics.composer@2.1-service \
	libdrm \
	libdrm_omap \
	hwcomposer.am57x \

#Security
PRODUCT_PACKAGES += \
	android.hardware.keymaster@3.0-impl \
	android.hardware.keymaster@3.0-service \
	android.hardware.drm@1.0-impl \
	android.hardware.drm@1.0-service \

# Audio
PRODUCT_PACKAGES += \
	android.hardware.audio@2.0-impl \
	android.hardware.audio@2.0-service \
	android.hardware.audio.effect@2.0-impl \
	android.hardware.audio.effect@2.0-service \

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


PRODUCT_COPY_FILES += \
	device/ti/beagle_x15/tablet_core_hardware_beagle_x15.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/tablet_core_hardware_beagle_x15.xml \
	device/ti/beagle_x15/init.beagle_x15board.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.beagle_x15board.rc \
	device/ti/beagle_x15/init.beagle_x15board.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.beagle_x15board.usb.rc \
	device/ti/beagle_x15/ueventd.beagle_x15board.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
	device/ti/beagle_x15/fstab.beagle_x15board:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.beagle_x15board \
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

PRODUCT_PACKAGES += \
	bootfitimage
