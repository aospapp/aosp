# 1. bootimage
# 1.1 kernel and dtb
LOCAL_KERNEL := device/linaro/poplar-kernel/Image
PRODUCT_COPY_FILES += $(LOCAL_KERNEL):kernel
LOCAL_DTB := device/linaro/poplar-kernel/hi3798cv200-poplar.dtb
PRODUCT_COPY_FILES += $(LOCAL_DTB):2ndbootloader

# 1.2 rootfs
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/vendor/ueventd.poplar.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
    $(LOCAL_PATH)/vendor/fstab.poplar:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.poplar

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/vendor/init.poplar.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.poplar.rc \
    $(LOCAL_PATH)/vendor/init.poplar.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.poplar.usb.rc


# feature declaration
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.app_widgets.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.app_widgets.xml \
    frameworks/native/data/etc/android.hardware.screen.landscape.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.screen.landscape.xml \
    frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
    frameworks/native/data/etc/android.software.print.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.print.xml \
    frameworks/native/data/etc/android.software.webview.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.webview.xml \
    frameworks/native/data/etc/android.hardware.ethernet.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.ethernet.xml


# framework properties
PRODUCT_PROPERTY_OVERRIDES += \
    dalvik.vm.heapstartsize=5m \
    dalvik.vm.heapgrowthlimit=96m \
    dalvik.vm.heapsize=256m \
    dalvik.vm.heaptargetutilization=0.75 \
    dalvik.vm.heapminfree=512k \
    dalvik.vm.heapmaxfree=2m \
    ro.sf.lcd_density=160 \
    ro.opengles.version=196609


# start HAL audio >>>>>>>>
## feature declaration
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.audio.output.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.output.xml \
    frameworks/native/data/etc/android.hardware.audio.low_latency.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.low_latency.xml
## build packages
PRODUCT_PACKAGES += \
    android.hardware.audio@2.0-impl \
    android.hardware.audio@2.0-service \
    android.hardware.audio.effect@2.0-impl \
    android.hardware.soundtrigger@2.0-impl \
    audio.a2dp.default \
    audio.usb.default \
    audio.r_submix.default \
    audio.primary.poplar
## runtime configs
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/audio_policy.conf:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy.conf


# start HAL drm >>>>>>>>
## build packages
PRODUCT_PACKAGES += \
    android.hardware.drm@1.0-impl
# raw instructions - do I have a better place to go?
ifeq ($(TARGET_TEE_IS_OPTEE), true)
   $(call inherit-product-if-exists, device/linaro/poplar/optee/optee-packages.mk)
endif
## service init.rc scripts
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/optee/optee.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/optee.rc


# start HAL graphics >>>>>>>>
## build packages
PRODUCT_PACKAGES += \
    libion \
    android.hardware.graphics.mapper@2.0 \
    android.hardware.graphics.mapper@2.0-impl \
    android.hardware.graphics.allocator@2.0 \
    android.hardware.graphics.allocator@2.0-impl \
    android.hardware.graphics.allocator@2.0-service \
    android.hardware.graphics.composer@2.1 \
    android.hardware.graphics.composer@2.1-impl \
    android.hardware.graphics.composer@2.1-service
## copy packages
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/proprietary/hisilicon/libGLES_mali.so:$(TARGET_COPY_OUT_VENDOR)/lib/egl/libGLES_mali.so \
    $(LOCAL_PATH)/proprietary/hisilicon/hwcomposer.poplar.so:$(TARGET_COPY_OUT_VENDOR)/lib/hw/hwcomposer.poplar.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libhi_gfx2d.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhi_gfx2d.so \
    $(LOCAL_PATH)/proprietary/hisilicon/overlay.poplar.so:$(TARGET_COPY_OUT_VENDOR)/lib/hw/overlay.poplar.so \
    $(LOCAL_PATH)/proprietary/hisilicon/gralloc.poplar.so:$(TARGET_COPY_OUT_VENDOR)/lib/hw/gralloc.poplar.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libion_ext.so:$(TARGET_COPY_OUT_VENDOR)/lib/libion_ext.so

# Property required by HiSilicon gralloc
PRODUCT_PROPERTY_OVERRIDES += \
    ro.config.build.name=poplar.androidp


# start HAL keymaster >>>>>>>>
## build packages
PRODUCT_PACKAGES += \
    android.hardware.keymaster@3.0-impl \
    android.hardware.keymaster@3.0-service


# start HAL media.codec >>>>>>>>
## copy packages
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/proprietary/hisilicon/libstagefrighthw.so:$(TARGET_COPY_OUT_VENDOR)/lib/libstagefrighthw.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libhi_common.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhi_common.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libhi_msp.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhi_msp.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libhi_vfmw.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhi_vfmw.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libOMX_Core.so:$(TARGET_COPY_OUT_VENDOR)/lib/libOMX_Core.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libOMX.hisi.video.decoder.so:$(TARGET_COPY_OUT_VENDOR)/lib/libOMX.hisi.video.decoder.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libhiavplayer.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhiavplayer.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libhiavplayer_adp.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhiavplayer_adp.so \
    $(LOCAL_PATH)/proprietary/hisilicon/libhiavplayerservice.so:$(TARGET_COPY_OUT_VENDOR)/lib/libhiavplayerservice.so \
    $(LOCAL_PATH)/proprietary/hisilicon/hiavplayer:$(TARGET_COPY_OUT_VENDOR)/bin/hiavplayer
## service init.rc scripts
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/vendor/hiavplayer.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hiavplayer.rc
## runtime configs
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/media/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_video.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_video.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_audio.xml


# start HAL bt >>>>>>>>
## feature declaration
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml
## build packages
PRODUCT_PACKAGES += \
    android.hardware.bluetooth@1.0-service \
    android.hardware.bluetooth@1.0-service.rc \
    android.hardware.bluetooth@1.0-impl
## copy packages
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/proprietary/bt-wifi/libbt-vendor.so:$(TARGET_COPY_OUT_VENDOR)/lib/libbt-vendor.so
## config files
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/bluetooth/rtkbt.conf:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth/rtkbt.conf
## firmwares
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/proprietary/bt-wifi/rtl8822b_config:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl8822b_config \
    $(LOCAL_PATH)/proprietary/bt-wifi/rtl8822b_fw:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl8822b_fw
## service init.rc scripts
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/bluetooth/bt.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/bt.rc


# start HAL wifi >>>>>>>>
## feature declaration
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml
## build packages
PRODUCT_PACKAGES += \
    android.hardware.wifi@1.0-service \
    wificond \
    libwpa_client \
    wpa_supplicant \
    hostapd
## config files
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/wifi/wpa_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant.conf \
    $(LOCAL_PATH)/wifi/p2p_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/p2p_supplicant.conf
## service init.rc scripts
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/wifi/wifi.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/wifi.rc
## feature wifi properties
PRODUCT_PROPERTY_OVERRIDES += \
    wifi.interface=wlan0 \
    wifi.supplicant_scan_interval=15


# manifest.xml
DEVICE_MANIFEST_FILE := $(LOCAL_PATH)/manifest.xml

# VNDK libraries
PRODUCT_PACKAGES += vndk_package
