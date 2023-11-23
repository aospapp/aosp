BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/storage/sepolicy

PRODUCT_PACKAGES += dump_storage.sh

# Pixel storage tool
PRODUCT_PACKAGES_DEBUG += \
	sg_write_buffer \
	sg_read_buffer
