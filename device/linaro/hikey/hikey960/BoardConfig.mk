include device/linaro/hikey/BoardConfigCommon.mk

TARGET_BOOTLOADER_BOARD_NAME := hikey960
TARGET_BOARD_PLATFORM := hikey960

TARGET_CPU_VARIANT := cortex-a73
TARGET_2ND_CPU_VARIANT := cortex-a73

TARGET_NO_DTIMAGE := false

BOARD_KERNEL_CMDLINE := androidboot.hardware=hikey960 firmware_class.path=/vendor/firmware efi=noruntime init=/init
BOARD_KERNEL_CMDLINE += androidboot.boot_devices=soc/ff3b0000.ufs
BOARD_KERNEL_CMDLINE += loglevel=15 androidboot.slot_suffix=_a

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

BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864
BOARD_USERDATAIMAGE_PARTITION_SIZE := 25845301248 # 24648MB
BOARD_FLASH_BLOCK_SIZE := 512

# Vendor/system_ext/product partition definitions
TARGET_COPY_OUT_VENDOR := vendor
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := ext4
TARGET_COPY_OUT_PRODUCT := product
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_USES_METADATA_PARTITION := true

#Dynamic Partition details
TARGET_USE_DYNAMIC_PARTITIONS := true
BOARD_BUILD_SUPER_IMAGE_BY_DEFAULT := true
BOARD_SUPER_PARTITION_SIZE := 4915724288
BOARD_SUPER_PARTITION_GROUPS := hikey960_dynamic_partitions
BOARD_HIKEY960_DYNAMIC_PARTITIONS_PARTITION_LIST := system vendor system_ext product
BOARD_HIKEY960_DYNAMIC_PARTITIONS_SIZE := 4911529984  # Reserve 4M for DAP metadata
BOARD_SUPER_PARTITION_METADATA_DEVICE := super
BOARD_SUPER_IMAGE_IN_UPDATE_PACKAGE := true

TARGET_RECOVERY_FSTAB := device/linaro/hikey/hikey960/fstab.hikey960
