$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/linaro/hikey/hikey-common.mk)

#setup dm-verity configs
PRODUCT_SYSTEM_VERITY_PARTITION := /dev/block/platform/soc/f723d000.dwmmc0/by-name/system
PRODUCT_VENDOR_VERITY_PARTITION := /dev/block/platform/soc/f723d000.dwmmc0/by-name/vendor
$(call inherit-product, build/target/product/verity.mk)
PRODUCT_SUPPORTS_BOOT_SIGNER := false
PRODUCT_SUPPORTS_VERITY_FEC := false

PRODUCT_PROPERTY_OVERRIDES += ro.opengles.version=131072

PRODUCT_NAME := hikey
PRODUCT_DEVICE := hikey
PRODUCT_BRAND := Android

ifneq ($(HIKEY_USES_GKI),)
HIKEY_MOD_DIR := $(LOCAL_KERNEL_HOME)
HIKEY_MODS := $(wildcard $(HIKEY_MOD_DIR)/*.ko)
ifneq ($(HIKEY_MODS),)
  BOARD_VENDOR_KERNEL_MODULES += $(HIKEY_MODS)
  # XXX dwc2/phy-hi6220-usb have some timing
  # issue that prevents gadget mode from working
  # unless they are loaded from initrd. Need to fix.
  BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
	$(HIKEY_MOD_DIR)/dwc2.ko \
	$(HIKEY_MOD_DIR)/phy-hi6220-usb.ko

  # make sure ion cma heap loads early
  CMA_HEAP_KO := $(wildcard $(HIKEY_MOD_DIR)/cma_heap*.ko)
  ION_CMA_HEAP_KO := $(wildcard $(HIKEY_MOD_DIR)/ion_cma_heap*.ko)
  BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
      $(CMA_HEAP_KO) \
      $(ION_CMA_HEAP_KO)

  # Not sure why, but powerkey has to be initrd
  # or else we'll see stalls or issues at bootup
  BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
	$(HIKEY_MOD_DIR)/hisi_powerkey.ko

  MMC_CORE_KO := $(wildcard $(HIKEY_MOD_DIR)/mmc_core.ko)
  MMC_BLOCK_KO := $(wildcard $(HIKEY_MOD_DIR)/mmc_block.ko)
  BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
      $(MMC_CORE_KO) \
      $(MMC_BLOCK_KO)

  BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
	$(HIKEY_MOD_DIR)/hi655x-regulator.ko \
	$(HIKEY_MOD_DIR)/clk-hi655x.ko \
	$(HIKEY_MOD_DIR)/hi655x-pmic.ko \
	$(HIKEY_MOD_DIR)/dw_mmc-k3.ko \
	$(HIKEY_MOD_DIR)/dw_mmc-pltfm.ko \
	$(HIKEY_MOD_DIR)/dw_mmc.ko \

endif
endif
