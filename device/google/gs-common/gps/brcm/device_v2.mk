BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/gps/brcm/sepolicy

PRODUCT_SOONG_NAMESPACES += vendor/broadcom/gps/bcm47765

ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
  $(call soong_config_set, gpsv2, assertion, true)
endif

PRODUCT_PACKAGES += \
	bcm47765_gps_package_v2 \
	android.hardware.location.gps.prebuilt.xml

PRODUCT_PACKAGES_DEBUG += \
	init.gps_log.rc
