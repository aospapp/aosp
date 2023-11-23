PIXEL3_KERNEL_DIR := device/linaro/dragonboard-kernel/pixel3_mainline/

# Inherit the full_base and device configurations
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/linaro/dragonboard/pixel3_mainline/device.mk)
$(call inherit-product, device/linaro/dragonboard/device-common.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)

# Product overrides
PRODUCT_NAME := pixel3_mainline
PRODUCT_DEVICE := pixel3_mainline
PRODUCT_BRAND := Android

ifndef PIXEL3_USES_GKI
PIXEL3_USES_GKI := true
endif

ifeq ($(PIXEL3_USES_GKI), true)
PIXEL3_MODS := $(wildcard $(PIXEL3_KERNEL_DIR)/*.ko)
ifneq ($(PIXEL3_MODS),)
  BOARD_VENDOR_KERNEL_MODULES += $(PIXEL3_MODS)
  P3_ONLY_VENDOR := %/msm.ko %/dwc3.ko %/dwc3-qcom.ko %/hci_uart.ko %/btqca.ko %/incrementalfs.ko
  P3_ONLY_VENDOR += %/ath10k_core.ko %/ath10k_pci.ko %/ath10k_snoc.ko %/ath.ko
  P3_ONLY_VENDOR += %/apr.ko %/qcom_q6v5_ipa_notify.ko
  P3_ONLY_VENDOR += %/ns.ko %/qcom_q6v5.ko %/qrtr.ko %/qcom_q6v5_mss.ko %/qrtr-smd.ko
  P3_ONLY_VENDOR += %/qcom_q6v5_pas.ko %/qrtr-tun.ko %/snd-soc-hdmi-codec.ko
  P3_ONLY_VENDOR += %/qcom_q6v5_wcss.ko
  P3_ONLY_VENDOR += %/rmtfs_mem.ko
  P3_ONLY_VENDOR += %/wcn36xx.ko %/wcnss_ctrl.ko
  P3_ONLY_VENDOR += %/qcom_wcnss_pil.ko %/mdt_loader.ko
  P3_ONLY_VENDOR += %/qcom_q6v5_adsp.ko
  P3_ONLY_VENDOR += %/ehci-hcd.ko %/ehci-pci.ko %/ehci-platform.ko %/xhci-hcd.ko %/xhci-pci.ko %/xhci-pci-renesas.ko %/xhci-plat-hcd.ko
  P3_ONLY_VENDOR += %/lt9611.ko %/panel-tianma-nt36672a.ko %/ax88179_178a.ko %/msm_serial.ko %/asix.ko
  P3_ONLY_VENDOR += %/qcom-wdt.ko %/i2c-qup.ko %/i2c-gpio.ko %/phy-qcom-usb-hs.ko %/ulpi.ko %/extcon-usb-gpio.ko
  P3_ONLY_VENDOR += %/nvmem_qfprom.ko %/pm8916_wdt.ko %/llcc-qcom.ko
  P3_ONLY_VENDOR += %/i2c-qcom-geni.ko %/qcom-pon.ko %/syscon-reboot-mode.ko %/reboot-mode.ko
  P3_ONLY_VENDOR += %/fastrpc.ko %/ohci-hcd.ko %/ohci-pci.ko %/ohci-platform.ko %/phy-qcom-qusb2.ko
  P3_ONLY_VENDOR += %/spmi-pmic-arb.ko %/rtc-pm8xxx.ko %/socinfo.ko
  P3_ONLY_VENDOR += %/smsm.ko %/smp2p.ko %/smem.ko %/qcom_smd.ko %/qcom_glink_smem.ko %/qcom_glink.ko %/qcom_glink_rpm.ko %/qcom_common.ko
  P3_ONLY_VENDOR += %/regmap-spmi.ko %/qcom-spmi-pmic.ko
  P3_ONLY_VENDOR += %/qcom_sysmon.ko
  P3_ONLY_VENDOR += %/qmi_helpers.ko %/pdr_interface.ko
  P3_ONLY_VENDOR += %/icc-bcm-voter.ko %/icc-rpmh.ko %/qnoc-sdm845.ko
  P3_ONLY_VENDOR += %/qcom_gsbi.ko %/pm8941-pwrkey.ko
  P3_ONLY_VENDOR += %/pinctrl-spmi-mpp.ko %/ocmem.ko %/gcc-msm8998.ko %/clk-scmi.ko

  BOARD_VENDOR_RAMDISK_KERNEL_MODULES := $(filter-out $(P3_ONLY_VENDOR),$(PIXEL3_MODS))
endif
endif
