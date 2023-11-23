ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=4.19
endif

$(call inherit-product, device/amlogic/yukawa/device-common.mk)

PRODUCT_PROPERTY_OVERRIDES += ro.product.device=sei510

BOARD_KERNEL_DTB := device/amlogic/yukawa-kernel

ifeq ($(TARGET_PREBUILT_DTB),)
LOCAL_DTB := $(BOARD_KERNEL_DTB)
else
LOCAL_DTB := $(TARGET_PREBUILT_DTB)
endif
