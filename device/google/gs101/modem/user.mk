ifneq ($(BOARD_WITHOUT_RADIO),true)
  PRODUCT_PACKAGES += dmd
endif
BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs101-sepolicy/modem/user
