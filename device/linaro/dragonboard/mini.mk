#
# Copyright (C) 2022 The Android Open-Source Project
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

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_no_zygote.mk)
$(call inherit-product, device/generic/goldfish/minimal_system.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/runtime_libart.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/updatable_apex.mk)

PRODUCT_PACKAGES += \
    android.hardware.keymaster@4.1-service \
    android.hidl.allocator@1.0-service \
    android.system.suspend@1.0-service \
    com.android.i18n \
    com.android.runtime \
    keystore2 \
    init_vendor \
    libstatshidl \
    mediaserver \
    selinux_policy_nonsystem \
    system_compatibility_matrix.xml \
    system_manifest.xml \
    tune2fs \
    vdc \
    vendor_compatibility_matrix.xml \
    vendor_manifest.xml \

# Disable vintf manifest checking. The mini targets do not have the usual
# set of hardware interfaces, some of which are required by the vintf
# compatibility matrix.
PRODUCT_ENFORCE_VINTF_MANIFEST_OVERRIDE := false
DEVICE_MANIFEST_FILE := device/linaro/dragonboard/mini-manifest.xml
