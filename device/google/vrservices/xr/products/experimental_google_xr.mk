PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.dvr.lens_metrics=/etc/hmd_config

# SELinux permissions
BOARD_PLAT_PRIVATE_SEPOLICY_DIR := device/google/vrservices/xr/sepolicy

# Remove non-critical and non-XR packages from PRODUCT_PACKAGES.
#
# Overrides (i.e. removes) packages that are bundled into the system/product
# image for smartphone use cases. We are removing those packages for two
# reasons:
# 1) Pixel devices' system/product image are almost out of disk spaces. It has
#    been hard for us to bundle the AIO flavored VrCore into the their system
#    image. Removing some of the packages free up enough disk spaces for XR use
#    cases.
# 2) Removing those packages won't impact the functionality of the device. More
#    specifically, those package meet the following requirements:
#    i) they are not critical packages for XR use cases; and ii) can still be
#    install from Play Store if ever needed.
#    For certain packages, removing those packages are actually beneficial. For
#    example, the WallpapersBReel201* packages introduced unnecessary GPU load
#    for the system. Disabling those packages frees some GPU resources to XR use
#    cases and improves the accuracy of our GPU performance profiling.

# External camera libraries.
# There is no need to add extra SELinux policy for external cameras
# because our devices do not run Trebel passthrough mode.
PRODUCT_PACKAGES += android.hardware.camera.provider@2.4-impl
PRODUCT_PACKAGES += android.hardware.camera.provider@2.4-external-service
# Use webcam camera device@3.5
PRODUCT_PROPERTY_OVERRIDES += ro.vendor.camera.external.hal3TrebleMinorVersion=5

PRODUCT_PACKAGES += NonXrProductPackagesRemover

PRODUCT_PACKAGE_OVERLAYS := device/google/vrservices/xr/overlay

PRODUCT_COPY_FILES += \
    device/google/vrservices/xr/init/init.xr.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/init.xr.rc \
    device/google/vrservices/xr/scripts/boot-to-vr.sh:$(TARGET_COPY_OUT_SYSTEM)/bin/boot-to-vr.sh \
    frameworks/native/data/etc/android.hardware.vr.high_performance.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/android.hardware.vr.high_performance.xml \
    vendor/unbundled_google/packages/PrebuiltGoogleVr/configs/daydream_viewer_config:$(TARGET_COPY_OUT_SYSTEM)/etc/hmd_config \

# XR/VR prebuilt packages
PRODUCT_PACKAGES += \
    SetupWizardOverlay \
    SetupWizardOverlayXr \
    VrHome \
    VrInputMethodIme \
    VrHeadsetPowerPolicy \
    pps-tool.sh \
    BluetoothQtiSymlink \

