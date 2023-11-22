PRODUCT_KERNEL_VERSION := 4.4

PRODUCT_PROPERTY_OVERRIDES += \
       vendor.rild.libpath=/vendor/lib64/libgoldfish-ril.so

# Note: the following lines need to stay at the beginning so that it can
# take priority  and override the rules it inherit from other mk files
# see copy file rules in core/Makefile
PRODUCT_SDK_ADDON_COPY_FILES += \
    device/generic/goldfish/data/etc/advancedFeatures.ini.arm:images/arm64-v8a/advancedFeatures.ini \
    prebuilts/qemu-kernel/arm64/$(PRODUCT_KERNEL_VERSION)/kernel-qemu2:images/arm64-v8a/kernel-ranchu

PRODUCT_COPY_FILES += \
    device/generic/goldfish/fstab.ranchu.initrd.arm:$(TARGET_COPY_OUT_RAMDISK)/fstab.ranchu \
    device/generic/goldfish/manifest-arm.xml:$(TARGET_COPY_OUT_VENDOR)/manifest.xml \
    device/generic/goldfish/data/etc/advancedFeatures.ini.arm:advancedFeatures.ini \
    prebuilts/qemu-kernel/arm64/$(PRODUCT_KERNEL_VERSION)/kernel-qemu2:kernel-ranchu \
    device/generic/goldfish/fstab.ranchu.arm:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.ranchu
EMULATOR_VENDOR_NO_GNSS := true

