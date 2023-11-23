ifneq ($(BOARD_WITHOUT_RADIO),true)
  PRODUCT_PACKAGES += vcd
endif
BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs101-sepolicy/modem/userdebug/
