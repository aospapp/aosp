ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=5.10
endif

$(call inherit-product, device/amlogic/yukawa/device-common.mk)

PRODUCT_PROPERTY_OVERRIDES += ro.product.device=sei510

GPU_TYPE ?= dvalin_ion

BOARD_KERNEL_DTB := device/amlogic/yukawa-kernel/$(TARGET_KERNEL_USE)

ifeq ($(TARGET_PREBUILT_DTB),)
LOCAL_DTB := $(BOARD_KERNEL_DTB)
else
LOCAL_DTB := $(TARGET_PREBUILT_DTB)
endif

# Feature permissions
PRODUCT_COPY_FILES += \
    device/amlogic/yukawa/permissions/yukawa.xml:/system/etc/sysconfig/yukawa.xml
