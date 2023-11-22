$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/linaro/hikey/hikey-common.mk)

PRODUCT_NAME := hikey
PRODUCT_DEVICE := hikey
PRODUCT_BRAND := Android
