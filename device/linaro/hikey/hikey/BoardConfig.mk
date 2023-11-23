include device/linaro/hikey/BoardConfigCommon.mk

TARGET_BOOTLOADER_BOARD_NAME := hikey
TARGET_BOARD_PLATFORM := hikey

TARGET_CPU_VARIANT := cortex-a53
TARGET_2ND_CPU_VARIANT := cortex-a53

BOARD_KERNEL_CMDLINE := androidboot.hardware=hikey firmware_class.path=/vendor/firmware efi=noruntime init=/init
BOARD_KERNEL_CMDLINE += androidboot.boot_devices=soc/f723d000.dwmmc0
BOARD_KERNEL_CMDLINE += console=ttyAMA3,115200 androidboot.console=ttyAMA3

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
