# Use this file to generate dtb.img and dtbo.img instead of using
# BOARD_PREBUILT_DTBIMAGE_DIR. We need to keep dtb and dtbo files at the fixed
# positions in images, so that bootloader can rely on their indexes in the
# image. As dtbo.img must be signed with AVB tool, we generate intermediate
# dtbo.img, and the resulting $(PRODUCT_OUT)/dtbo.img will be created with
# Android build system, by exploiting BOARD_PREBUILT_DTBOIMAGE variable.

ifneq ($(filter beagle_x15%, $(TARGET_DEVICE)),)

MKDTIMG := $(realpath prebuilts/misc/$(HOST_PREBUILT_TAG)/libufdt/mkdtimg)
DTBIMAGE := $(PRODUCT_OUT)/dtb.img
DTBOIMAGE := $(PRODUCT_OUT)/$(DTBO_UNSIGNED)

# Please keep this list fixed: add new files in the end of the list
DTB_FILES := \
	$(DTB_DIR)/am57xx-beagle-x15-revc.dtb \

# Please keep this list fixed: add new files in the end of the list
DTBO_FILES := \
	$(DTBO_DIR)/am57xx-evm-common.dtbo \
	$(DTBO_DIR)/am57xx-evm-reva3.dtbo

$(DTBIMAGE): $(DTB_FILES)
	cat $^ > $@

$(DTBOIMAGE): $(DTBO_FILES)
	$(MKDTIMG) create $@ $^

include $(CLEAR_VARS)
LOCAL_MODULE := dtbimage
LOCAL_LICENSE_KINDS := legacy_notice
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_ADDITIONAL_DEPENDENCIES := $(DTBIMAGE)
include $(BUILD_PHONY_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE := dtboimage
LOCAL_LICENSE_KINDS := legacy_notice
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_ADDITIONAL_DEPENDENCIES := $(DTBOIMAGE)
include $(BUILD_PHONY_PACKAGE)

droidcore: dtbimage dtboimage

endif
