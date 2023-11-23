#
# Copyright (C) 2022 The Android Open Source Project
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

#
# TODO (b/212486689): The minimum system stuff for build pass.
#
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/runtime_libart.mk)

#
# Build GKI boot images
#
include device/generic/common/gki_common.mk

PRODUCT_COPY_FILES += \
    kernel/prebuilts/4.19/arm64/kernel-4.19:kernel-4.19 \
    kernel/prebuilts/4.19/arm64/kernel-4.19-gz:kernel-4.19-gz \
    kernel/prebuilts/4.19/arm64/kernel-4.19-lz4:kernel-4.19-lz4 \

$(call dist-for-goals,dist_files,kernel/prebuilts/4.19/arm64/prebuilt-info.txt:kernel/4.19/prebuilt-info.txt)

ifneq (,$(filter userdebug eng,$(TARGET_BUILD_VARIANT)))
PRODUCT_COPY_FILES += \
    kernel/prebuilts/4.19/arm64/kernel-4.19-allsyms:kernel-4.19-allsyms \
    kernel/prebuilts/4.19/arm64/kernel-4.19-gz-allsyms:kernel-4.19-gz-allsyms \
    kernel/prebuilts/4.19/arm64/kernel-4.19-lz4-allsyms:kernel-4.19-lz4-allsyms \

$(call dist-for-goals,dist_files,kernel/prebuilts/4.19/arm64/prebuilt-info.txt:kernel/4.19-debug/prebuilt-info.txt)

endif


PRODUCT_NAME := gki419_arm64
PRODUCT_DEVICE := gki419_arm64
PRODUCT_BRAND := Android
PRODUCT_MODEL := GKI on ARM64
