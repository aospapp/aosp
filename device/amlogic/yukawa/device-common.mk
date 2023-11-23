PRODUCT_SOONG_NAMESPACES += device/amlogic/yukawa

ifeq ($(TARGET_PREBUILT_KERNEL),)
LOCAL_KERNEL := device/amlogic/yukawa-kernel/Image.lz4-$(TARGET_KERNEL_USE)
else
LOCAL_KERNEL := $(TARGET_PREBUILT_KERNEL)
endif

PRODUCT_COPY_FILES +=  $(LOCAL_KERNEL):kernel

# Build and run only ART
PRODUCT_RUNTIMES := runtime_libart_default

# Enable updating of APEXes
$(call inherit-product, $(SRC_TARGET_DIR)/product/updatable_apex.mk)

# Setup TV Build
USE_OEM_TV_APP := true
$(call inherit-product, device/google/atv/products/atv_base.mk)
PRODUCT_CHARACTERISTICS := tv
PRODUCT_AAPT_PREF_CONFIG := tvdpi
PRODUCT_IS_ATV := true
DEVICE_PACKAGE_OVERLAYS := device/amlogic/yukawa/overlay
DEVICE_PACKAGE_OVERLAYS += device/google/atv/overlay

PRODUCT_PACKAGES += llkd

# All VNDK libraries (HAL interfaces, VNDK, VNDK-SP, LL-NDK)
PRODUCT_PACKAGES += vndk_package

PRODUCT_PACKAGES += \
    android.hardware.health@2.0-service.yukawa \
    android.hardware.health@2.0-service

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/fstab.yukawa:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.yukawa \
    $(LOCAL_PATH)/init.yukawa.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.yukawa.rc \
    $(LOCAL_PATH)/init.yukawa.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.yukawa.usb.rc \
    $(LOCAL_PATH)/ueventd.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
    $(LOCAL_PATH)/wpa_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant.conf \
    $(LOCAL_PATH)/wpa_supplicant_overlay.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant_overlay.conf \
    $(LOCAL_PATH)/p2p_supplicant_overlay.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/p2p_supplicant_overlay.conf \
    $(LOCAL_PATH)/bt-wifi-firmware/BCM.hcd:$(TARGET_COPY_OUT_VENDOR)/firmware/brcm/BCM.hcd \
    $(LOCAL_PATH)/bt-wifi-firmware/fw_bcm4359c0_ag.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/brcm/fw_bcm4359c0_ag.bin \
    $(LOCAL_PATH)/bt-wifi-firmware/nvram.txt:$(TARGET_COPY_OUT_VENDOR)/firmware/brcm/nvram.txt \

# TV Specific Packages
PRODUCT_PACKAGES += \
    LiveTv \
    google-tv-pairing-protocol \
    TvProvision \
    LeanbackSampleApp \
    TvSampleLeanbackLauncher \
    tv_input.default \
    com.android.media.tv.remoteprovider \
    InputDevices

PRODUCT_PACKAGES += \
    LeanbackIME

ifeq (,$(filter $(TARGET_PRODUCT),yukawa_gms yukawa_sei510_gms))
PRODUCT_PACKAGES += \
    TVLauncherNoGms \
    TVRecommendationsNoGms
endif

PRODUCT_PROPERTY_OVERRIDES += ro.sf.lcd_density=320

PRODUCT_PACKAGES +=  libGLES_mali
PRODUCT_PACKAGES +=  libGLES_android

# Vulkan
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.vulkan.version-1_1.xml:vendor/etc/permissions/android.hardware.vulkan.version.xml \
    frameworks/native/data/etc/android.hardware.vulkan.compute-0.xml:vendor/etc/permissions/android.hardware.vulkan.compute.xml \
    frameworks/native/data/etc/android.hardware.vulkan.level-1.xml:vendor/etc/permissions/android.hardware.vulkan.level.xml

PRODUCT_PACKAGES +=  vulkan.yukawa.so 

# Bluetooth
PRODUCT_PACKAGES += android.hardware.bluetooth@1.1-service.btlinux

# Wifi
PRODUCT_PACKAGES += libwpa_client wpa_supplicant hostapd wificond wifilogd wpa_cli
PRODUCT_PROPERTY_OVERRIDES += wifi.interface=wlan0 \
                              wifi.supplicant_scan_interval=15

# Build default bluetooth a2dp and usb audio HALs
PRODUCT_PACKAGES += \
    audio.usb.default \
    audio.primary.yukawa \
    audio.r_submix.default \
    audio.bluetooth.default \
    audio.hearing_aid.default \
    audio.a2dp.default \
    tinyplay \
    tinycap \
    tinymix \
    tinypcminfo \
    cplay

