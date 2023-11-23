# Factory build, use HIDL hal & extension so that we can use Test tool
ifneq ( ,$(findstring factory, $(TARGET_PRODUCT)))
include device/google/gs101/fingerprint/fpc1540/sw42/fingerprint_hidl_config_factory.mk
PRODUCT_PACKAGES += \
    android.hardware.biometrics.fingerprint@2.1-service.fpc \
    fpc_tee_test\
    SensorTestTool \
    ImageTool \
    ImageCollection \
    fp_test \

PRODUCT_PACKAGES += \
    com.fingerprints.extension.xml \
    com.fingerprints.extension \

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.fingerprint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.fingerprint.xml

else
# Non facotry build, use fingerprint AIDL version
include device/google/gs101/fingerprint/fpc1540/sw42/fingerprint_aidl_config.mk

PRODUCT_PACKAGES += \
    android.hardware.biometrics.fingerprint-service.fpc42 \
    fingerprint.fpc \

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.fingerprint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.fingerprint.xml

endif
