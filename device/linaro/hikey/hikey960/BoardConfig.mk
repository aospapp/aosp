include device/linaro/hikey/BoardConfigCommon.mk

TARGET_BOOTLOADER_BOARD_NAME := hikey960
TARGET_BOARD_PLATFORM := hikey960

TARGET_CPU_VARIANT := cortex-a73
TARGET_2ND_CPU_VARIANT := cortex-a73

TARGET_NO_DTIMAGE := false

BOARD_KERNEL_CMDLINE := androidboot.hardware=hikey960 firmware_class.path=/vendor/firmware efi=noruntime init=/init
BOARD_KERNEL_CMDLINE += androidboot.boot_devices=soc/ff3b0000.ufs
BOARD_KERNEL_CMDLINE += loglevel=15

ifeq ($(TARGET_BUILTIN_EDID), true)
BOARD_KERNEL_CMDLINE += drm_kms_helper.edid_firmware=edid/1920x1080.bin
endif

# On kernels before 4.19, enable dtb fstab. On kernels >= 4.19, both dtb
# fstab and android-verity are deprecated, so until we have avb2 support
# in the bootloader, don't enable either feature. The ramdisk fstab
# needed for the new mechanism will be installed unconditionally; if dtb
# fstab is present, it will override it automatically.
ifneq ($(TARGET_KERNEL_USE),4.19)
# Enable treble dtb fstab with verity
ifneq ($(TARGET_ANDROID_VERITY),)
BOARD_KERNEL_CMDLINE += overlay_mgr.overlay_dt_entry=hardware_cfg_enable_android_fstab_v2
BOARD_KERNEL_CMDLINE += rootwait ro root=/dev/dm-0
BOARD_KERNEL_CMDLINE += dm=\"system none ro,0 1 android-verity 8:58\"
BOARD_BUILD_SYSTEM_ROOT_IMAGE := true
else
# Enable treble dtb fstab without verity
BOARD_KERNEL_CMDLINE += overlay_mgr.overlay_dt_entry=hardware_cfg_enable_android_fstab
endif
endif

ifneq ($(TARGET_SENSOR_MEZZANINE),)
BOARD_KERNEL_CMDLINE += overlay_mgr.overlay_dt_entry=hardware_cfg_$(TARGET_SENSOR_MEZZANINE)
endif

BOARD_MKBOOTIMG_ARGS := --base 0x0 --tags_offset 0x07a00000 --kernel_offset 0x00080000 --ramdisk_offset 0x07c00000

BOARD_SYSTEMIMAGE_PARTITION_SIZE := 4915724288    # 4688MB
BOARD_USERDATAIMAGE_PARTITION_SIZE := 25845301248 # 24648MB
BOARD_FLASH_BLOCK_SIZE := 512

# Vendor partition definitions
TARGET_COPY_OUT_VENDOR := vendor
BOARD_VENDORIMAGE_PARTITION_SIZE := 822083584     # 784MB
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_JOURNAL_SIZE := 0
BOARD_VENDORIMAGE_EXTFS_INODE_COUNT := 2048

TARGET_RECOVERY_FSTAB := device/linaro/hikey/hikey960/fstab.hikey960
