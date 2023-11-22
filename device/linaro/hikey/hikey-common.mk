
ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=4.9
endif

ifndef TARGET_COMPRESSED_KERNEL
TARGET_COMPRESSED_KERNEL=false
endif

ifeq ($(TARGET_COMPRESSED_KERNEL), false)
TARGET_PREBUILT_KERNEL := device/linaro/hikey-kernel/Image-dtb-$(TARGET_KERNEL_USE)
else
TARGET_PREBUILT_KERNEL := device/linaro/hikey-kernel/Image.gz-dtb-$(TARGET_KERNEL_USE)
endif

TARGET_PREBUILT_DTB := device/linaro/hikey-kernel/hi6220-hikey.dtb-$(TARGET_KERNEL_USE)

ifeq ($(TARGET_KERNEL_USE), 3.18)
  TARGET_FSTAB := fstab.hikey-$(TARGET_KERNEL_USE)
  HIKEY_USE_LEGACY_TI_BLUETOOTH := true
else
  ifeq ($(TARGET_KERNEL_USE), 4.4)
    HIKEY_USE_LEGACY_TI_BLUETOOTH := true
  else
    HIKEY_USE_LEGACY_TI_BLUETOOTH := false
  endif
  TARGET_FSTAB := fstab.hikey
endif

$(call inherit-product, device/linaro/hikey/hikey/device-hikey.mk)
$(call inherit-product, device/linaro/hikey/device-common.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)
