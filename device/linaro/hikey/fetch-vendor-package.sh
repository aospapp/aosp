#!/bin/bash
# fetch, check & extract the current vendor package
set -e

DIR_PARENT=$(cd $(dirname $0); pwd)
if [ -z "${ANDROID_BUILD_TOP}" ]; then
    ANDROID_BUILD_TOP=$(cd ${DIR_PARENT}/../../../; pwd)
fi

. "${ANDROID_BUILD_TOP}/device/linaro/hikey/vendor-package-ver.sh"

PKG_FILE=extract-linaro_devices-${EXPECTED_LINARO_VENDOR_VERSION}

pushd ${ANDROID_BUILD_TOP}

# remove the older vendor-package, if any, because upstream
# mesa prebuilts in linaro-vendor-package ain't declared
# within a contained namespace and we can run into build
# errors because of redefinition of mesa prebuilt modules
# from the previous vendor-package.
# FIXME move mesa prebuilts from Android.mk to Android.bp
rm -rf ${ANDROID_BUILD_TOP}/vendor/linaro/

if [ ! -e "${PKG_FILE}.tgz"  ]; then
    curl -L ${VND_PKG_URL} -o  ${PKG_FILE}.tgz
fi

# generate expected sha512sum, check & cleanup
echo "${EXPECTED_LINARO_VENDOR_SHA}  ${PKG_FILE}.tgz" > ${PKG_FILE}.tgz.sha
sha512sum -c ${PKG_FILE}.tgz.sha
rm ${PKG_FILE}.tgz.sha

tar -xf ${PKG_FILE}.tgz
./${PKG_FILE}.sh
popd
