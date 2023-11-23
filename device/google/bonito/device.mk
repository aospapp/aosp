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

TARGET_CHIPSET := sdm710

PRODUCT_SOONG_NAMESPACES += \
    device/google/bonito \
    hardware/google/av \
    hardware/google/interfaces \
    hardware/google/pixel \
    hardware/qcom/sdm845 \
    vendor/google/camera \
    vendor/qcom/sdm845 \
    vendor/google/interfaces

PRODUCT_PROPERTY_OVERRIDES += \
    keyguard.no_require_sim=true

# enable cal by default on accel sensor
PRODUCT_PRODUCT_PROPERTIES += \
    persist.debug.sensors.accel_cal=1

# The default value of this variable is false and should only be set to true when
# the device allows users to retain eSIM profiles after factory reset of user data.
PRODUCT_PRODUCT_PROPERTIES += \
    masterclear.allow_retain_esim_profiles_after_fdr=true

PRODUCT_COPY_FILES += \
    device/google/bonito/default-permissions.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default-permissions/default-permissions.xml \
    frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml \
    frameworks/native/data/etc/android.software.verified_boot.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/android.software.verified_boot.xml

# Enforce privapp-permissions whitelist
PRODUCT_PROPERTY_OVERRIDES += \
    ro.control_privapp_permissions=enforce

# Enable on-access verification of priv apps. This requires fs-verity support in kernel.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.apk_verity.mode=1

PRODUCT_PACKAGES += \
    messaging

LOCAL_PATH := device/google/bonito

TARGET_PRODUCT_PROP := $(LOCAL_PATH)/product.prop

$(call inherit-product, $(LOCAL_PATH)/utils.mk)

# Installs gsi keys into ramdisk, to boot a GSI with verified boot.
$(call inherit-product, $(SRC_TARGET_DIR)/product/gsi_keys.mk)

ifeq ($(wildcard vendor/google_devices/bonito/proprietary/device-vendor-bonito.mk),)
    BUILD_WITHOUT_VENDOR := true
endif

ifeq ($(TARGET_PREBUILT_KERNEL),)
    LOCAL_KERNEL := device/google/bonito-kernel/Image.lz4
else
    LOCAL_KERNEL := $(TARGET_PREBUILT_KERNEL)
endif

PRODUCT_CHARACTERISTICS := nosdcard
PRODUCT_SHIPPING_API_LEVEL := 28

DEVICE_PACKAGE_OVERLAYS += $(LOCAL_PATH)/overlay

PRODUCT_COPY_FILES += \
    $(LOCAL_KERNEL):kernel \
    $(LOCAL_PATH)/init.recovery.hardware.rc:recovery/root/init.recovery.$(PRODUCT_PLATFORM).rc \
    $(LOCAL_PATH)/init.hardware.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.$(PRODUCT_PLATFORM).rc \
    $(LOCAL_PATH)/init.hardware.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.$(PRODUCT_PLATFORM).usb.rc \
    $(LOCAL_PATH)/ueventd.hardware.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
    $(LOCAL_PATH)/init.power.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.$(PRODUCT_PLATFORM).power.rc \
    $(LOCAL_PATH)/init.radio.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.radio.sh \
    $(LOCAL_PATH)/uinput-fpc.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/uinput-fpc.kl \
    $(LOCAL_PATH)/uinput-fpc.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/uinput-fpc.idc \
    $(LOCAL_PATH)/init.qcom.devstart.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.qcom.devstart.sh \
    $(LOCAL_PATH)/init.qcom.ipastart.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.qcom.ipastart.sh \
    $(LOCAL_PATH)/init.qcom.wlan.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.qcom.wlan.sh \
    $(LOCAL_PATH)/init.insmod.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.insmod.sh \
    $(LOCAL_PATH)/init.insmod.cfg:$(TARGET_COPY_OUT_VENDOR)/etc/init.insmod.cfg \
    $(LOCAL_PATH)/thermal-engine-$(PRODUCT_HARDWARE).conf:$(TARGET_COPY_OUT_VENDOR)/etc/thermal-engine-$(PRODUCT_HARDWARE).conf \
    $(LOCAL_PATH)/init.firstboot.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.firstboot.sh \
    $(LOCAL_PATH)/init.ramoops.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.ramoops.sh

# Edge Sense initialization script.
# TODO: b/67205273
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/init.edge_sense.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.edge_sense.sh

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
  PRODUCT_COPY_FILES += \
      $(LOCAL_PATH)/init.hardware.diag.rc.userdebug:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.$(PRODUCT_PLATFORM).diag.rc
  PRODUCT_COPY_FILES += \
      $(LOCAL_PATH)/init.hardware.mpssrfs.rc.userdebug:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.$(PRODUCT_PLATFORM).mpssrfs.rc
  PRODUCT_COPY_FILES += \
      $(LOCAL_PATH)/init.hardware.chamber.rc.userdebug:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.$(PRODUCT_PLATFORM).chamber.rc
