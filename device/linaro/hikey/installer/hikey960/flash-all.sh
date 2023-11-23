#!/bin/bash

INSTALLER_DIR="`dirname ${0}`"
ECHO_PREFIX="=== "

# for cases that don't run "lunch hikey960-userdebug"
if [ -z "${ANDROID_BUILD_TOP}" ]; then
    ANDROID_BUILD_TOP=$(cd ${INSTALLER_DIR}/../../../../../; pwd)
    ANDROID_PRODUCT_OUT="${ANDROID_BUILD_TOP}/out/target/product/hikey960"
fi

if [ ! -d "${ANDROID_PRODUCT_OUT}" ]; then
    echo ${ECHO_PREFIX}"error in locating out directory, check if it exist"
    exit
fi

echo ${ECHO_PREFIX}"android out dir:${ANDROID_PRODUCT_OUT}"

. "${ANDROID_BUILD_TOP}/device/linaro/hikey/vendor-package-ver.sh"

VENDOR_DIR=$ANDROID_BUILD_TOP/vendor/linaro/hikey960/${EXPECTED_LINARO_VENDOR_VERSION}/

# TODO: Pull one-time recovery/qdl path out of standard install
# Flash bootloader firmware files
if [ ! -d "${VENDOR_DIR}/" ]; then
    echo "FLASH-ALL-AOSP: Missing vendor firmware package?"
    echo "                Make sure the vendor binaries have been downloaded from"
    echo "                ${VND_PKG_URL}"
    echo "                and extracted to $ANDROID_BUILD_TOP."
    exit
fi

pushd $VENDOR_DIR/bootloader/

function check_partition_table_version () {
	fastboot erase reserved
	if [ $? -eq 0 ]
	then
		IS_PTABLE_1MB_ALIGNED=true
	else
		IS_PTABLE_1MB_ALIGNED=false
	fi
}

function flashing_atf_uefi () {
	fastboot flash ptable prm_ptable.img
	fastboot flash xloader hisi-sec_xloader.img
	fastboot reboot-bootloader

	fastboot flash fastboot l-loader.bin
	fastboot flash fip fip.bin
	fastboot flash nvme hisi-nvme.img
	fastboot flash fw_lpm3 hisi-lpm3.img
	fastboot flash trustfirmware hisi-bl31.bin
	fastboot reboot-bootloader

	fastboot flash ptable prm_ptable.img
	fastboot flash xloader hisi-sec_xloader.img
	fastboot flash fastboot l-loader.bin
	fastboot flash fip fip.bin

	fastboot flash boot "${ANDROID_PRODUCT_OUT}"/boot.img
	fastboot flash super "${ANDROID_PRODUCT_OUT}"/super.img
	fastboot flash userdata "${ANDROID_PRODUCT_OUT}"/userdata.img
	fastboot format cache
}

function upgrading_ptable_1mb_aligned () {
	fastboot flash xloader hisi-sec_xloader.img
	fastboot flash ptable hisi-ptable.img
	fastboot flash fastboot hisi-fastboot.img
	fastboot reboot-bootloader
}

echo ${ECHO_PREFIX}"Checking partition table version..."
check_partition_table_version

if [ "${IS_PTABLE_1MB_ALIGNED}" == "true" ]
then
	echo ${ECHO_PREFIX}"Partition table is 1MB aligned. Flashing ATF/UEFI..."
	flashing_atf_uefi
else
	echo ${ECHO_PREFIX}"Partition table is 512KB aligned."
	echo ${ECHO_PREFIX}"Upgrading to 1MB aligned version..."
	upgrading_ptable_1mb_aligned
	echo ${ECHO_PREFIX}"Flashing ATF/UEFI..."
	flashing_atf_uefi
	echo ${ECHO_PREFIX}"Done"
fi

fastboot reboot
popd
