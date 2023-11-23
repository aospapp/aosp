$(call inherit-product, device/linaro/dragonboard/mini.mk)
$(call inherit-product, device/linaro/dragonboard/db845c/device.mk)

# Product overrides
PRODUCT_NAME := db845c_mini
PRODUCT_DEVICE := db845c
PRODUCT_BRAND := Android