else
  PRODUCT_COPY_FILES += \
      $(LOCAL_PATH)/init.hardware.diag.rc.user:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.$(PRODUCT_PLATFORM).diag.rc
  PRODUCT_COPY_FILES += \
      $(LOCAL_PATH)/init.hardware.mpssrfs.rc.user:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.$(PRODUCT_PLATFORM).mpssrfs.rc
endif

#per device
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/bonito/init.bonito.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.bonito.rc \
    $(LOCAL_PATH)/sargo/init.sargo.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.sargo.rc \
    $(LOCAL_PATH)/init.recovery.hardware.device.rc:recovery/root/init.recovery.bonito.rc \
    $(LOCAL_PATH)/init.recovery.hardware.device.rc:recovery/root/init.recovery.sargo.rc \

MSM_VIDC_TARGET_LIST := sdm710 # Get the color format from kernel headers
MASTER_SIDE_CP_TARGET_LIST := sdm710 # ION specific settings

# A/B support
PRODUCT_PACKAGES += \
    otapreopt_script \
    cppreopts.sh \
    update_engine \
    update_verifier

# Use Sdcardfs
PRODUCT_PRODUCT_PROPERTIES += \
    ro.sys.sdcardfs=1

PRODUCT_PACKAGES += \
    bootctrl.sdm710

PRODUCT_PROPERTY_OVERRIDES += \
    ro.cp_system_other_odex=1

# Userdata Checkpointing OTA GC
PRODUCT_PACKAGES += \
    checkpoint_gc

AB_OTA_PARTITIONS += \
    boot \
    system \
    vbmeta \
    dtbo \
    product

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_system=true \
    POSTINSTALL_PATH_system=system/bin/otapreopt_script \
    FILESYSTEM_TYPE_system=ext4 \
    POSTINSTALL_OPTIONAL_system=true

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_vendor=true \
    POSTINSTALL_PATH_vendor=bin/checkpoint_gc \
    FILESYSTEM_TYPE_vendor=ext4 \
    POSTINSTALL_OPTIONAL_vendor=true

# Enable update engine sideloading by including the static version of the
# boot_control HAL and its dependencies.
PRODUCT_STATIC_BOOT_CONTROL_HAL := \
    bootctrl.sdm710 \
    libgptutils \
    libz \
    libcutils

PRODUCT_PACKAGES += \
    update_engine_sideload \
    sg_write_buffer \
    f2fs_io

# The following modules are included in debuggable builds only.
PRODUCT_PACKAGES_DEBUG += \
    bootctl \
    update_engine_client

# Write flags to the vendor space in /misc partition.
PRODUCT_PACKAGES += \
    misc_writer

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.camera.flash-autofocus.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.flash-autofocus.xml \
    frameworks/native/data/etc/android.hardware.camera.front.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.front.xml \
    frameworks/native/data/etc/android.hardware.camera.full.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.full.xml\
    frameworks/native/data/etc/android.hardware.camera.raw.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.raw.xml\
    frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml \
    frameworks/native/data/etc/android.hardware.sensor.accelerometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.accelerometer.xml \
    frameworks/native/data/etc/android.hardware.sensor.assist.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.assist.xml \
    frameworks/native/data/etc/android.hardware.sensor.compass.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.compass.xml \
    frameworks/native/data/etc/android.hardware.sensor.gyroscope.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.gyroscope.xml \
    frameworks/native/data/etc/android.hardware.sensor.light.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.light.xml \
    frameworks/native/data/etc/android.hardware.sensor.proximity.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.proximity.xml \
    frameworks/native/data/etc/android.hardware.sensor.barometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.barometer.xml \
    frameworks/native/data/etc/android.hardware.sensor.stepcounter.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.stepcounter.xml \
    frameworks/native/data/etc/android.hardware.sensor.stepdetector.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.stepdetector.xml \
    frameworks/native/data/etc/android.hardware.sensor.hifi_sensors.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.hifi_sensors.xml \
    frameworks/native/data/etc/android.hardware.location.gps.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.location.gps.xml \
    frameworks/native/data/etc/android.hardware.telephony.gsm.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.gsm.xml \
    frameworks/native/data/etc/android.hardware.telephony.cdma.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.cdma.xml \
    frameworks/native/data/etc/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.ims.xml \
    frameworks/native/data/etc/android.hardware.touchscreen.multitouch.jazzhand.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.touchscreen.multitouch.jazzhand.xml \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
    frameworks/native/data/etc/android.hardware.wifi.aware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.aware.xml \
    frameworks/native/data/etc/android.hardware.wifi.passpoint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.passpoint.xml \
    frameworks/native/data/etc/android.hardware.wifi.rtt.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.rtt.xml \
    frameworks/native/data/etc/android.software.sip.voip.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.sip.voip.xml \
    frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml \
    frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
    frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml \
    frameworks/native/data/etc/android.hardware.nfc.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.xml \
    frameworks/native/data/etc/android.hardware.nfc.hce.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.hce.xml \
    frameworks/native/data/etc/android.hardware.nfc.hcef.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.hcef.xml \
    frameworks/native/data/etc/android.hardware.vulkan.level-1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.level.xml \
    frameworks/native/data/etc/android.hardware.vulkan.compute-0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.compute.xml \
    frameworks/native/data/etc/android.hardware.vulkan.version-1_1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.version.xml \
    frameworks/native/data/etc/android.hardware.telephony.carrierlock.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.carrierlock.xml \
    frameworks/native/data/etc/android.hardware.strongbox_keystore.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.strongbox_keystore.xml \
    frameworks/native/data/etc/android.software.ipsec_tunnels.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.ipsec_tunnels.xml \

