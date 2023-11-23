$(call inherit-product, device/linaro/dragonboard/mini.mk)
$(call inherit-product, device/linaro/dragonboard/sm8450/device.mk)

# Product overrides
PRODUCT_NAME := sm8450_mini
PRODUCT_DEVICE := sm8450
PRODUCT_BRAND := Android
