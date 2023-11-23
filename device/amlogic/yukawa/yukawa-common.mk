
PRODUCT_SYSTEM_VERITY_PARTITION := /dev/block/platform/ffe07000.emmc/by-name/system
PRODUCT_VENDOR_VERITY_PARTITION := /dev/block/platform/ffe07000.emmc/by-name/vendor
$(call inherit-product, build/target/product/verity.mk)
PRODUCT_SUPPORTS_BOOT_SIGNER := false

PRODUCT_SHIPPING_API_LEVEL := 29
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false

PRODUCT_BRAND := Android
PRODUCT_MODEL := ATV on yukawa
PRODUCT_MANUFACTURER := SEI Robotics