# power HAL
PRODUCT_PACKAGES += \
    android.hardware.power@1.3-service.pixel-libperfmgr

# powerstats HAL
PRODUCT_PACKAGES += \
    android.hardware.power.stats@1.0-service.pixel

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/powerhint.json:$(TARGET_COPY_OUT_VENDOR)/etc/powerhint.json

# perfstatsd
PRODUCT_PACKAGES_DEBUG += \
    perfstatsd

# Audio fluence, ns, aec property, voice and media volume steps
PRODUCT_PROPERTY_OVERRIDES += \
    ro.vendor.audio.sdk.fluencetype=fluencepro \
    persist.audio.fluence.voicecall=true \
    persist.audio.fluence.speaker=true \
    persist.audio.fluence.voicecomm=true \
    persist.audio.fluence.voicerec=false \
    persist.audio.dualmic.config=endfire \
    persist.audio.in_mmap_delay_micros=100 \
    persist.audio.out_mmap_delay_micros=150 \
    ro.config.vc_call_vol_steps=7 \
    ro.config.media_vol_steps=25 \

# MaxxAudio effect and add rotation monitor
PRODUCT_PROPERTY_OVERRIDES += \
    ro.audio.monitorRotation=true

PRODUCT_PACKAGES += \
    libmalistener

# graphics
PRODUCT_PROPERTY_OVERRIDES += \
    ro.opengles.version=196610

PRODUCT_PROPERTY_OVERRIDES += \
    ro.vendor.display.foss=1 \
    ro.vendor.display.paneltype=2 \
    ro.vendor.display.sensortype=2 \
    vendor.display.foss.config=1 \
    vendor.display.foss.config_path=/vendor/etc/FOSSConfig.xml

# Add saturation parameters
PRODUCT_PROPERTY_OVERRIDES += \
    vendor.display.adaptive_saturation_parameter=1.1574,-0.0426,-0.0426,-0.143,1.057,-0.143,-0.0144,-0.0144,1.1856

# b/73168288
PRODUCT_PROPERTY_OVERRIDES += \
    vendor.display.disable_rotator_downscale=1

PRODUCT_PROPERTY_OVERRIDES += \
    vendor.display.disable_inline_rotator=1

# Enable camera EIS3.0
PRODUCT_PROPERTY_OVERRIDES += \
    persist.camera.is_type=5 \
    persist.camera.gzoom.at=0

# camera google face detection
PRODUCT_PROPERTY_OVERRIDES += \
    persist.camera.googfd.enable=1

# Enable logical camera as default (camera id 1)
PRODUCT_PROPERTY_OVERRIDES += \
    persist.camera.logical.default=1

# OEM Unlock reporting
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
    ro.oem_unlock_supported=1

