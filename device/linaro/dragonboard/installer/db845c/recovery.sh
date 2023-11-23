#!/bin/bash

INSTALLER_DIR="`dirname ${0}`"

QDL="`readlink -f ${INSTALLER_DIR}/qdl`"
FIRMWARE_DIR="dragonboard-845c-bootloader-ufs-aosp"

# for cases that don't run "lunch db845c-userdebug"
if [ -z "${ANDROID_BUILD_TOP}" ]; then
    ANDROID_BUILD_TOP=${INSTALLER_DIR}/../../../../../
    ANDROID_PRODUCT_OUT="${ANDROID_BUILD_TOP}/out/target/product/db845c"
fi

if [ ! -d "${ANDROID_PRODUCT_OUT}" ]; then
    echo "RECOVERY: error in locating out directory, check if it exist"
    exit
fi

echo "android out dir:${ANDROID_PRODUCT_OUT}"

# TODO: Pull one-time recovery/qdl path out of standard install
# Flash bootloader firmware files
if [ ! -d "${INSTALLER_DIR}/${FIRMWARE_DIR}/" ]; then
    echo "RECOVERY: No firmware directory? Make sure binaries have been provided"
    exit
fi

pushd "${INSTALLER_DIR}/${FIRMWARE_DIR}" > /dev/null
sudo "${QDL}" prog_firehose_ddr.elf rawprogram[012345].xml patch[012345].xml
popd > /dev/null

echo
echo
echo "RECOVERY: Please boot the db845c into fastboot mode, and use the flash-all-aosp.sh script!"
echo
