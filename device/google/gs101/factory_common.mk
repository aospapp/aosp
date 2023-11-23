#
# Copyright 2020 The Android Open-Source Project
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

$(call inherit-product, device/google/gs101/aosp_common.mk)
$(call inherit-product-if-exists, vendor/google_devices/gs101/factory/pixel/device-factory.mk)

PRODUCT_PROPERTY_OVERRIDES += service.adb.root=1 \
                              ro.vendor.factory=1 \
                              ro.adb.secure=0

# Factory Libraries of Audio
PRODUCT_PACKAGES += audioroute libaudioroutelite

# Enable fatp by default for factory builds
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    persist.vendor.camera.fatp.enable=1

# Disable camera related features for factory builds
PRODUCT_PROPERTY_OVERRIDES += \
    persist.vendor.camera.partial_result_count=1 \
    vendor.camera.debug.bypass_face_ssd_processor=1 \
    vendor.camera.debug.enable_face_detection=0 \
    vendor.camera.debug.force_dpm_on=0 \
    vendor.camera.debug.force_eis_on=0 \
    vendor.camera.debug.force_eaf_on=0 \
    vendor.camera.debug.force_local_tone_mapping_on=0 \
    vendor.camera.debug.force_mesh_warp_on=0 \
    vendor.camera.debug.force_rectiface_node_on=0 \
    vendor.camera.debug.force_steadiface_on=0 \
    vendor.camera.debug.force_tnr_on=0 \
    vendor.camera.debug.local_tone_mapping_controller_v1.mode=0 \
    vendor.camera.debug.force_segmentation_node_on=0 \
    vendor.camera.debug.enable_saliency=0

# Disable ScreenDecorations for factory builds
PRODUCT_PROPERTY_OVERRIDES += \
    debug.disable_screen_decorations=true

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.set_idle_timer_ms=80
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.set_touch_timer_ms=200
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.set_display_power_timer_ms=1000
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += ro.surface_flinger.use_content_detection_for_refresh_rate=true

# factory should always has SELinux permissive
BOARD_BOOTCONFIG += androidboot.selinux=permissive
BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive

# Disable Bluetooth as default in factory build
DEVICE_PACKAGE_OVERLAYS += device/google/gs101/overlay-factory

PRODUCT_COPY_FILES += \
    device/google/gs101/conf/init.factory.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.factory.rc

# Add factory-friendly changes
PRODUCT_PACKAGES += \
        FactoryOverlaySettings \
        FactoryOverlayLauncher3 \
        FactoryOverlayFrameworkRes

# Disable DebugFS restrictions in factory builds
PRODUCT_SET_DEBUGFS_RESTRICTIONS := false

# PixelLogger for RF testing
PRODUCT_PACKAGES_DEBUG += \
    PixelLogger

# Tool for installing Widevine keybox
PRODUCT_PACKAGES_DEBUG += \
    WvInstallKeybox
