ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=4.19
endif

LOCAL_KERNEL_HOME ?= device/linaro/hikey-kernel/hikey/$(TARGET_KERNEL_USE)
TARGET_PREBUILT_KERNEL := $(LOCAL_KERNEL_HOME)/Image.gz-dtb
TARGET_PREBUILT_DTB := $(LOCAL_KERNEL_HOME)/hi6220-hikey.dtb

PRODUCT_ENFORCE_VINTF_MANIFEST_OVERRIDE := true

TARGET_FSTAB := fstab.hikey

$(call inherit-product, device/linaro/hikey/hikey/device-hikey.mk)
$(call inherit-product, device/linaro/hikey/device-common.mk)
