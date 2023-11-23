# Instructions for building custom AVF on Pixel 6 or 6 Pro

This document provides steps for building AVF from AOSP, and then install it to
Pixel 6 series to better understand AVF and do some experiments.

**WARNING**: Unless Android 13 is released to AOSP (expected to be at Summer
2022, exact date TBD) by the time when you read this documentation, or you or
your company have early access to Android Tiramisu source tree, you **CANNOT**
follow this instruction. In that case, you can only **USE** the AVF that is
shipped in the Android 13 Beta Image.

This is because AVF in the beta image is signed by Google and therefore it can't
be updated to a new AVF built in AOSP which can't be signed by the Google key
that is not shared with AOSP.

## Upgrade to Android 13 Beta Image

First, upgrade your Pixel 6 or Pixel 6 Pro to the Android 13 Beta Image. This
can be done in two ways:

* Join [Android Beta Program](https://www.google.com/android/beta) and then OTA
  to Android 13.
* Manually flash [Android 13 Beta Images](https://developer.android.com/about/versions/13/download#factory-images).

Then enable ADB debugging in "Settings" -> "System" -> "Developer options".
Finally, enable PKVM.

```shell
adb reboot bootloader
fastboot flashing unlock
fastboot oem pkvm enable
fastboot reboot
```

## Building GSI and flashing it

Prepare your Android 13 (Tiramisu) source tree.

```shell
mkdir tm
cd tm
repo init -u <URL> -m <your_tm_branch>
repo sync -c --no-tags -j 10
```

Patch GSI so that it includes AVF. Edit
`build/make/target/product/gsi_release.mk` and add the following line to the
end (or anywhere in the file that makes sense):

```
PRODUCT_PACKAGES += com.android.virt
```

Build GSI.

```shell
source build/envsetup.sh
choosecombo 1 aosp_arm64 userdebug
m
```

Flash GSI to the Pixel device.

```shell
adb reboot bootloader
fastboot reboot fastboot
fastboot delete-logical-partition product_a
fastboot flash system out/target/product/generic_arm64/system.img
fastboot --disable-verification flash vbmeta out/target/product/generic_arm64/vbmeta.img
fastboot -w reboot
```

Deleting the logical partition `product_a` is needed because the GSI image is
bigger than the logical partition `system_a` of the beta image.
`--disable-verification` when flashing the `vbmeta` partition is critical. Don't
miss it.

Lastly, check if you are running GSI.

```shell
adb shell getprop ro.build.product
adb shell ls /dev/kvm
adb shell ls /apex/com.android.virt/bin/vm
```

The result should be as follows.

```
generic_arm64
/dev/kvm
/apex/com.android.virt/bin/vm
```

## Building and installing AVF from AOSP

Checkout AOSP master branch.

```shell
mkdir aosp
cd aosp
repo init -u https://android.googlesource.com/platform/manifest -b master
repo sync -c --no-tags -j 10
```

Then build the `com.android.virt` APEX.

```shell
source build/envsetup.sh
banchan com.android.virt aosp_arm64
UNBUNDLED_BUILD_SDKS_FROM_SOURCE=true m apps_only dist
```

Install the newly built AVF to the device

```shell
adb install out/dist/com.android.virt.apex
adb reboot
```

If this doesn't work for some reason, try this:

```
adb root
adb shell setenforce 0
adb push out/dist/com.android.virt.apex /data/local/
adb shell cmd -w apexservice deactivatePackage /system/system_ext/apex/com.android.virt.apex
adb shell cmd -w apexservice activatePackage /data/local/com.android.virt.apex
// Don't adb reboot
```
