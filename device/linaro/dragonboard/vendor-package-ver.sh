#!/bin/bash

export EXPECTED_LINARO_VENDOR_VERSION=20221126
#make sure to use sha512sum here
export EXPECTED_LINARO_VENDOR_SHA=b87346f0612809458f556d0770f32a542ffd200418fe89bf1bf11b250f8c9197cd7c3624c87d141837c315a308d166129f711cf8c7b0a31eafcc1c2b87556199
export VND_PKG_URL=https://releases.linaro.org/android/aosp-linaro-vendor-package/extract-linaro_devices-20221126.tgz

if [ "$1" = "url" ]; then
 echo $VND_PKG_URL
elif [ "$1" = "ver" ]; then
 echo $EXPECTED_LINARO_VENDOR_VERSION
elif [ "$1" = "sha" ]; then
 echo $EXPECTED_LINARO_VENDOR_SHA
fi
