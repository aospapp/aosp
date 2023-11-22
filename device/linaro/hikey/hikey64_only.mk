$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, device/linaro/hikey/hikey-common.mk)

PRODUCT_NAME := hikey64_only
PRODUCT_DEVICE := hikey64
PRODUCT_BRAND := Android
