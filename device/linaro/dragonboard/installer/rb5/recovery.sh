#!/bin/bash

INSTALLER_DIR="`dirname ${0}`"

# for cases that don't run "lunch rb5-userdebug"
if [ -z "${ANDROID_BUILD_TOP}" ]; then
    ANDROID_BUILD_TOP="`readlink -f ${INSTALLER_DIR}/../../../../../`"
fi

. "${ANDROID_BUILD_TOP}/device/linaro/dragonboard/vendor-package-ver.sh"

FIRMWARE_DIR="${ANDROID_BUILD_TOP}/vendor/linaro/rb5/${EXPECTED_LINARO_VENDOR_VERSION}"

# TODO: Pull one-time recovery/qdl path out of standard install
# Flash bootloader firmware files
if [ ! -d "${FIRMWARE_DIR}/" ]; then
    echo "RECOVERY: Missing vendor firmware package?"
    echo "          Make sure the vendor binaries have been downloaded from"
    echo "          ${VND_PKG_URL}"
    echo "          and extracted to $ANDROID_BUILD_TOP."
    exit
fi

QDL="`readlink -f ${FIRMWARE_DIR}/qdl/qdl`"

pushd "${FIRMWARE_DIR}/rb5-bootloader-ufs-aosp" > /dev/null
echo "RECOVERY: Running QDL board recovery tool"
sudo ${QDL} prog_firehose_ddr.elf rawprogram?.xml patch?.xml
popd > /dev/null

echo
echo
echo "RECOVERY: Please boot the RB5 into fastboot mode, and use the flash-all-aosp.sh script!"
echo
