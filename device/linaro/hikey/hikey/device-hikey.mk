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

PRODUCT_COPY_FILES +=   $(TARGET_PREBUILT_KERNEL):kernel \
                        $(TARGET_PREBUILT_DTB):hi6220-hikey.dtb \
			$(LOCAL_PATH)/$(TARGET_FSTAB):$(TARGET_COPY_OUT_VENDOR)/etc/fstab.hikey \
			device/linaro/hikey/fstab.ramdisk:$(TARGET_COPY_OUT_RAMDISK)/fstab.hikey \
			device/linaro/hikey/hikey/init.hikey.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.hikey.rc \
			device/linaro/hikey/init.hikey.power.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.hikey.power.rc \
			device/linaro/hikey/hikey/init.hikey.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.hikey.usb.rc \
			device/linaro/hikey/ueventd.common.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
			device/linaro/hikey/common.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/hikey.kl

# Copy BT firmware
PRODUCT_COPY_FILES += \
	device/linaro/hikey/bt-wifi-firmware-util/TIInit_11.8.32.bts:$(TARGET_COPY_OUT_VENDOR)/firmware/ti-connectivity/TIInit_11.8.32.bts

# Copy wlan firmware
PRODUCT_COPY_FILES += \
	device/linaro/hikey/bt-wifi-firmware-util/wl18xx-fw-4.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/ti-connectivity/wl18xx-fw-4.bin \
	device/linaro/hikey/bt-wifi-firmware-util/wl18xx-conf.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/ti-connectivity/wl18xx-conf.bin

# Build HiKey HDMI audio HAL
PRODUCT_PACKAGES += audio.primary.hikey

# Include USB speed switch App
PRODUCT_PACKAGES += UsbSpeedSwitch

# Build libion
PRODUCT_PACKAGES += libion

# Build gralloc for hikey
PRODUCT_PACKAGES += gralloc.hikey

# PowerHAL
PRODUCT_PACKAGES += power.hikey

# Sensors HAL
PRODUCT_PACKAGES += sensors.hikey

ifeq ($(HIKEY_USE_DRM_HWCOMPOSER), true)
  PRODUCT_PACKAGES += hwcomposer.drm_hikey
endif

# Include mali blobs from ARM
PRODUCT_PACKAGES += libGLES_mali.so END_USER_LICENCE_AGREEMENT.txt

ifneq ($(TARGET_NO_RECOVERY),true)
PRODUCT_COPY_FILES += \
	device/linaro/hikey/init.recovery.common.rc:recovery/root/init.recovery.hikey.rc
endif
