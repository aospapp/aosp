#
# Copyright 2021 The Android Open-Source Project
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

TARGET_LINUX_KERNEL_VERSION := 5.10

$(call inherit-product, device/google/gs201/factory_common.mk)
$(call inherit-product, device/google/lynx/device-lynx.mk)
include device/google/lynx/audio/lynx/factory-audio-tables.mk

PRODUCT_NAME := factory_lynx
PRODUCT_DEVICE := lynx
PRODUCT_MODEL := Factory build on Lynx
PRODUCT_BRAND := Android
PRODUCT_MANUFACTURER := Google

# default BDADDR for EVB only
PRODUCT_PROPERTY_OVERRIDES += \
	ro.vendor.bluetooth.evb_bdaddr="22:22:22:33:44:55"

# Factory binaries of camera
PRODUCT_PACKAGES += fatp_imx787_hat_tool

# Factory binaries of wifi
PRODUCT_PACKAGES += athdiag
PRODUCT_PACKAGES += libdiag
PRODUCT_PACKAGES += libtime_genoff
PRODUCT_PACKAGES += cnss_diag
