#!/bin/bash

INSTALLER_DIR="`dirname ${0}`"

# for cases that don't run "lunch hikey960-userdebug"
if [ -z "${ANDROID_BUILD_TOP}" ]; then
    ANDROID_BUILD_TOP=${INSTALLER_DIR}/../../../../../
    ANDROID_PRODUCT_OUT="${ANDROID_BUILD_TOP}/out/target/product/hikey960"
fi

if [ ! -d "${ANDROID_PRODUCT_OUT}" ]; then
    echo "error in locating out directory, check if it exist"
    exit
fi

echo "android out dir:${ANDROID_PRODUCT_OUT}"

fastboot flash xloader "${INSTALLER_DIR}"/hisi-sec_xloader.img
fastboot flash ptable "${INSTALLER_DIR}"/hisi-ptable.img
fastboot flash fastboot "${INSTALLER_DIR}"/hisi-fastboot.img
fastboot reboot-bootloader
fastboot flash nvme "${INSTALLER_DIR}"/hisi-nvme.img
fastboot flash fw_lpm3   "${INSTALLER_DIR}"/hisi-lpm3.img
fastboot flash trustfirmware   "${INSTALLER_DIR}"/hisi-bl31.bin
fastboot flash boot "${ANDROID_PRODUCT_OUT}"/boot.img
fastboot flash dts "${ANDROID_PRODUCT_OUT}"/dt.img
fastboot flash super "${ANDROID_PRODUCT_OUT}"/super.img
fastboot flash userdata "${ANDROID_PRODUCT_OUT}"/userdata.img
fastboot format:ext4:10000000 cache
fastboot reboot
