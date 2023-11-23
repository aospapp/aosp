BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/gps/brcm/sepolicy

PRODUCT_SOONG_NAMESPACES += device/google/gs-common/gps/brcm/prebuilt

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
  $(call soong_config_set, SensorHub_debug, assertion, true)
endif

PRODUCT_PACKAGES += \
	bcm47765_gps_package \
	android.hardware.location.gps.prebuilt.xml

PRODUCT_PACKAGES_DEBUG += \
	init.gps_log.rc
