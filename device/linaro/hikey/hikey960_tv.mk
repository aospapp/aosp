#
# Inherit the full_base and device configurations
$(call inherit-product, device/linaro/hikey/hikey960.mk)

#
# Overrides
PRODUCT_NAME := hikey960_tv
PRODUCT_DEVICE := hikey960
PRODUCT_BRAND := Android
PRODUCT_MODEL := AOSP TV on hikey960
