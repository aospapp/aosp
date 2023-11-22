#
# Copyright (C) 2019 The Android Open Source Project
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

# Common product definitons.

PRODUCT_BRAND := Fuchsia
PRODUCT_MODEL := Fuchsia

# Define the Fuchsia product.
PRODUCT_FUCHSIA := true

# Don't build ramdisk for Fuchsia.
PRODUCT_BUILD_RAMDISK_IMAGE := false

# default is nosdcard, S/W button enabled in resource
PRODUCT_CHARACTERISTICS := nosdcard

# Hand-picked packages.
PRODUCT_PACKAGES += \
    art-runtime \
    bouncycastle \
    conscrypt \
    core-libart \
    core-oj \
    dalvikvm \
    libart \
    libjavacore \
    libopenjdk \
    okhttp

# Fuchsia only has 64-bit support.
TARGET_SUPPORTS_32_BIT_APPS := false
TARGET_SUPPORTS_64_BIT_APPS := true
