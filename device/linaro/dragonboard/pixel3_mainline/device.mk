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

# copied from crosshatch
# setup dalvik vm configs
$(call inherit-product, frameworks/native/build/phone-xhdpi-2048-dalvik-heap.mk)

PRODUCT_COPY_FILES := \
    device/linaro/dragonboard/fstab.common:$(TARGET_COPY_OUT_RAMDISK)/fstab.pixel3_mainline \
    device/linaro/dragonboard/fstab.common:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.pixel3_mainline \
    device/linaro/dragonboard/init.common.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.pixel3_mainline.rc \
    device/linaro/dragonboard/init.common.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.pixel3_mainline.usb.rc \
    device/linaro/dragonboard/common.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/pixel3_mainline.kl

ifneq (,$(wildcard $(PIXEL3_KERNEL_DIR)/Image.gz-dtb))
    PRODUCT_COPY_FILES += $(PIXEL3_KERNEL_DIR)/Image.gz-dtb:kernel
    PIXEL3_KERNEL_FOUND := true
else
    PIXEL3_KERNEL_FOUND := false
endif

# Build generic Audio HAL
PRODUCT_PACKAGES := audio.primary.pixel3_mainline

# Copy firmware files
$(call inherit-product-if-exists, $(LOCAL_PATH)/firmware/device.mk)