PRODUCT_PROPERTY_OVERRIDES += \
    persist.vendor.cne.feature=1 \
    persist.vendor.data.iwlan.enable=true \
    persist.radio.RATE_ADAPT_ENABLE=1 \
    persist.radio.ROTATION_ENABLE=1 \
    persist.radio.VT_ENABLE=1 \
    persist.radio.VT_HYBRID_ENABLE=1 \
    persist.radio.reboot_on_modem_change=true \
    persist.vendor.radio.apm_sim_not_pwdn=1 \
    persist.vendor.radio.custom_ecc=1 \
    persist.vendor.radio.data_ltd_sys_ind=1 \
    persist.radio.videopause.mode=1 \
    persist.vendor.radio.multisim_switch_support=true \
    persist.vendor.radio.sib16_support=1 \
    persist.vendor.radio.data_con_rprt=true \
    persist.vendor.radio.relay_oprt_change=1 \
    persist.vendor.radio.sap_silent_pin=1 \
    persist.vendor.radio.no_wait_for_card=1 \
    persist.rcs.supported=1 \
    vendor.rild.libpath=/vendor/lib64/libril-qc-hal-qmi.so \
    ro.hardware.keystore_desede=true \
    ro.zram.mark_idle_delay_mins=60 \
    ro.zram.first_wb_delay_mins=180 \
    ro.zram.periodic_wb_delay_hours=24 \

# Disable snapshot timer
PRODUCT_PROPERTY_OVERRIDES += \
    persist.vendor.radio.snapshot_enabled=0 \
    persist.vendor.radio.snapshot_timer=0

# logical camera for dual front sensors
PRODUCT_PROPERTY_OVERRIDES += \
  persist.vendor.camera.multicam=1

# WLAN driver configuration files
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/wpa_supplicant_overlay.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant_overlay.conf \
    $(LOCAL_PATH)/p2p_supplicant_overlay.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/p2p_supplicant_overlay.conf \
    $(LOCAL_PATH)/wifi_concurrency_cfg.txt:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wifi_concurrency_cfg.txt \
    $(LOCAL_PATH)/WCNSS_qcom_cfg.ini:$(TARGET_COPY_OUT_VENDOR)/firmware/wlan/qca_cld/WCNSS_qcom_cfg.ini \

#ipacm configuration files
PRODUCT_COPY_FILES += \
    hardware/qcom/data/ipacfg-mgr/msm8998/ipacm/src/IPACM_cfg.xml:$(TARGET_COPY_OUT_VENDOR)/etc/IPACM_cfg.xml

PRODUCT_PACKAGES += \
    hwcomposer.$(TARGET_CHIPSET) \
    android.hardware.graphics.composer@2.2-service \
    gralloc.$(TARGET_CHIPSET) \
    android.hardware.graphics.mapper@2.0-impl-qti-display \
    vendor.qti.hardware.display.allocator@1.0-service

# RenderScript HAL
PRODUCT_PACKAGES += \
    android.hardware.renderscript@1.0-impl

# Health HAL
PRODUCT_PACKAGES += \
    android.hardware.health@2.0-service.bonito

# Storage health HAL
PRODUCT_PACKAGES += \
    android.hardware.health.storage@1.0-service

# Light HAL
PRODUCT_PACKAGES += \
    lights.$(TARGET_CHIPSET) \
    android.hardware.light@2.0-impl \
    android.hardware.light@2.0-service

# Memtrack HAL
PRODUCT_PACKAGES += \
    memtrack.$(TARGET_CHIPSET) \
    android.hardware.memtrack@1.0-impl \
    android.hardware.memtrack@1.0-service

# Bluetooth HAL
PRODUCT_PACKAGES += \
    android.hardware.bluetooth@1.0-impl-qti \
    android.hardware.bluetooth@1.0-service-qti

# Bluetooth SoC
PRODUCT_PROPERTY_OVERRIDES += \
    vendor.qcom.bluetooth.soc=cherokee

# Property for loading BDA from device tree
PRODUCT_PROPERTY_OVERRIDES += \
    ro.vendor.bt.bdaddr_path=/proc/device-tree/chosen/cdt/cdb2/bt_addr

# Enable Perfetto traced
PRODUCT_PROPERTY_OVERRIDES += \
    persist.traced.enable=1

# Bluetooth WiPower
PRODUCT_PROPERTY_OVERRIDES += \
    ro.vendor.bluetooth.emb_wp_mode=false \
    ro.vendor.bluetooth.wipower=false

# DRM HAL
PRODUCT_PACKAGES += \
    android.hardware.drm@1.0-impl \
    android.hardware.drm@1.0-service \
    android.hardware.drm@1.2-service.clearkey \
    android.hardware.drm@1.2-service.widevine

# NFC and Secure Element packages
PRODUCT_PACKAGES += \
    NfcNci \
    Tag \
    SecureElement \
    android.hardware.nfc@1.2-service \
    android.hardware.secure_element@1.1-service-disabled

