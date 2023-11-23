#
# Copyright (C) 2020 The Android Open Source Project
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

$(call inherit-product, device/google/cuttlefish/vsoc_x86_64_only/auto/aosp_cf.mk)

# Audio HAL
TARGET_USES_CUTTLEFISH_AUDIO ?= false

# Audio Control HAL
# TODO (chenhaosjtuacm, egranata): move them to kernel command line
LOCAL_AUDIOCONTROL_PROPERTIES ?= \
    ro.vendor.audiocontrol.server.cid=3 \
    ro.vendor.audiocontrol.server.port=9410 \

# Vehicle HAL
ENABLE_VHAL_FAKE_GRPC_SERVER ?= true

TARGET_VULKAN_SUPPORT := true

include device/google/trout/aosp_trout_common.mk

DEVICE_MANIFEST_FILE += device/google/trout/trout_x86_64/manifest.xml
DEVICE_MATRIX_FILE += device/google/trout/compatibility_matrix.xml
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE := device/google/trout/framework_compatibility_matrix.xml

PRODUCT_COPY_FILES += \
    packages/services/Car/cpp/computepipe/products/init.computepipe.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/computepipe.rc

PRODUCT_NAME := aosp_trout_x86_64
PRODUCT_DEVICE := trout_x86_64
PRODUCT_MODEL := x86_64 trout
