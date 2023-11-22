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

PRODUCT_COPY_FILES  += vendor/linaro/hikey960/hisilicon/proprietary/libOMX.hisi.video.decoder.so:$(TARGET_COPY_OUT_VENDOR)/lib/libOMX.hisi.video.decoder.so \
    vendor/linaro/hikey960/hisilicon/proprietary/lib64/libOMX.hisi.video.decoder.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libOMX.hisi.video.decoder.so \
    vendor/linaro/hikey960/hisilicon/proprietary/libOMX.hisi.vdec.core.so:$(TARGET_COPY_OUT_VENDOR)/lib/libOMX.hisi.vdec.core.so \
    vendor/linaro/hikey960/hisilicon/proprietary/lib64/libOMX.hisi.vdec.core.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libOMX.hisi.vdec.core.so \
    vendor/linaro/hikey960/hisilicon/proprietary/libOMX_Core.so:$(TARGET_COPY_OUT_VENDOR)/lib/libOMX_Core.so \
    vendor/linaro/hikey960/hisilicon/proprietary/lib64/libOMX_Core.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libOMX_Core.so \
    vendor/linaro/hikey960/hisilicon/proprietary/libstagefrighthw.so:$(TARGET_COPY_OUT_VENDOR)/lib/libstagefrighthw.so \
    vendor/linaro/hikey960/hisilicon/proprietary/lib64/libstagefrighthw.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libstagefrighthw.so \
    vendor/linaro/hikey960/hisilicon/proprietary/libc_secshared.so:$(TARGET_COPY_OUT_VENDOR)/lib/libc_secshared.so \
    vendor/linaro/hikey960/hisilicon/proprietary/lib64/libc_secshared.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libc_secshared.so \
    vendor/linaro/hikey960/hisilicon/proprietary/lib64/libhilog.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libhilog.so \
    vendor/linaro/hikey960/hisilicon/proprietary/libhilog.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhilog.so \
    vendor/linaro/hikey960/hisilicon/proprietary/lib64/libhiion.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libhiion.so \
    vendor/linaro/hikey960/hisilicon/proprietary/libhiion.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhiion.so

