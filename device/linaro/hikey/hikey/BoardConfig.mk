include device/linaro/hikey/BoardConfigCommon.mk

TARGET_BOOTLOADER_BOARD_NAME := hikey
TARGET_BOARD_PLATFORM := hikey

TARGET_CPU_VARIANT := cortex-a53
TARGET_2ND_CPU_VARIANT := cortex-a53

BOARD_KERNEL_CMDLINE := androidboot.hardware=hikey firmware_class.path=/vendor/firmware efi=noruntime init=/init
BOARD_KERNEL_CMDLINE += androidboot.boot_devices=soc/f723d000.dwmmc0
BOARD_KERNEL_CMDLINE += console=ttyAMA3,115200 androidboot.console=ttyAMA3

# On kernels before 4.14, enable dtb fstab with android-verity. On kernels >=
# 4.14, both dtb fstab and android-verity are deprecated, so until we have
# avb2 support in the bootloader, don't enable either feature. The ramdisk
# fstab needed for the new mechanism will be installed unconditionally; if
# dtb fstab is present, it will override it automatically.
ifneq ($(TARGET_KERNEL_USE),4.19)
ifneq ($(TARGET_KERNEL_USE),4.14)
BOARD_KERNEL_CMDLINE += overlay_mgr.overlay_dt_entry=hardware_cfg_enable_android_fstab_v2
BOARD_KERNEL_CMDLINE += rootwait ro root=/dev/dm-0
BOARD_KERNEL_CMDLINE += dm=\"system none ro,0 1 android-verity 179:9\"
BOARD_BUILD_SYSTEM_ROOT_IMAGE := true
endif
endif

ifneq ($(TARGET_SENSOR_MEZZANINE),)
BOARD_KERNEL_CMDLINE += overlay_mgr.overlay_dt_entry=hardware_cfg_$(TARGET_SENSOR_MEZZANINE)
endif

## printk.devkmsg only has meaning for kernel 4.9 and later
## it would be ignored by kernel 4.4
BOARD_KERNEL_CMDLINE += printk.devkmsg=on

TARGET_NO_DTIMAGE := true

BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 1610612736
ifeq ($(TARGET_USERDATAIMAGE_4GB), true) # to build for aosp-4g partition table
BOARD_USERDATAIMAGE_PARTITION_SIZE := 1595915776
else
ifeq ($(TARGET_WITH_SWAP), true) # to build for swap-8g partition table
BOARD_USERDATAIMAGE_PARTITION_SIZE := 4246715904
else
BOARD_USERDATAIMAGE_PARTITION_SIZE := 5588893184
endif
endif
BOARD_FLASH_BLOCK_SIZE := 131072

# Vendor partition definitions
TARGET_COPY_OUT_VENDOR := vendor
BOARD_VENDORIMAGE_PARTITION_SIZE := 268435456 # 256MB
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_JOURNAL_SIZE := 0
BOARD_VENDORIMAGE_EXTFS_INODE_COUNT := 2048

TARGET_RECOVERY_FSTAB := device/linaro/hikey/hikey/$(TARGET_FSTAB)
