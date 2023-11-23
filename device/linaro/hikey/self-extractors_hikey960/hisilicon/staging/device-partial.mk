# Copyright 2016 The Android Open Source Project
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

# Blobs needed for HiKey960 video decoding hardware
TARGET_HISI_CODEC_VERSION := 1

PRODUCT_SOONG_NAMESPACES += vendor/linaro/hikey960/hisilicon/proprietary

PRODUCT_PACKAGES += \
    libc_secshared \
    libhiion \
    libhilog \
    libOMX_Core \
    libOMX.hisi.vdec.core \
    libOMX.hisi.video.decoder \
    libstagefrighthw
