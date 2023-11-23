ifeq ($(LOCAL_PREFER_VENDOR_APEX),true)
PRODUCT_PACKAGES += com.google.pixel.vibrator.hal
else
PRODUCT_PACKAGES += android.hardware.vibrator-service.cs40l26
endif

BOARD_SEPOLICY_DIRS += \
    hardware/google/pixel-sepolicy/vibrator/common \
    hardware/google/pixel-sepolicy/vibrator/cs40l26
