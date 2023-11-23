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

LOCAL_WIFI_PATH := device/google/gs201/wifi/qcom

# wlan flags
BOARD_WLAN_DEVICE := qcwcn
BOARD_WLAN_CHIP := wcn6740
BOARD_WPA_SUPPLICANT_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
BOARD_HOSTAPD_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
BOARD_WPA_SUPPLICANT_DRIVER := NL80211
BOARD_HOSTAPD_DRIVER := NL80211
WPA_SUPPLICANT_VERSION := VER_0_8_X
CONFIG_IEEE80211AX := true
# BOARD_HAS_QCOM_WLAN := true
# CONFIG_ACS := true

# Wifi interface combination - {1 STA + 1 of (P2P or NAN)} or {1 AP}
WIFI_HAL_INTERFACE_COMBINATIONS := {{{STA}, 1}, {{P2P, NAN}, 1}}, {{{AP}, 1}}
WIFI_HIDL_FEATURE_AWARE := true
WIFI_FEATURE_WIFI_EXT_HAL := true
WIFI_FEATURE_IMU_DETECTION := true
WIFI_HIDL_UNIFIED_SUPPLICANT_SERVICE_RC_ENTRY := true
# Avoid Wifi reset on MAC Address change
# WIFI_AVOID_IFACE_RESET_MAC_CHANGE := true
# WIFI_FEATURE_HOSTAPD_11AX := true
WIFI_DRIVER_STATE_CTRL_PARAM := "/dev/wlan"
WIFI_DRIVER_STATE_ON := "ON"
WIFI_DRIVER_STATE_OFF := "OFF"
BOARD_HOSTAPD_CONFIG_80211W_MFP_OPTIONAL := true

# WLAN driver configuration files
PRODUCT_COPY_FILES += \
    $(LOCAL_WIFI_PATH)/wpa_supplicant_overlay.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant_overlay.conf \
    $(LOCAL_WIFI_PATH)/p2p_supplicant_overlay.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/p2p_supplicant_overlay.conf

# Add BOARD_WLAN_CHIP to soong_config
$(call soong_config_set,qcom_wifi,board_wlan_chip,wcn6740)