#
# Inherit the full_base and device configurations
$(call inherit-product, device/linaro/hikey/hikey.mk)

#
# Overrides
PRODUCT_NAME := hikey_tv
PRODUCT_DEVICE := hikey
PRODUCT_BRAND := Android
PRODUCT_MODEL := AOSP TV on hikey
