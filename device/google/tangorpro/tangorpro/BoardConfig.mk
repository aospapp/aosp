#
# Copyright (C) 2021 The Android Open-Source Project
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
TARGET_BOARD_INFO_FILE := device/google/tangorpro/board-info.txt
TARGET_BOOTLOADER_BOARD_NAME := tangorpro

# Enable load module in parallel
BOARD_BOOTCONFIG += androidboot.load_modules_parallel=true

# The modules which need to be loaded in sequential
BOARD_KERNEL_CMDLINE += exynos_drm.load_sequential=1

ifdef PHONE_CAR_BOARD_PRODUCT
    include device/google_car/$(PHONE_CAR_BOARD_PRODUCT)/BoardConfig.mk 
else
    TARGET_SCREEN_DENSITY := 320
endif

BOARD_USES_GENERIC_AUDIO := true
USES_DEVICE_GOOGLE_TANGORPRO := true
BOARD_KERNEL_CMDLINE += swiotlb=noforce

include device/google/gs201/BoardConfig-common.mk
-include vendor/google_devices/gs201/prebuilts/BoardConfigVendor.mk
-include vendor/google_devices/tangorpro/proprietary/BoardConfigVendor.mk
include device/google/tangorpro-sepolicy/tangorpro-sepolicy.mk
include device/google/tangorpro/wifi/BoardConfig-wifi.mk
