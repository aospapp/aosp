PRODUCT_PACKAGES += battery_mitigation
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
PRODUCT_PACKAGES += BrownoutDetection
endif

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/battery_mitigation/sepolicy
PRODUCT_SOONG_NAMESPACES += device/google/gs-common/battery_mitigation
