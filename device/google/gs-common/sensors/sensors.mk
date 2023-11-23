$(call inherit-product-if-exists, vendor/google/sensors/usf/android/usf_efw_product.mk)

PRODUCT_PACKAGES += dump_sensors
BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/sensors/sepolicy

