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

TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_ABI2 :=
TARGET_CPU_VARIANT := generic

TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := generic

include device/generic/common/BoardConfigGkiCommon.mk

# Boot image with ramdisk and kernel
BOARD_RAMDISK_USE_LZ4 := true
BOARD_BUILD_GKI_BOOT_IMAGE_WITHOUT_RAMDISK := false

BOARD_KERNEL-4.19-GZ_BOOTIMAGE_PARTITION_SIZE := 47185920
BOARD_KERNEL-4.19-GZ-ALLSYMS_BOOTIMAGE_PARTITION_SIZE := 47185920

BOARD_KERNEL_BINARIES := \
    kernel-4.19-gz \

ifneq (,$(filter userdebug eng,$(TARGET_BUILD_VARIANT)))
BOARD_KERNEL_BINARIES += \
    kernel-4.19-gz-allsyms \

endif
