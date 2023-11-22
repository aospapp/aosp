#
# Copyright (C) 2014 The Android Open Source Project
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

# This file contains the definitions needed for a _really_ minimal system
# image to be run under emulation under upstream QEMU (www.qemu.org), once
# it supports a few Android virtual devices. Note that this is _not_ the
# same as running under the Android emulator.

# This should only contain what's necessary to boot the system, support
# ADB, and allow running command-line executable compiled against the
# following NDK libraries: libc, libm, libstdc++, libdl, liblog

# Host modules
PRODUCT_PACKAGES += \
    adb \

# Device modules
PRODUCT_PACKAGES += \
    adbd \
    bootanimation \
    debuggerd \
    debuggerd64 \
    dumpstate \
    dumpsys \
    e2fsck \
    gzip \
    healthd \
    init \
    init.environ.rc \
    init.rc \
    libbinder \
    libc \
    libctest \
    libcutils \
    libdl \
    libhardware \
    libhardware_legacy \
    liblog \
    libm \
    libstdc++ \
    libsysutils \
    libutils \
    linker \
    linker64 \
    logcat \
    logd \
    logwrapper \
    mkshrc \
    qemu-props \
    reboot \
    service \
    servicemanager \
    hwservicemanager \
    vndservice \
    vndservicemanager \
    sh \
    toolbox \
    toybox \
    vold

# SELinux packages are added as dependencies of the selinux_policy
# phony package.
PRODUCT_PACKAGES += \
    selinux_policy \

# SELinux packages
#PRODUCT_PACKAGES += \
#    sepolicy \
#    file_contexts \
#    seapp_contexts \
#    property_contexts \
#    mac_permissions.xml \

PRODUCT_COPY_FILES += \
    system/core/rootdir/init.usb.rc:root/init.usb.rc \
    system/core/rootdir/init.usb.configfs.rc:root/init.usb.configfs.rc \
    system/core/rootdir/ueventd.rc:root/ueventd.rc \
    system/core/rootdir/etc/hosts:system/etc/hosts \

PRODUCT_FULL_TREBLE_OVERRIDE := true

PRODUCT_COPY_FILES += \
    device/generic/qemu/fstab.ranchu:root/fstab.ranchu \
    device/generic/qemu/init.ranchu.rc:root/init.ranchu.rc \
    device/generic/qemu/ueventd.ranchu.rc:root/ueventd.ranchu.rc \

PRODUCT_COPY_FILES += \
    device/generic/goldfish/data/etc/config.ini:config.ini \
    device/generic/qemu/advancedFeatures.ini:advancedFeatures.ini \

#$(call inherit-product, $(SRC_TARGET_DIR)/product/core_tiny.mk)
ifeq ($(TARGET_CORE_JARS),)
$(error TARGET_CORE_JARS is empty; cannot initialize PRODUCT_BOOT_JARS variable)
endif

# The order matters
PRODUCT_BOOT_JARS := \
    $(TARGET_CORE_JARS) \



# The set of packages we want to force 'speed' compilation on.
PRODUCT_DEXPREOPT_SPEED_APPS := \

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
    ro.zygote=zygote32
PRODUCT_COPY_FILES += \
    system/core/rootdir/init.zygote32.rc:root/init.zygote32.rc

PRODUCT_PROPERTY_OVERRIDES += \
    ro.carrier=unknown

$(call inherit-product, $(SRC_TARGET_DIR)/product/runtime_libart.mk)

