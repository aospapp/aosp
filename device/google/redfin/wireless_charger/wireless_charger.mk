# This wireless_charger folder is used to keep the compatibility for later google devices
PRODUCT_SOONG_NAMESPACES += vendor/google/interfaces
PRODUCT_PACKAGES += vendor.google.wireless_charger-default
DEVICE_PRODUCT_COMPATIBILITY_MATRIX_FILE += device/google/redfin/wireless_charger/compatibility_matrix.xml

BOARD_VENDOR_SEPOLICY_DIRS += device/google/redfin-sepolicy/wireless_charger
