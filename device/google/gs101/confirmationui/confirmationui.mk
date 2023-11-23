# Trusty ConfirmationUI HAL
PRODUCT_PACKAGES += \
	android.hardware.confirmationui@1.0-service.trusty.vendor

# Trusty Secure DPU Daemon
PRODUCT_PACKAGES += \
	securedpud.slider

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs101-sepolicy/confirmationui/
