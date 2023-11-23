PRODUCT_VENDOR_PROPERTIES += ro.vendor.touch.dump.sys=/sys/class/spi_master/spi6/spi6.0
PRODUCT_VENDOR_PROPERTIES += ro.vendor.touch.dump.proc=/proc/fts_ext/driver_test

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/touch/stm/sepolicy

PRODUCT_PACKAGES += dump_stm.sh
