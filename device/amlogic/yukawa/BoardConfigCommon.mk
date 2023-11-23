#
# Product-specific compile-time definitions.
#
# The generic product target doesn't have any hardware-specific pieces.
# Primary Arch
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := cortex-a53

# Secondary Arch
TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := cortex-a53

TARGET_USES_64_BIT_BINDER := true
TARGET_SUPPORTS_64_BIT_APPS := true

TARGET_BOARD_PLATFORM := yukawa

# Vulkan
BOARD_INSTALL_VULKAN := true
# BT configs
BOARD_HAVE_BLUETOOTH := true

# generic wifi
WPA_SUPPLICANT_VERSION := VER_0_8_X
BOARD_WPA_SUPPLICANT_DRIVER := NL80211
BOARD_HOSTAPD_DRIVER := NL80211

# Treble
PRODUCT_FULL_TREBLE_OVERRIDE := true
BOARD_VNDK_VERSION := current

TARGET_NO_BOOTLOADER := true
TARGET_NO_KERNEL := false
TARGET_NO_RECOVERY := true

BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 1610612736
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_PARTITION_SIZE := 268435456
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_CACHEIMAGE_PARTITION_SIZE := 268435456
BOARD_CACHEIMAGE_FILE_SYSTEM_TYPE := ext4
TARGET_USERIMAGES_USE_EXT4 := true

BOARD_FLASH_BLOCK_SIZE := 512

TARGET_COPY_OUT_VENDOR := vendor

BOARD_BUILD_SYSTEM_ROOT_IMAGE := true

BOARD_KERNEL_OFFSET      := 0x1080000
BOARD_KERNEL_TAGS_OFFSET := 0x1000000
BOARD_MKBOOTIMG_ARGS     := --second $(BOARD_KERNEL_DTB) --kernel_offset $(BOARD_KERNEL_OFFSET) --second_offset $(BOARD_KERNEL_TAGS_OFFSET)

BOARD_KERNEL_CMDLINE += root=PARTUUID=99f9b7ac-5207-11e9-8507-c3c037e393f3
BOARD_KERNEL_CMDLINE += no_console_suspend console=ttyAML0,115200 earlycon
BOARD_KERNEL_CMDLINE += printk.devkmsg=on
BOARD_KERNEL_CMDLINE += skip_initramfs init=/init
BOARD_KERNEL_CMDLINE += firmware_class.path=/vendor/firmware
BOARD_KERNEL_CMDLINE += androidboot.hardware=yukawa
ifneq ($(TARGET_SELINUX_ENFORCE), true)
BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive
endif
ifeq ($(TARGET_BUILTIN_EDID), true)
BOARD_KERNEL_CMDLINE += drm.edid_firmware=edid/1920x1080.bin
endif

USE_E2FSPROGS := true

BOARD_USES_GENERIC_AUDIO := false
BOARD_USES_ALSA_AUDIO := true
TARGET_USES_MKE2FS := true
TARGET_USES_HWC2 := true
BOARD_BLUETOOTH_BDROID_BUILDCFG_INCLUDE_DIR := device/amlogic/yukawa/bluetooth

BOARD_SEPOLICY_DIRS += \
        device/amlogic/yukawa/sepolicy

DEVICE_MANIFEST_FILE := device/amlogic/yukawa/manifest.xml
ifeq ($(TARGET_KERNEL_USE), 5.4)
DEVICE_MANIFEST_FILE += device/amlogic/yukawa/manifest_kernel5.xml
endif
DEVICE_MATRIX_FILE := device/amlogic/yukawa/compatibility_matrix.xml
