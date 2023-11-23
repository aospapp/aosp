ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=4.19
endif
TARGET_PREBUILT_KERNEL := device/linaro/hikey-kernel/Image.gz-dtb-hikey960-$(TARGET_KERNEL_USE)
TARGET_PREBUILT_DTB := device/linaro/hikey-kernel/hi3660-hikey960.dtb-$(TARGET_KERNEL_USE)

ifeq ($(TARGET_KERNEL_USE), 4.4)
  HIKEY_USE_DRM_HWCOMPOSER := false
  HIKEY_USE_LEGACY_TI_BLUETOOTH := true
else
  ifeq ($(TARGET_KERNEL_USE), 4.9)
    HIKEY_USE_DRM_HWCOMPOSER := false
  else
    HIKEY_USE_DRM_HWCOMPOSER := true
  endif
  HIKEY_USE_LEGACY_TI_BLUETOOTH := false
endif

#
# Inherit the full_base and device configurations
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/linaro/hikey/hikey960/device-hikey960.mk)
$(call inherit-product, device/linaro/hikey/device-common.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)

#setup dm-verity configs
PRODUCT_SYSTEM_VERITY_PARTITION := /dev/block/platform/soc/ff3b0000.ufs/by-name/system
PRODUCT_VENDOR_VERITY_PARTITION := /dev/block/platform/soc/ff3b0000.ufs/by-name/vendor
$(call inherit-product, build/target/product/verity.mk)
PRODUCT_SUPPORTS_BOOT_SIGNER := false
PRODUCT_SUPPORTS_VERITY_FEC := false

PRODUCT_PROPERTY_OVERRIDES += ro.opengles.version=196608

#
# Overrides
PRODUCT_NAME := hikey960
PRODUCT_DEVICE := hikey960
PRODUCT_BRAND := Android
PRODUCT_MODEL := AOSP on hikey960

ifneq ($(HIKEY_USES_GKI),)
  HIKEY_MOD_DIR := device/linaro/hikey-kernel/hikey960/$(TARGET_KERNEL_USE)
  HIKEY_MODS := $(wildcard $(HIKEY_MOD_DIR)/*.ko)
  ifneq ($(HIKEY_MODS),)
    BOARD_VENDOR_KERNEL_MODULES += $(HIKEY_MODS)
    BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
	$(HIKEY_MOD_DIR)/ion_cma_heap.ko \
	$(HIKEY_MOD_DIR)/ufs-hisi.ko
  endif
endif
