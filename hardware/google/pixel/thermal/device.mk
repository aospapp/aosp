PRODUCT_PACKAGES += \
    android.hardware.thermal-service.pixel

# Thermal utils
PRODUCT_PACKAGES += \
    thermal_symlinks

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += \
    thermal_logd
endif

BOARD_SEPOLICY_DIRS += hardware/google/pixel-sepolicy/thermal
