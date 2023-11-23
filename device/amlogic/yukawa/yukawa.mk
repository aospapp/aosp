# Inherit the full_base and device configurations
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/amlogic/yukawa/device-yukawa.mk)
$(call inherit-product, device/amlogic/yukawa/yukawa-common.mk)

PRODUCT_NAME := yukawa
PRODUCT_DEVICE := yukawa

MOD_DIR = device/amlogic/yukawa-kernel/$(TARGET_KERNEL_USE)

#
# Put all the modules in the rootfs...
#
BOARD_VENDOR_KERNEL_MODULES := $(wildcard $(MOD_DIR)/*.ko)

ifneq ($(BOARD_VENDOR_KERNEL_MODULES),)

#
# ...and only a subset on the ramdisk.
#
# core clock providers
BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
  $(MOD_DIR)/axg.ko \
  $(MOD_DIR)/axg-audio.ko \
  $(MOD_DIR)/axg-aoclk.ko \
  $(MOD_DIR)/clk-cpu-dyndiv.ko \
  $(MOD_DIR)/clk-regmap.ko \
  $(MOD_DIR)/clk-phase.ko \
  $(MOD_DIR)/gxbb-aoclk.ko \
  $(MOD_DIR)/clk-dualdiv.ko \
  $(MOD_DIR)/clk-pll.ko \
  $(MOD_DIR)/clk-mpll.ko \
  $(MOD_DIR)/meson-eeclk.ko \
  $(MOD_DIR)/sclk-div.ko \
  $(MOD_DIR)/g12a-aoclk.ko \
  $(MOD_DIR)/g12a.ko \
  $(MOD_DIR)/meson-aoclk.ko \
  $(MOD_DIR)/vid-pll-div.ko \
  $(MOD_DIR)/gxbb.ko

# pinctrl
BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
  $(MOD_DIR)/pinctrl-meson-a1.ko \
  $(MOD_DIR)/pinctrl-meson-axg-pmx.ko \
  $(MOD_DIR)/pinctrl-meson-g12a.ko \
  $(MOD_DIR)/pinctrl-meson-axg.ko \
  $(MOD_DIR)/pinctrl-meson-gxl.ko \
  $(MOD_DIR)/pinctrl-meson.ko \
  $(MOD_DIR)/pinctrl-meson-gxbb.ko \
  $(MOD_DIR)/pinctrl-meson8-pmx.ko

# reset
BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
  $(MOD_DIR)/reset-meson.ko \
  $(MOD_DIR)/reset-meson-audio-arb.ko

# misc.
BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
  $(MOD_DIR)/meson-ee-pwrc.ko \
  $(MOD_DIR)/pwm-meson.ko \
  $(MOD_DIR)/pwm-regulator.ko

# SD/eMMC
BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
  $(MOD_DIR)/meson-gx-mmc.ko \
  $(MOD_DIR)/pwrseq_simple.ko \
  $(MOD_DIR)/pwrseq_emmc.ko

#
# ...and only a subset of those to explicitly load, mainly to get
# SD/eMMC up so the main rootfs can be loaded
#
# NOTE: this list is G12/SM1 specific
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD += \
  $(MOD_DIR)/g12a_aoclk.ko \
  $(MOD_DIR)/g12a.ko \
  $(MOD_DIR)/meson-eeclk.ko \
  $(MOD_DIR)/pinctrl-meson-g12a.ko \
  $(MOD_DIR)/reset-meson.ko \
  $(MOD_DIR)/pwm-meson.ko \
  $(MOD_DIR)/pwrseq_simple.ko \
  $(MOD_DIR)/pwrseq_emmc.ko \
  $(MOD_DIR)/meson-gx-mmc.ko

#
# serial console (may be built-in, so check if present)
#
UART_MOD=$(MOD_DIR)/meson_uart.ko
ifneq (,$(wildcard $(UART_MOD)))
BOARD_VENDOR_RAMDISK_KERNEL_MODULES += $(UART_MOD)
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD += $(UART_MOD)
endif

endif