PRODUCT_COPY_FILES += \
    device/google/bonito/nfc/libnfc-nci.conf:$(TARGET_COPY_OUT_PRODUCT)/etc/libnfc-nci.conf \
    device/google/bonito/nfc/libese-nxp.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libese-nxp.conf

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.nfc.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020A/android.hardware.nfc.uicc.xml \
    frameworks/native/data/etc/android.hardware.nfc.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020B/android.hardware.nfc.uicc.xml \
    frameworks/native/data/etc/android.hardware.nfc.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020C/android.hardware.nfc.uicc.xml \
    frameworks/native/data/etc/android.hardware.nfc.ese.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020D/android.hardware.nfc.ese.xml \
    frameworks/native/data/etc/android.hardware.nfc.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020E/android.hardware.nfc.uicc.xml \
    frameworks/native/data/etc/android.hardware.nfc.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020F/android.hardware.nfc.uicc.xml \
    frameworks/native/data/etc/android.hardware.nfc.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020G/android.hardware.nfc.uicc.xml \
    frameworks/native/data/etc/android.hardware.nfc.ese.xml:$(TARGET_COPY_OUT_VENDOR)/odm/etc/permissions/sku_G020H/android.hardware.nfc.ese.xml \

PRODUCT_PACKAGES += \
    android.hardware.usb@1.1-service.bonito

PRODUCT_PACKAGES += \
    libmm-omxcore \
    libOmxCore \
    libstagefrighthw \
    libOmxVdec \
    libOmxVdecHevc \
    libOmxVenc \
    libc2dcolorconvert

# Enable Codec 2.0
PRODUCT_PROPERTY_OVERRIDES += \
    debug.media.codec2=2 \

# Create input surface on the framework side
PRODUCT_PROPERTY_OVERRIDES += \
    debug.stagefright.c2inputsurface=-1 \

PRODUCT_PACKAGES += \
    libqcodec2 \
    vendor.qti.media.c2@1.0-service \
    media_codecs_c2.xml

PRODUCT_PACKAGES += \
    android.hardware.camera.provider@2.4-impl \
    android.hardware.camera.provider@2.4-service_64 \
    camera.device@3.2-impl \
    camera.sdm710 \
    libgooglecamerahal \
    libgoogle_camera_hal_tests \
    libqomx_core \
    libmmjpeg_interface \
    libmmcamera_interface \
    libcameradepthcalibrator

PRODUCT_PACKAGES += \
    sensors.$(PRODUCT_HARDWARE) \
    android.hardware.sensors@1.0-impl \
    android.hardware.sensors@1.0-service

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/sensors/hals.conf:vendor/etc/sensors/hals.conf

# Default permission grant exceptions
PRODUCT_COPY_FILES += \
    device/google/bonito/default-permissions.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default-permissions/default-permissions.xml

PRODUCT_PACKAGES += \
    fs_config_dirs \
    fs_config_files

# Context hub HAL
PRODUCT_PACKAGES += \
    android.hardware.contexthub@1.0-impl.generic \
    android.hardware.contexthub@1.0-service

# Boot control HAL
PRODUCT_PACKAGES += \
    android.hardware.boot@1.0-impl \
    android.hardware.boot@1.0-service \

# Vibrator HAL
PRODUCT_PACKAGES += \
    android.hardware.vibrator@1.2-service.bonito \

# Thermal HAL
PRODUCT_PACKAGES += \
    android.hardware.thermal@2.0-service.pixel

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/thermal_info_config_$(PRODUCT_HARDWARE).json:$(TARGET_COPY_OUT_VENDOR)/etc/thermal_info_config.json

#GNSS HAL
PRODUCT_PACKAGES += \
    libgps.utils \
    libgnss \
    liblocation_api \
    android.hardware.gnss@1.1-impl-qti \
    android.hardware.gnss@1.1-service-qti

ENABLE_VENDOR_RIL_SERVICE := true

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/sec_config:$(TARGET_COPY_OUT_VENDOR)/etc/sec_config


HOSTAPD := hostapd
HOSTAPD += hostapd_cli
PRODUCT_PACKAGES += $(HOSTAPD)

WPA := wpa_supplicant.conf
WPA += wpa_supplicant_wcn.conf
WPA += wpa_supplicant
PRODUCT_PACKAGES += $(WPA)

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += wpa_cli
endif

# Wifi
PRODUCT_PACKAGES += \
    android.hardware.wifi@1.0-service \
    wificond \
    libwpa_client

LIB_NL := libnl_2
PRODUCT_PACKAGES += $(LIB_NL)

# Factory OTA
PRODUCT_PACKAGES += \
    FactoryOta

# Audio effects
PRODUCT_PACKAGES += \
    libvolumelistener \
    libqcomvisualizer \
    libqcomvoiceprocessing \
    libqcomvoiceprocessingdescriptors \
    libqcompostprocbundle

