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

include device/linaro/dragonboard/BoardConfigCommon.mk

# Primary Arch
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a-branchprot
TARGET_CPU_VARIANT := kryo385
TARGET_CPU_ABI := arm64-v8a

# Secondary Arch
TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-2a
TARGET_2ND_CPU_VARIANT := kryo385
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi

# Board Information
TARGET_BOOTLOADER_BOARD_NAME := sm8450
TARGET_BOARD_PLATFORM := sm8450

TARGET_NO_KERNEL := false
BOARD_KERNEL_BASE := 0x80000000
BOARD_KERNEL_PAGESIZE := 4096

BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_BOOT_HEADER_VERSION := 2
BOARD_MKBOOTIMG_ARGS := --header_version $(BOARD_BOOT_HEADER_VERSION)

BOARD_KERNEL_CMDLINE := earlycon firmware_class.path=/vendor/firmware/ androidboot.hardware=sm8450
BOARD_KERNEL_CMDLINE += init=/init androidboot.boot_devices=soc@0/1d84000.ufshc printk.devkmsg=on
BOARD_KERNEL_CMDLINE += allow_mismatched_32bit_el0
BOARD_KERNEL_CMDLINE += console=ttyMSM0

# Image Configuration
BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864 #64M
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 67108864 #64M
BOARD_USERDATAIMAGE_PARTITION_SIZE := 21474836480
BOARD_FLASH_BLOCK_SIZE := 131072

# Super/Dynamic partition
BOARD_SUPER_PARTITION_SIZE := 6442450944
BOARD_DB_DYNAMIC_PARTITIONS_SIZE := 6438256640 # Reserve 4M for DAP metadata
BOARD_SUPER_PARTITION_METADATA_DEVICE := super
BOARD_SUPER_IMAGE_IN_UPDATE_PACKAGE := true
