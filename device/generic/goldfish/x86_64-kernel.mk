TARGET_KERNEL_USE ?= 5.15

KERNEL_MODULES_PATH := kernel/prebuilts/common-modules/virtual-device/$(TARGET_KERNEL_USE)/x86-64

BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
    $(filter-out $(KERNEL_MODULES_EXCLUDE), $(wildcard $(KERNEL_MODULES_PATH)/*.ko))

EMULATOR_KERNEL_FILE := kernel/prebuilts/$(TARGET_KERNEL_USE)/x86_64/kernel-$(TARGET_KERNEL_USE)
