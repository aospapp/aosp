$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/linaro/hikey/hikey-common.mk)

#setup dm-verity configs
PRODUCT_SYSTEM_VERITY_PARTITION := /dev/block/platform/soc/f723d000.dwmmc0/by-name/system
PRODUCT_VENDOR_VERITY_PARTITION := /dev/block/platform/soc/f723d000.dwmmc0/by-name/vendor
$(call inherit-product, build/target/product/verity.mk)
PRODUCT_SUPPORTS_BOOT_SIGNER := false
PRODUCT_SUPPORTS_VERITY_FEC := false

PRODUCT_PROPERTY_OVERRIDES += ro.opengles.version=131072

PRODUCT_NAME := hikey
PRODUCT_DEVICE := hikey
PRODUCT_BRAND := Android
