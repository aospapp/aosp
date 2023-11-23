ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=4.19
endif

$(call inherit-product, device/amlogic/yukawa/device-common.mk)

BOARD_KERNEL_DTB := device/amlogic/yukawa-kernel/meson-g12a-sei510.dtb-$(TARGET_KERNEL_USE)

ifeq ($(TARGET_PREBUILT_DTB),)
LOCAL_DTB := $(BOARD_KERNEL_DTB)
else
LOCAL_DTB := $(TARGET_PREBUILT_DTB)
endif

PRODUCT_COPY_FILES +=  $(LOCAL_DTB):meson-g12a-sei510.dtb

