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

# UWB - ultra wide band
$(call inherit-product-if-exists, vendor/qorvo/uwb/uwb.mk)

LOCAL_UWB_CAL_DIR=device/google/gs101/uwb/calibration

PRODUCT_COPY_FILES += \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-jp.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-jp.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-ar.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-ar.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-am.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-am.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-az.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-az.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-by.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-by.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-id.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-id.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-kz.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-kz.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-kg.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-kg.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-np.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-np.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-pk.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-pk.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-py.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-py.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-ru.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-ru.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-sb.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-sb.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-tj.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-tj.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-tm.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-tm.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-ua.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-ua.conf \
    $(LOCAL_UWB_CAL_DIR)/UWB-calibration-uz.conf:$(TARGET_COPY_OUT_VENDOR)/etc/uwb/UWB-calibration-uz.conf \
    $(LOCAL_UWB_CAL_DIR)/init.uwb.calib.xtal.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.uwb.calib.sh \

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.uwb.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.uwb.xml