# Video
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/video_firmware/g12a_h264.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/g12a_h264.bin \
    $(LOCAL_PATH)/video_firmware/g12a_hevc_mmu.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/g12a_hevc_mmu.bin \
    $(LOCAL_PATH)/video_firmware/g12a_vp9.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/g12a_vp9.bin \
    $(LOCAL_PATH)/video_firmware/gxl_mpeg4_5.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/gxl_mpeg4_5.bin \
    $(LOCAL_PATH)/video_firmware/gxl_mpeg12.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/gxl_mpeg12.bin \
    $(LOCAL_PATH)/video_firmware/gxl_mjpeg.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/gxl_mjpeg.bin \
    $(LOCAL_PATH)/video_firmware/sm1_hevc_mmu.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/sm1_hevc_mmu.bin \
    $(LOCAL_PATH)/video_firmware/sm1_vp9_mmu.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/meson/vdec/sm1_vp9_mmu.bin

PRODUCT_PACKAGES += \
    android.hardware.audio@2.0-service \
    android.hardware.audio@6.0-impl \
    android.hardware.audio.effect@6.0-impl \
    android.hardware.soundtrigger@2.2-impl \

# Hardware Composer HAL
#
PRODUCT_PACKAGES += \
    hwcomposer.drm_meson \
    android.hardware.drm@1.0-impl \
    android.hardware.drm@1.0-service \

# CEC
PRODUCT_PACKAGES += \
    android.hardware.tv.cec@1.0-impl \
    android.hardware.tv.cec@1.0-service \
    hdmi_cec.yukawa

PRODUCT_PROPERTY_OVERRIDES += ro.hdmi.device_type=4

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/Generic.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/Generic.kl \
    frameworks/native/data/etc/android.hardware.hdmi.cec.xml:system/etc/permissions/android.hardware.hdmi.cec.xml

# Memtrack
PRODUCT_PACKAGES += memtrack.default \
    android.hardware.memtrack@1.0-service \
    android.hardware.memtrack@1.0-impl

PRODUCT_PACKAGES += \
    gralloc.yukawa \
    android.hardware.graphics.composer@2.1-impl \
    android.hardware.graphics.composer@2.1-service \
    android.hardware.graphics.allocator@2.0-service \
    android.hardware.graphics.allocator@2.0-impl \
    android.hardware.graphics.mapper@2.0-impl

# PowerHAL
PRODUCT_PACKAGES += \
    power.default \
    android.hardware.power@1.0-impl \
    android.hardware.power@1.0-service

# ThermalHAL
PRODUCT_PACKAGES += \
    thermal.default \
    android.hardware.thermal@1.0-impl \
    android.hardware.thermal@1.0-service

# Software Gatekeeper HAL
PRODUCT_PACKAGES += \
    android.hardware.gatekeeper@1.0-service.software

PRODUCT_PACKAGES += \
    android.hardware.keymaster@3.0-impl \
    android.hardware.keymaster@3.0-service

# USB
PRODUCT_PACKAGES += \
    android.hardware.usb@1.1-service

PRODUCT_COPY_FILES +=  \
    frameworks/native/data/etc/android.software.app_widgets.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.app_widgets.xml \
    frameworks/native/data/etc/android.hardware.ethernet.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.ethernet.xml \
    frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml \
    frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
    frameworks/native/data/etc/android.software.device_admin.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.device_admin.xml \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
    frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml \
    frameworks/native/data/etc/android.software.cts.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.cts.xml \
    frameworks/native/data/etc/android.software.backup.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.backup.xml

# audio policy configuration
USE_XML_AUDIO_POLICY_CONF := 1
PRODUCT_COPY_FILES += \
    device/amlogic/yukawa/audio/mixer_paths.xml:$(TARGET_COPY_OUT_VENDOR)/etc/mixer_paths.xml \
    device/amlogic/yukawa/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/a2dp_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml \
    frameworks/av/services/audiopolicy/config/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    frameworks/av/media/libeffects/data/audio_effects.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_effects.xml 

# Copy media codecs config file
PRODUCT_COPY_FILES += \
    device/amlogic/yukawa/media_xml/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_audio.xml

# Enable AVB
BOARD_AVB_ENABLE := false
BOARD_AVB_ALGORITHM := SHA256_RSA2048
BOARD_AVB_ROLLBACK_INDEX := 0

# Enable BT Pairing with button BTN_0 (key 256)
PRODUCT_PACKAGES += YukawaService YukawaAndroidOverlay
PRODUCT_COPY_FILES += \
    device/amlogic/yukawa/Vendor_0001_Product_0001.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/Vendor_0001_Product_0001.kl
