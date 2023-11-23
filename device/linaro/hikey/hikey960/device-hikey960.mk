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

# Enable Virtual A/B
AB_OTA_UPDATER := true
AB_OTA_PARTITIONS += \
    product \
    system \
    system_ext \
    vendor

$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota.mk)

PRODUCT_COPY_FILES +=	$(TARGET_PREBUILT_KERNEL):kernel \
			$(TARGET_PREBUILT_DTB):hi3660-hikey960.dtb

PRODUCT_COPY_FILES +=	$(LOCAL_PATH)/fstab.hikey960:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.hikey960 \
			$(LOCAL_PATH)/fstab.hikey960:$(TARGET_COPY_OUT_RAMDISK)/fstab.hikey960 \
			device/linaro/hikey/hikey960/init.hikey960.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.hikey960.rc \
			device/linaro/hikey/init.hikey960.power.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.hikey960.power.rc \
			device/linaro/hikey/hikey960/init.hikey960.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.hikey960.usb.rc \
			device/linaro/hikey/ueventd.common.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
			device/linaro/hikey/common.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/hikey960.kl \
			frameworks/native/data/etc/android.hardware.vulkan.level-1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.level.xml \
			frameworks/native/data/etc/android.hardware.vulkan.version-1_0_3.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.version.xml \
			frameworks/native/data/etc/android.software.vulkan.deqp.level-2020-03-01.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.vulkan.deqp.level.xml

PRODUCT_BUILD_SUPER_PARTITION := true
PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_USE_DYNAMIC_PARTITION_SIZE :=true

# Copy BT firmware
PRODUCT_COPY_FILES += \
	device/linaro/hikey/bt-wifi-firmware-util/TIInit_11.8.32-pcm-960.bts:$(TARGET_COPY_OUT_VENDOR)/firmware/ti-connectivity/TIInit_11.8.32.bts

# Copy wlan firmware
PRODUCT_COPY_FILES += \
	device/linaro/hikey/bt-wifi-firmware-util/wl18xx-fw-4.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/ti-connectivity/wl18xx-fw-4.bin \
	device/linaro/hikey/bt-wifi-firmware-util/wl18xx-conf-wl1837mod.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/ti-connectivity/wl18xx-conf.bin

# Copy hifi firmware
PRODUCT_COPY_FILES += \
	device/linaro/hikey/hifi/firmware/hifi-hikey960.img:$(TARGET_COPY_OUT_VENDOR)/firmware/hifi/hifi.img

PRODUCT_PACKAGES += \
	dhifimesg


# Build HiKey960 HDMI audio HAL. Experimental only may not work. FIXME
PRODUCT_PACKAGES += audio.primary.hikey960

PRODUCT_PACKAGES += gralloc.hikey960

#binary blobs from ARM
PRODUCT_PACKAGES +=	libGLES_mali.so \
			vulkan.hikey960.so \
			libbccArm.so \
			libRSDriverArm.so \
			libmalicore.bc \
			END_USER_LICENCE_AGREEMENT.txt

ifdef MALI_RS_DRIVER_AVAILABLE
PRODUCT_COPY_FILES += \
        $(LOCAL_PATH)/../mali/bifrost/lib/libclcore.bc:vendor/lib/libclcore.bc \
        $(LOCAL_PATH)/../mali/bifrost/lib/libclcore_neon.bc:vendor/lib/libclcore_neon.bc \
        $(LOCAL_PATH)/../mali/bifrost/bin/bcc:vendor/bin/bcc \
        $(LOCAL_PATH)/../mali/bifrost/lib64/libbcc.so:vendor/lib64/libbcc.so \
        $(LOCAL_PATH)/../mali/bifrost/lib64/libclcore.bc:vendor/lib64/libclcore.bc \
        $(LOCAL_PATH)/../mali/bifrost/lib64/libLLVM.so:vendor/lib64/libLLVM.so
endif

OVERRIDE_RS_DRIVER := libRSDriverArm.so
PRODUCT_PACKAGES += android.hardware.renderscript@1.0-impl
PRODUCT_PACKAGES += vndk_package

PRODUCT_PACKAGES += power.hikey960

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += sys.usb.controller=ff100000.dwc3

PRODUCT_PACKAGES += sensors.hikey960

ifeq ($(HIKEY_USE_DRM_HWCOMPOSER), true)
  PRODUCT_PACKAGES += hwcomposer.drm_hikey960
endif

ifneq ($(TARGET_NO_RECOVERY),true)
PRODUCT_COPY_FILES += \
	device/linaro/hikey/init.recovery.common.rc:recovery/root/init.recovery.hikey960.rc
endif
