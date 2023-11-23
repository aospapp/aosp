# Config variables for TPU chip on device.
$(call soong_config_set,edgetpu_config,chip,abrolhos)

# TPU logging service
PRODUCT_PACKAGES += \
	android.hardware.edgetpu.logging@service-edgetpu-logging

# TPU firmware
PRODUCT_PACKAGES += \
	edgetpu-abrolhos.fw

# TPU NN AIDL HAL
PRODUCT_PACKAGES += \
	android.hardware.neuralnetworks@service-darwinn-aidl

# TPU application service
PRODUCT_PACKAGES += \
	vendor.google.edgetpu_app_service@1.0-service

# TPU vendor service
PRODUCT_PACKAGES += \
	vendor.google.edgetpu_vendor_service@1.0-service

# TPU HAL client library
PRODUCT_PACKAGES += \
	libedgetpu_client.google

# TPU metrics logger library
PRODUCT_PACKAGES += \
	libmetrics_logger

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs101-sepolicy/edgetpu/