PRODUCT_PACKAGES += \
    audio.primary.sdm710 \
    audio.a2dp.default \
    audio.usb.default \
    audio.r_submix.default \
    libaudio-resampler \
    audio.hearing_aid.default \
    audio.bluetooth.default

PRODUCT_PACKAGES += \
    android.hardware.audio@5.0-impl:32 \
    android.hardware.audio.effect@5.0-impl:32 \
    android.hardware.broadcastradio@1.0-impl \
    android.hardware.soundtrigger@2.2-impl \
    android.hardware.bluetooth.audio@2.0-impl \
    android.hardware.audio@2.0-service

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += \
    tinyplay \
    tinycap \
    tinymix \
    tinypcminfo \
    cplay
endif

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio_policy_configuration_a2dp_offload_disabled.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration_a2dp_offload_disabled.xml \
    $(LOCAL_PATH)/audio_policy_configuration_bluetooth_legacy_hal.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration_bluetooth_legacy_hal.xml \
    $(LOCAL_PATH)/bluetooth_hearing_aid_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_hearing_aid_audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    frameworks/av/services/audiopolicy/config/a2dp_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/a2dp_in_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_in_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/bluetooth_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/hearing_aid_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/hearing_aid_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml

# Audio XMLs
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/mixer_paths_intcodec_b4.xml:$(TARGET_COPY_OUT_VENDOR)/etc/mixer_paths_intcodec_b4.xml \
    $(LOCAL_PATH)/mixer_paths_intcodec_b4.xml:$(TARGET_COPY_OUT_VENDOR)/etc/mixer_paths_intcodec_b4dev.xml \
    $(LOCAL_PATH)/audio_platform_info_intcodec_b4.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_platform_info_intcodec_b4.xml \
    $(LOCAL_PATH)/audio_platform_info_intcodec_b4dev.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_platform_info_intcodec_b4dev.xml \
    $(LOCAL_PATH)/mixer_paths_intcodec_s4.xml:$(TARGET_COPY_OUT_VENDOR)/etc/mixer_paths_intcodec_s4.xml \
    $(LOCAL_PATH)/mixer_paths_intcodec_s4.xml:$(TARGET_COPY_OUT_VENDOR)/etc/mixer_paths_intcodec_s4dev.xml \
    $(LOCAL_PATH)/audio_platform_info_intcodec_s4.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_platform_info_intcodec_s4.xml \
    $(LOCAL_PATH)/audio_platform_info_intcodec_s4dev.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_platform_info_intcodec_s4dev.xml

# audio hal tables
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/sound_trigger_platform_info.xml:$(TARGET_COPY_OUT_VENDOR)/etc/sound_trigger_platform_info.xml \
    $(LOCAL_PATH)/sound_trigger_mixer_paths.xml:$(TARGET_COPY_OUT_VENDOR)/etc/sound_trigger_mixer_paths.xml \
    $(LOCAL_PATH)/graphite_ipc_platform_info.xml:$(TARGET_COPY_OUT_VENDOR)/etc/graphite_ipc_platform_info.xml \

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml \
    $(LOCAL_PATH)/media_codecs_performance.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_telephony.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_telephony.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_video.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_video.xml \
    $(LOCAL_PATH)/media_profiles_V1_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_V1_0.xml \
    $(LOCAL_PATH)/media_codecs_omx.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_omx.xml

# configures both aac and xaac decoders
PRODUCT_COPY_FILES += \
    device/google/bonito/media_codecs_google_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_audio.xml \
# and ensure that the xaac decoder is built
PRODUCT_PACKAGES += \
    libstagefright_soft_xaacdec.vendor

PRODUCT_PROPERTY_OVERRIDES += \
    audio.snd_card.open.retries=50

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/lowi.conf:$(TARGET_COPY_OUT_VENDOR)/etc/lowi.conf

# GPS configuration file
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/gps.conf:$(TARGET_COPY_OUT_VENDOR)/etc/gps.conf

# Vendor seccomp policy files for media components:
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/seccomp_policy/codec2.vendor.ext.policy:$(TARGET_COPY_OUT_VENDOR)/etc/seccomp_policy/codec2.vendor.ext.policy \
    $(LOCAL_PATH)/seccomp_policy/mediacodec.policy:$(TARGET_COPY_OUT_VENDOR)/etc/seccomp_policy/mediacodec.policy

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
# Subsystem ramdump
PRODUCT_PROPERTY_OVERRIDES += \
    persist.vendor.sys.ssr.enable_ramdumps=1
endif

# Subsystem silent restart
PRODUCT_PROPERTY_OVERRIDES += \
    persist.vendor.sys.ssr.restart_level=modem,slpi,adsp

