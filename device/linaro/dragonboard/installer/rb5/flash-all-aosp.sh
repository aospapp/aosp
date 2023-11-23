#!/bin/bash

INSTALLER_DIR="`dirname ${0}`"

# for cases that don't run "lunch rb5-userdebug"
if [ -z "${ANDROID_BUILD_TOP}" ]; then
    ANDROID_BUILD_TOP="`readlink -f ${INSTALLER_DIR}/../../../../../`"
    ANDROID_PRODUCT_OUT="${ANDROID_BUILD_TOP}/out/target/product/rb5"
fi

if [ ! -d "${ANDROID_PRODUCT_OUT}" ]; then
    echo "FLASH-ALL-AOSP: error in locating ${ANDROID_PRODUCT_OUT}/ directory, check if it exist"
    exit
fi

. "${ANDROID_BUILD_TOP}/device/linaro/dragonboard/vendor-package-ver.sh"

FIRMWARE_DIR="${ANDROID_BUILD_TOP}/vendor/linaro/rb5/${EXPECTED_LINARO_VENDOR_VERSION}"

# TODO: Pull one-time recovery/qdl path out of standard install
# Flash bootloader firmware files
if [ ! -d "${FIRMWARE_DIR}/" ]; then
    echo "FLASH-ALL-AOSP: Missing vendor firmware package?"
    echo "                Make sure the vendor binaries have been downloaded from"
    echo "                ${VND_PKG_URL}"
    echo "                and extracted to $ANDROID_BUILD_TOP."
    exit
fi

pushd "${FIRMWARE_DIR}/rb5-bootloader-ufs-aosp" > /dev/null
echo "FLASH-ALL-AOSP: Flash bootloader images"
./flashall
popd > /dev/null

echo "android out dir:${ANDROID_PRODUCT_OUT}"

echo "FLASH-ALL-AOSP: Flash boot img"
fastboot flash boot "${ANDROID_PRODUCT_OUT}"/boot.img
echo "FLASH-ALL-AOSP: Flash super/dynamic image"
fastboot flash super "${ANDROID_PRODUCT_OUT}"/super.img
echo "FLASH-ALL-AOSP: Flash userdata image"
fastboot flash userdata "${ANDROID_PRODUCT_OUT}"/userdata.img
echo "FLASH-ALL-AOSP: Flash vendor_boot image"
fastboot flash vendor_boot "${ANDROID_PRODUCT_OUT}"/vendor_boot.img
echo "FLASH-ALL-AOSP: Formatting metadata"
fastboot format:ext4 metadata

echo "FLASH-ALL-AOSP: Rebooting"
fastboot reboot

echo "FLASH-ALL-AOSP: Updating lt9611uxc firmware version"
echo "                Waiting for adb.."
echo
adb wait-for-device
VERSION=`adb shell su 0 cat /sys/bus/i2c/devices/5-002b/lt9611uxc_firmware`
if [ "$VERSION" -lt "43" ] ; then
    echo "FLASH-ALL-AOSP: lt9611uxc 5-002b: Updating firmware... May take up to 120 seconds. Do not switch off the device"
    adb shell "echo 1 | su 0 tee /sys/bus/i2c/devices/5-002b/lt9611uxc_firmware > /dev/null"
    echo "FLASH-ALL-AOSP: lt9611uxc 5-002b: Firmware updates successfully"
    echo "FLASH-ALL-AOSP: Rebooting"
    adb reboot
fi
echo "FLASH-ALL-AOSP: Done"
