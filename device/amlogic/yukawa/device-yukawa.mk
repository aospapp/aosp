ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=4.19
endif

$(call inherit-product, device/amlogic/yukawa/device-common.mk)

ifeq ($(TARGET_VIM3), true)
PRODUCT_PROPERTY_OVERRIDES += ro.product.device=vim3
AUDIO_DEFAULT_OUTPUT := hdmi
GPU_TYPE := gondul_ion
else ifeq ($(TARGET_VIM3L), true)
PRODUCT_PROPERTY_OVERRIDES += ro.product.device=vim3l
AUDIO_DEFAULT_OUTPUT := hdmi
else
PRODUCT_PROPERTY_OVERRIDES += ro.product.device=sei610
endif
GPU_TYPE ?= dvalin_ion

BOARD_KERNEL_DTB := device/amlogic/yukawa-kernel/$(TARGET_KERNEL_USE)

ifeq ($(TARGET_PREBUILT_DTB),)
LOCAL_DTB := $(BOARD_KERNEL_DTB)
else
LOCAL_DTB := $(TARGET_PREBUILT_DTB)
endif

# Feature permissions
PRODUCT_COPY_FILES += \
    device/amlogic/yukawa/permissions/yukawa.xml:/system/etc/sysconfig/yukawa.xml

# Speaker EQ
PRODUCT_COPY_FILES += \
    device/amlogic/yukawa/hal/audio/speaker_eq_sei610.fir:$(TARGET_COPY_OUT_VENDOR)/etc/speaker_eq_sei610.fir