# setup dalvik vm configs
$(call inherit-product, frameworks/native/build/phone-xhdpi-2048-dalvik-heap.mk)

PRODUCT_COPY_FILES += \
    device/google/bonito/fstab.hardware:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.$(PRODUCT_PLATFORM) \
    device/google/bonito/fstab.hardware:$(TARGET_COPY_OUT_RECOVERY)/root/first_stage_ramdisk/fstab.$(PRODUCT_PLATFORM) \

# Use the default charger mode images
PRODUCT_PACKAGES += \
    charger_res_images

# b/36703476
# Set default log size on userdebug/eng build to 1M
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += ro.logd.size=1M
endif

# Dumpstate HAL
PRODUCT_PACKAGES += \
    android.hardware.dumpstate@1.0-service.bonito

# Citadel
PRODUCT_PACKAGES += \
    citadeld \
    citadel_updater \
    android.hardware.authsecret@1.0-service.citadel \
    android.hardware.oemlock@1.0-service.citadel \
    android.hardware.weaver@1.0-service.citadel \
    android.hardware.keymaster@4.0-service.citadel \
    wait_for_strongbox

# Citadel debug stuff
PRODUCT_PACKAGES_DEBUG += \
    test_citadel

# Storage: for factory reset protection feature
PRODUCT_PROPERTY_OVERRIDES += \
    ro.frp.pst=/dev/block/bootdevice/by-name/frp

PRODUCT_PACKAGES += \
    vndk-sp

PRODUCT_ENFORCE_RRO_TARGETS := *

# Override heap growth limit due to high display density on device
PRODUCT_PROPERTY_OVERRIDES += \
    dalvik.vm.heapgrowthlimit=256m

PRODUCT_COPY_FILES += \
    device/google/bonito/hidl/android.hidl.base@1.0.so-32:system/lib/android.hidl.base@1.0.so \
    device/google/bonito/hidl/android.hidl.base@1.0.so-64:system/lib64/android.hidl.base@1.0.so \
    device/google/bonito/hidl/android.hidl.base@1.0.so-32:vendor/lib/android.hidl.base@1.0.so \
    device/google/bonito/hidl/android.hidl.base@1.0.so-64:vendor/lib64/android.hidl.base@1.0.so \

PRODUCT_PACKAGES += \
    ipacm

#Set default CDMA subscription to RUIM
PRODUCT_PROPERTY_OVERRIDES += \
    ro.telephony.default_cdma_sub=0

# Set display color mode to Automatic by default
PRODUCT_PROPERTY_OVERRIDES += \
    persist.sys.sf.color_saturation=1.0 \
    persist.sys.sf.native_mode=2

# Easel device feature
PRODUCT_COPY_FILES += \
    device/google/bonito/permissions/com.google.hardware.camera.easel_2018.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/com.google.hardware.camera.easel_2018.xml

# ConfirmationUI HAL
PRODUCT_PACKAGES += \
    android.hardware.confirmationui@1.0-service-bonito

# Fingerprint
PRODUCT_PACKAGES += \
    android.hardware.biometrics.fingerprint@2.1-service.fpc
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/init.fingerprint.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.fingerprint.sh \

# Reliability reporting
PRODUCT_PACKAGES += \
    pixelstats-vendor

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.fingerprint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.fingerprint.xml

# CS40L20 Haptics Waveform & Firmware
PRODUCT_COPY_FILES += \
    device/google/bonito/vibrator/cs40l20/cs40l20.wmfw:$(TARGET_COPY_OUT_VENDOR)/firmware/cs40l20.wmfw \
    device/google/bonito/vibrator/cs40l20/cs40l20.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/cs40l20.bin

PRODUCT_VENDOR_KERNEL_HEADERS := device/google/bonito/sdm710/kernel-headers

# Audio ACDB data
PRODUCT_COPY_FILES += \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Bluetooth_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Bluetooth_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/General_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/General_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Global_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Global_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Handset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Handset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Hdmi_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Hdmi_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Headset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Headset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Speaker_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Speaker_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Bluetooth_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Bluetooth_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/General_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/General_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Global_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Global_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Handset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Handset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Hdmi_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Hdmi_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Headset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Headset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Speaker_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Speaker_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Bluetooth_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/Bluetooth_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/General_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/General_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Global_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/Global_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Handset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/Handset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Hdmi_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/Hdmi_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Headset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/Headset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/Speaker_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/Speaker_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Bluetooth_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/Bluetooth_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/General_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/General_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Global_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/Global_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Handset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/Handset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Hdmi_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/Hdmi_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Headset_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/Headset_cal.acdb \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/Speaker_cal.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/Speaker_cal.acdb \
     device/google/bonito/acdbdata/adsp_avs_config.acdb:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/adsp_avs_config.acdb

