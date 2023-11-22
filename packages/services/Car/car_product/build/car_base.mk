#
# Copyright (C) 2016 The Android Open-Source Project
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

# Base platform for car builds
# car packages should be added to car.mk instead of here

PRODUCT_PACKAGE_OVERLAYS += packages/services/Car/car_product/overlay

PRODUCT_PACKAGES += \
    Home \
    BasicDreams \
    CaptivePortalLogin \
    CertInstaller \
    DocumentsUI \
    DownloadProviderUi \
    FusedLocation \
    InputDevices \
    KeyChain \
    Keyguard \
    LatinIME \
    Launcher2 \
    ManagedProvisioning \
    PacProcessor \
    PrintSpooler \
    ProxyHandler \
    Settings \
    SharedStorageBackup \
    VpnDialogs \
    MmsService \
    ExternalStorageProvider \
    atrace \
    cameraserver \
    libandroidfw \
    libaudioutils \
    libmdnssd \
    libnfc_ndef \
    libpowermanager \
    libspeexresampler \
    libvariablespeed \
    libwebrtc_audio_preprocessing \
    wifi-service \
    A2dpSinkService \
    PackageInstaller \
    car-bugreportd \

# EVS resources
PRODUCT_PACKAGES += android.automotive.evs.manager@1.0
# The following packages, or their vendor specific equivalents should be include in the device.mk
#PRODUCT_PACKAGES += evs_app
#PRODUCT_PACKAGES += evs_app_default_resources
#PRODUCT_PACKAGES += android.hardware.automotive.evs@1.0-service

# Device running Android is a car
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.type.automotive.xml:system/etc/permissions/android.hardware.type.automotive.xml

# Default permission grant exceptions
PRODUCT_COPY_FILES += \
    packages/services/Car/car_product/build/default-car-permissions.xml:system/etc/default-permissions/default-car-permissions.xml

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_minimal.mk)

