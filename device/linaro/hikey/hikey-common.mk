ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=4.14
endif

HIKEY_USE_DRM_HWCOMPOSER := false

TARGET_PREBUILT_KERNEL := device/linaro/hikey-kernel/Image.gz-dtb-$(TARGET_KERNEL_USE)

TARGET_PREBUILT_DTB := device/linaro/hikey-kernel/hi6220-hikey.dtb-$(TARGET_KERNEL_USE)

PRODUCT_ENFORCE_VINTF_MANIFEST_OVERRIDE := true

ifeq ($(TARGET_KERNEL_USE), 4.4)
  HIKEY_USE_LEGACY_TI_BLUETOOTH := true
else
  HIKEY_USE_LEGACY_TI_BLUETOOTH := false
  HIKEY_USE_DRM_HWCOMPOSER := true
endif
TARGET_FSTAB := fstab.hikey

$(call inherit-product, device/linaro/hikey/hikey/device-hikey.mk)
$(call inherit-product, device/linaro/hikey/device-common.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)
