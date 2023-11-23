# Trusty Metrics Daemon
PRODUCT_SOONG_NAMESPACES += \
	vendor/google/trusty/common

PRODUCT_PACKAGES += trusty_metricsd
BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs101-sepolicy/trusty_metricsd
