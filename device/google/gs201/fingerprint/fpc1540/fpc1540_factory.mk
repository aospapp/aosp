# Fingerprint
include device/google/gs201/fingerprint/fpc1540/fingerprint_config_factory.mk

PRODUCT_PACKAGES += \
    fpc_tee_test\
    SensorTestTool \

PRODUCT_PACKAGES += \
    com.fingerprints.extension.xml \
    com.fingerprints.extension \
