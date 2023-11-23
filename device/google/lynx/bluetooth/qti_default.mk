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

# Bluetooth Services sysprop for A2DP AAC VBR and A2DP offloading
PRODUCT_PRODUCT_PROPERTIES += \
	persist.bluetooth.a2dp_aac.vbr_supported=true \
	ro.bluetooth.a2dp_offload.supported=true \
	persist.bluetooth.a2dp_offload.disabled=false \
	persist.bluetooth.a2dp_offload.cap=sbc-aac-aptx-aptxhd-ldac

# Bluetooth LE Audio
PRODUCT_PRODUCT_PROPERTIES += \
	ro.bluetooth.leaudio_switcher.supported=true \
	ro.bluetooth.leaudio_offload.supported=true \
	persist.bluetooth.leaudio_offload.disabled=false \
	ro.vendor.audio_hal.ble_use_stream_id=true \
	bluetooth.profile.bap.unicast.client.enabled?=true \
	bluetooth.profile.csip.set_coordinator.enabled?=true \
	bluetooth.profile.hap.client.enabled?=true \
	bluetooth.profile.mcp.server.enabled?=true \
	bluetooth.profile.ccp.server.enabled?=true \
	bluetooth.profile.vcp.controller.enabled?=true \

# Bluetooth LE Audio CIS handover to SCO
# Set the property only if the controller doesn't support CIS and SCO
# simultaneously. More details in b/242908683.
PRODUCT_PRODUCT_PROPERTIES += \
	persist.bluetooth.leaudio.notify.idle.during.call=true

# LE Auido Offload Capabilities setting
PRODUCT_COPY_FILES += \
	device/google/lynx/bluetooth/le_audio_codec_capabilities.xml:$(TARGET_COPY_OUT_VENDOR)/etc/le_audio_codec_capabilities.xml

# Bluetooth HAL and Pixel extension
DEVICE_MANIFEST_FILE += \
	device/google/lynx/bluetooth/manifest_bluetooth.xml
DEVICE_PRODUCT_COMPATIBILITY_MATRIX_FILE += device/google/lynx/bluetooth/device_framework_matrix_product.xml

BOARD_SEPOLICY_DIRS += device/google/lynx-sepolicy/bluetooth

BOARD_HAVE_BLUETOOTH_QCOM = true
BOARD_USES_COMMON_BLUETOOTH_HAL = true
QCOM_BLUETOOTH_USING_DIAG = false
TARGET_BLUETOOTH_HCI_V1_1 = true
TARGET_BLUETOOTH_UART_DEVICE = "/dev/ttySAC18"
UART_USE_TERMIOS_AFC = true
TARGET_USE_QTI_BT_IBS = false
TARGET_USE_QTI_BT_OBS = true
TARGET_USE_QTI_BT_SAR_V1_1 = true
TARGET_USE_QTI_BT_CHANNEL_AVOIDANCE = true
TARGET_DROP_BYTES_BEFORE_SSR_DUMP = true

# IBluetoothHci @1.1 / @1.0
ifeq ($(TARGET_BLUETOOTH_HCI_V1_1),true)
   PRODUCT_PACKAGES += android.hardware.bluetooth@1.1-impl-qti
else
   PRODUCT_PACKAGES += android.hardware.bluetooth@1.0-impl-qti
endif
# IBluetoothSar @1.1 / @1.0
ifeq ($(TARGET_USE_QTI_BT_SAR_V1_1),true)
   PRODUCT_PACKAGES += hardware.google.bluetooth.sar@1.1-impl
else ifeq ($(TARGET_USE_QTI_BT_SAR),true)
   PRODUCT_PACKAGES += hardware.google.bluetooth.sar@1.0-impl
endif
PRODUCT_PACKAGES += \
	android.hardware.bluetooth@1.0-service-qti \
	hardware.google.bluetooth.bt_channel_avoidance@1.0-impl \
	android.hardware.bluetooth.prebuilt.xml \
	android.hardware.bluetooth_le.prebuilt.xml

# Bluetooth SAR Tx power caps
PRODUCT_COPY_FILES += \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_G0DZQ_EU.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_G0DZQ_EU.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_GHL1X_EU.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_GHL1X_EU.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_G0DZQ_CA.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_G0DZQ_CA.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_G0DZQ_US.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_G0DZQ_US.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_GWKK3_CA.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_GWKK3_cA.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_GWKK3_US.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_GWKK3_US.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_G0DZQ_JP.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_G0DZQ_JP.csv \
	device/google/lynx/bluetooth/bluetooth_power_limits_Lynx_G82U8_JP.csv:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_power_limits_G82U8_JP.csv

# Bluetooth SAR test tools
ifneq (,$(filter true, $(TARGET_USE_QTI_BT_SAR_V1_1) $(TARGET_USE_QTI_BT_SAR)))
   PRODUCT_PACKAGES_DEBUG += bluetooth_sar_test
endif

# Bluetooth (Vendor) SoC, BDA in device tree, and WiPower
PRODUCT_PROPERTY_OVERRIDES += \
	vendor.qcom.bluetooth.soc=hastings \
	ro.vendor.bt.bdaddr_path=/proc/device-tree/chosen/config/bt_addr \
	ro.vendor.bluetooth.emb_wp_mode=false \
	ro.vendor.bluetooth.wipower=false

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.service.bdroid.soclog=true \
	persist.vendor.service.bdroid.fwsnoop=true
else
PRODUCT_PROPERTY_OVERRIDES += \
	persist.vendor.service.bdroid.soclog=false \
	persist.vendor.service.bdroid.fwsnoop=false
endif