# Audio ACDB workspace files for QACT
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_COPY_FILES += \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/workspaceFile.qwsp:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4-snd-card/workspaceFile.qwsp \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/workspaceFile.qwsp:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4-snd-card/workspaceFile.qwsp \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-b4-snd-card/workspaceFile.qwsp:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-b4dev-snd-card/workspaceFile.qwsp \
     device/google/bonito/acdbdata/OEM/sdm670-intcodec-s4-snd-card/workspaceFile.qwsp:$(TARGET_COPY_OUT_VENDOR)/etc/acdbdata/OEM/sdm670-intcodec-s4dev-snd-card/workspaceFile.qwsp
endif

# CS35L36 Speaker Tuning
PRODUCT_COPY_FILES += \
    device/google/bonito/audio/crus_sp_config_b4_rx.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/crus_sp_config_b4_rx.bin \
    device/google/bonito/audio/crus_sp_config_b4_tx.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/crus_sp_config_b4_tx.bin \
    device/google/bonito/audio/crus_sp_config_s4_rx.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/crus_sp_config_s4_rx.bin \
    device/google/bonito/audio/crus_sp_config_s4_tx.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/crus_sp_config_s4_tx.bin

# RT5514 SoundTrigger
PRODUCT_COPY_FILES += \
    device/google/bonito/audio/rt5514_dsp_fw1.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt5514_dsp_fw1.bin \
    device/google/bonito/audio/rt5514_dsp_fw2.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt5514_dsp_fw2.bin \
    device/google/bonito/audio/rt5514_dsp_fw3.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt5514_dsp_fw3.bin \
    device/google/bonito/audio/rt5514_dsp_fw4.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt5514_dsp_fw4.bin

# Keymaster configuration
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.device_id_attestation.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.device_id_attestation.xml

# Enable modem logging
PRODUCT_PROPERTY_OVERRIDES += \
    ro.radio.log_loc="/data/vendor/modem_dump" \
    ro.radio.log_prefix="modem_log_"

# Enable modem logging for debug
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PROPERTY_OVERRIDES += \
    persist.vendor.sys.modem.diag.mdlog=true \
    persist.vendor.sys.modem.diag.mdlog_br_num=5
else
endif

# Enable tcpdump_logger on userdebug and eng
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
    PRODUCT_PROPERTY_OVERRIDES += \
        persist.vendor.tcpdump.log.alwayson=false \
        persist.vendor.tcpdump.log.br_num=5
endif

# Preopt SystemUI
PRODUCT_DEXPREOPT_SPEED_APPS += \
    SystemUIGoogle

# Enable stats logging in LMKD
TARGET_LMKD_STATS_LOG := true
PRODUCT_PRODUCT_PROPERTIES += \
    ro.lmk.log_stats=true

# default usb oem functions
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
  PRODUCT_PROPERTY_OVERRIDES += \
      persist.vendor.usb.usbradio.config=diag
endif

#Enable QTI KEYMASTER and GATEKEEPER HIDLs
KMGK_USE_QTI_SERVICE := true

#Clear the variable
TARGET_CHIPSET := ""

# Early phase offset configuration for SurfaceFlinger
PRODUCT_PROPERTY_OVERRIDES += \
    debug.sf.early_phase_offset_ns=1500000
PRODUCT_PROPERTY_OVERRIDES += \
    debug.sf.early_app_phase_offset_ns=1500000
PRODUCT_PROPERTY_OVERRIDES += \
    debug.sf.early_gl_phase_offset_ns=3000000
PRODUCT_PROPERTY_OVERRIDES += \
    debug.sf.early_gl_app_phase_offset_ns=15000000

# Enable backpressure for GL comp
PRODUCT_PROPERTY_OVERRIDES += \
    debug.sf.enable_gl_backpressure=1

# Do not skip init trigger by default
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
    vendor.skip.init=0

# pixel atrace HAL
PRODUCT_PACKAGES += \
    android.hardware.atrace@1.0-service.pixel

# fastbootd
PRODUCT_PACKAGES += \
    android.hardware.fastboot@1.0-impl.pixel \
    fastbootd

# GTS ACSA(Agreement for Carrier Service Application) verification
PRODUCT_PRODUCT_PROPERTIES += \
    ro.com.google.acsa=true

# Increment the SVN for any official public releases
PRODUCT_PROPERTY_OVERRIDES += \
	ro.vendor.build.svn=8

# Use /product/etc/fstab.postinstall to mount system_other.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.postinstall.fstab.prefix=/product

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/fstab.postinstall:$(TARGET_COPY_OUT_PRODUCT)/etc/fstab.postinstall
