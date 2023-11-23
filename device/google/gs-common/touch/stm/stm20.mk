PRODUCT_VENDOR_PROPERTIES += ro.vendor.touch.dump.sys=/sys/class/spi_master/spi20/spi20.0
PRODUCT_VENDOR_PROPERTIES += ro.vendor.touch.dump.proc=/proc/fts/driver_test

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/touch/stm/sepolicy

PRODUCT_PACKAGES += dump_stm.sh
