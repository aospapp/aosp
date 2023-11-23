#!/bin/bash

export EXPECTED_LINARO_VENDOR_VERSION=20220210
export EXPECTED_LINARO_VENDOR_SHA=75efc8471f299f64716140712c0785b8e8aeaf5aa7e389a6f12f78ad4962420740da32f4535d795006e78c5f0b77a1cee8c168192a87f668103c00d87d480e6d
export VND_PKG_URL=https://releases.linaro.org/android/aosp-linaro-vendor-package/extract-linaro_devices-20220210.tgz

if [ "$1" = "url" ]; then
 echo $VND_PKG_URL
elif [ "$1" = "ver" ]; then
 echo $EXPECTED_LINARO_VENDOR_VERSION
elif [ "$1" = "sha" ]; then
 echo $EXPECTED_LINARO_VENDOR_SHA
fi
