#!/bin/bash

export EXPECTED_LINARO_VENDOR_VERSION=20220303
#make sure to use sha512sum here
export EXPECTED_LINARO_VENDOR_SHA=1119c59e80094fc11624b19531f584e798fd048f0adfdbb5113d2539202898c7d2b13dc1d2ad679c555f1c79a64d3ffc1b45cdf414787e5a821499276ca0b36c
export VND_PKG_URL=https://releases.linaro.org/android/aosp-linaro-vendor-package/extract-linaro_devices-20220303.tgz

if [ "$1" = "url" ]; then
 echo $VND_PKG_URL
elif [ "$1" = "ver" ]; then
 echo $EXPECTED_LINARO_VENDOR_VERSION
elif [ "$1" = "sha" ]; then
 echo $EXPECTED_LINARO_VENDOR_SHA
fi
