# Copyright 2011, 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


DEVICE_DIR=device/linaro/hikey/
DEVICE=hikey960
PRODUCT=hikey960

BUILD=eng.`whoami`
BUILDNAME=`ls ${ANDROID_BUILD_TOP}/${PRODUCT}-img-${BUILD}.zip 2> /dev/null`
if [ $? -ne 0 ]; then
  VERSION=linaro-`date +"%Y.%m.%d"`
else
  BUILDNAME=`ls ${ANDROID_BUILD_TOP}/${PRODUCT}-img-*.zip 2> /dev/null`
  BUILD=`basename ${BUILDNAME} | cut -f3 -d'-' | cut -f1 -d'.'`
  VERSION=$BUILD
fi

# Prepare the staging directory
rm -rf tmp
mkdir -p tmp/$PRODUCT-$VERSION

# copy over bootloader binaries
cp $ANDROID_BUILD_TOP/$DEVICE_DIR/installer/hikey960/hisi-bl31.bin tmp/$PRODUCT-$VERSION/
cp $ANDROID_BUILD_TOP/$DEVICE_DIR/installer/hikey960/hisi-fastboot.img tmp/$PRODUCT-$VERSION/
cp $ANDROID_BUILD_TOP/$DEVICE_DIR/installer/hikey960/hisi-lpm3.img tmp/$PRODUCT-$VERSION/
cp $ANDROID_BUILD_TOP/$DEVICE_DIR/installer/hikey960/hisi-nvme.img tmp/$PRODUCT-$VERSION/
cp $ANDROID_BUILD_TOP/$DEVICE_DIR/installer/hikey960/hisi-ptable.img tmp/$PRODUCT-$VERSION/
cp $ANDROID_BUILD_TOP/$DEVICE_DIR/installer/hikey960/hisi-sec_xloader.img tmp/$PRODUCT-$VERSION/
cp $ANDROID_BUILD_TOP/$DEVICE_DIR/installer/hikey960/README tmp/$PRODUCT-$VERSION/

# copy over dts.img
cp $ANDROID_BUILD_TOP/out/target/product/hikey960/dt.img tmp/$PRODUCT-$VERSION/

# copy over the update image
cp ${SRCPREFIX}$PRODUCT-img-$BUILD.zip tmp/$PRODUCT-$VERSION/image-$PRODUCT-$VERSION.zip

# XXX hikey960's fastboot update currently doesn't format cache/userdata, so do it manually
# XXX Remove this when the bug is fixed.
cp $ANDROID_BUILD_TOP/out/target/product/hikey960/cache.img tmp/$PRODUCT-$VERSION/
cp $ANDROID_BUILD_TOP/out/target/product/hikey960/userdata.img tmp/$PRODUCT-$VERSION/


# Write flash-all.sh
cat > tmp/$PRODUCT-$VERSION/flash-all.sh << EOF
#!/bin/bash

# Copyright 2012, 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.



fastboot flash xloader		hisi-sec_xloader.img
fastboot flash ptable		hisi-ptable.img
fastboot flash fastboot		hisi-fastboot.img
fastboot reboot-bootloader
fastboot flash nvme		hisi-nvme.img
fastboot flash fw_lpm3		hisi-lpm3.img
fastboot flash trustfirmware	hisi-bl31.bin
fastboot flash dts		dt.img

# XXX fastboot update doesn't format cache and userdata
# XXX so flash those manually. Remove this later.
fastboot flash cache		cache.img
fastboot flash userdata		userdata.img

fastboot update image-$PRODUCT-$VERSION.zip
EOF

chmod a+x tmp/$PRODUCT-$VERSION/flash-all.sh

# Create the distributable package
(cd tmp ; zip -r ../$PRODUCT-$VERSION-factory.zip $PRODUCT-$VERSION)
mv $PRODUCT-$VERSION-factory.zip $PRODUCT-$VERSION-factory-$(sha256sum < $PRODUCT-$VERSION-factory.zip | cut -b -8).zip

# Clean up
rm -rf tmp
