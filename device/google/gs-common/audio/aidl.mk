DEVICE_MANIFEST_FILE += device/google/gs-common/audio/aidl/manifest.xml

# Audio HALs
PRODUCT_PACKAGES += \
    android.hardware.audio.service-aidl.aoc

# AIDL software effects. These are the effects supporting in all projects.
# For the project-specific effects, such as haptic generator, please add them
# to makefile in the project's device folder.
PRODUCT_PACKAGES += \
    libvisualizeraidl \
    libbundleaidl \
    libreverbaidl \
    libdynamicsprocessingaidl \
    libloudnessenhanceraidl \
    libdownmixaidl \

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/audio/sepolicy/aidl

include device/google/gs-common/audio/common.mk

DEVICE_PRODUCT_COMPATIBILITY_MATRIX_FILE += device/google/gs-common/audio/aidl/device_framework_matrix_product.xml

PRODUCT_PROPERTY_OVERRIDES += \
       vendor.audio_hal.aidl.enable=true
