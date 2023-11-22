
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)

PRODUCT_NAME := poplar
PRODUCT_DEVICE := poplar
PRODUCT_BRAND := poplar
PRODUCT_MODEL := poplar
PRODUCT_MANUFACTURER := hisilicon

DEVICE_PACKAGE_OVERLAYS := device/linaro/poplar/overlay

# automatically called
$(call inherit-product, device/linaro/poplar/device.mk)

