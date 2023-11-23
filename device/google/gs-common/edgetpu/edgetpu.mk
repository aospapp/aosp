# TPU logging service
PRODUCT_PACKAGES += \
	android.hardware.edgetpu.logging@service-edgetpu-logging
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
# TPU TFlite Delegate
PRODUCT_PACKAGES += \
        libedgetpu_util
# TPU DBA AIDL HAL service
PRODUCT_PACKAGES += com.google.edgetpu.dba-service
# TPU DBA C API library
PRODUCT_PACKAGES += libedgetpu_dba.google

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/edgetpu/sepolicy

# Tflite Darwinn delegate property
PRODUCT_VENDOR_PROPERTIES += vendor.edgetpu.tflite_delegate.force_disable_io_coherency=0

# Edgetpu CPU scheduler property
PRODUCT_VENDOR_PROPERTIES += vendor.edgetpu.cpu_scheduler.policy=FIFO
PRODUCT_VENDOR_PROPERTIES += vendor.edgetpu.cpu_scheduler.priority=99

