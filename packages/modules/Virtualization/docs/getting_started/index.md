# Getting started with Protected Virtual Machines

## Prepare a device

First you will need a device that is capable of running virtual machines. On arm64, this means a
device which boots the kernel in EL2 and the kernel was built with KVM enabled. Unfortunately at the
moment, we don't have an arm64 device in AOSP which does that. Instead, use cuttlefish which
provides the same functionalities except that the virtual machines are not protected from the host
(i.e. Android). This however should be enough for functional testing.

We support the following device:

* aosp_cf_x86_64_phone (Cuttlefish a.k.a. Cloud Android)
* oriole (Pixel 6)
* raven (Pixel 6 Pro)

### Cuttlefish

Building Cuttlefish

```shell
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-userdebug
m
```

Run Cuttlefish locally by

```shell
acloud create --local-instance --local-image
```

### Pixel 6 and 6 Pro

If the device is running Android 12, join the [Android Beta
Program](https://www.google.com/android/beta) to upgrade to Android 13 Beta.

Once upgraded to Android 13, execute the following command to enable pKVM.

```shell
adb reboot bootloader
fastboot flashing unlock
fastboot oem pkvm enable
fastboot reboot
```

Due to a bug in Android 13 for these devices, pKVM may stop working after an
[OTA update](https://source.android.com/devices/tech/ota). To prevent this, it
is necessary to manually replicate the `pvmfw` partition to the other slot:

```shell
git -C <android_root>/packages/modules/Virtualization
show de6b0b2ecf6225a0a7b43241de27e74fc3e6ceb2:pvmfw/pvmfw.img > /tmp/pvmfw.img
adb reboot bootloader
fastboot --slot other flash pvmfw /tmp/pvmfw.img
fastboot reboot
```

Otherwise, if an OTA has already made pKVM unusable, the working partition
should be copied to the "current" slot:

```shell
adb reboot bootloader
fastboot flash pvmfw /tmp/pvmfw.img
fastboot reboot
```

Finally, if the `pvmfw` partition has been corrupted, both slots may be flashed
using the [`pvmfw.img` pre-built](https://android.googlesource.com/platform/packages/modules/Virtualization/+/08deac98acefd62e222edfa374d5292458cf97eb%5E/pvmfw/pvmfw.img)
as long as the bootloader remains unlocked. Otherwise, a fresh install of
Android 13 followed by the manual steps above for flashing the `other` slot
should be used as a last resort.

Starting in Android 14, `pvmfw.img` can be built using the Android Build system:
```
lunch <target>  # where PRODUCT_BUILD_PVMFW_IMAGE=true
m pvmfwimage    # partition image under ${ANDROID_PRODUCT_OUT}/pvmfw.img
```
Note that the result is not intended to be used in Android 13 as not
backward-compatibility is guaranteed.

## Running demo app

The instruction is [here](../../demo/README.md).

## Running tests

There are various tests that spawn guest VMs and check different aspects of the architecture. They
all can run via `atest`.

```shell
atest VirtualizationTestCases.64
atest MicrodroidHostTestCases
atest MicrodroidTestApp
```

If you run into problems, inspect the logs produced by `atest`. Their location is printed at the
end. The `host_log_*.zip` file should contain the output of individual commands as well as VM logs.

## Spawning your own VMs with custom kernel

You can spawn your own VMs by passing a JSON config file to the VirtualizationService via the `vm`
tool on a rooted KVM-enabled device. If your device is attached over ADB, you can run:

```shell
cat > vm_config.json
{
  "kernel": "/data/local/tmp/kernel",
  "initrd": "/data/local/tmp/ramdisk",
  "params": "rdinit=/bin/init"
}
adb root
adb push <kernel> /data/local/tmp/kernel
adb push <ramdisk> /data/local/tmp/ramdisk
adb push vm_config.json /data/local/tmp/vm_config.json
adb shell "start virtualizationservice"
adb shell "/apex/com.android.virt/bin/vm run /data/local/tmp/vm_config.json"
```

The `vm` command also has other subcommands for debugging; run `/apex/com.android.virt/bin/vm help`
for details.

## Spawning your own VMs with custom pvmfw

Set system property `hypervisor.pvmfw.path` to custom `pvmfw` on the device before using `vm` tool.
`virtualizationservice` will pass the specified `pvmfw` to `crosvm` for protected VMs.

```shell
adb push pvmfw.img /data/local/tmp/pvmfw.img
adb root  # required for setprop
adb shell setprop hypervisor.pvmfw.path /data/local/tmp/pvmfw.img
```

## Spawning your own VMs with Microdroid

[Microdroid](../../microdroid/README.md) is a lightweight version of Android that is intended to run
on pVM. You can run a Microdroid with empty payload using the following command:

```shell
adb shell /apex/com.android.virt/bin/vm run-microdroid --debug full
```

The `instance.img` and `apk.idsig` files will be stored in a subdirectory under
`/data/local/tmp/microdroid`, that `vm` will create.

Atlernatively, you can manually run the demo app on top of Microdroid as follows:

```shell
UNBUNDLED_BUILD_SDKS_FROM_SOURCE=true TARGET_BUILD_APPS=MicrodroidDemoApp m apps_only dist
adb shell mkdir -p /data/local/tmp/virt
adb push out/dist/MicrodroidDemoApp.apk /data/local/tmp/virt/
adb shell /apex/com.android.virt/bin/vm run-app \
  --debug full \
  /data/local/tmp/virt/MicrodroidDemoApp.apk \
  /data/local/tmp/virt/MicrodroidDemoApp.apk.idsig \
  /data/local/tmp/virt/instance.img \
  --payload-path MicrodroidTestNativeLib.so
```

## Building and updating CrosVM and VirtualizationService {#building-and-updating}

You can update CrosVM and the VirtualizationService by updating the `com.android.virt` APEX instead
of rebuilding the entire image.

```shell
banchan com.android.virt aosp_arm64   // or aosp_x86_64 if the device is cuttlefish
UNBUNDLED_BUILD_SDKS_FROM_SOURCE=true m apps_only dist
adb install out/dist/com.android.virt.apex
adb reboot
```

## Building and updating kernel inside Microdroid

The instruction is [here](../../microdroid/kernel/README.md).
