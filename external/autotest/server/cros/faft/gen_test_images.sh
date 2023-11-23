#!/bin/sh
# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

# gen_test_images.sh BOARD IMAGE
# Generate test images from any premp/mp signed image.

set -e

BOARD=$1
IMAGE=$(readlink -f $2)
KEY=~/trunk/src/platform/ec/board/${BOARD}/dev_key.pem
# Increment to different rollback versions
ROLLBACK0=00000000
ROLLBACK1=01000000
ROLLBACK9=09000000

rm -rf images
mkdir images
cd images

# Use original image for some tests.
cp $IMAGE ${BOARD}.bin

# Generate dev key set
futility create --desc="${BOARD} dev key" $KEY key

# Pick up RO and RW version (only take up to 27 bytes, to leave an extra
# 4 bytes for .dev/.rbX tag, and terminating \0.
ro_version_offset=`dump_fmap $IMAGE RO_FRID | sed -n 's/area_offset: *//p'`
ro_version=`dd if=$IMAGE bs=1 skip=$((ro_version_offset)) count=27`
rw_version_offset=`dump_fmap $IMAGE RW_FWID | sed -n 's/area_offset: *//p'`
rw_version=`dd if=$IMAGE bs=1 skip=$((rw_version_offset)) count=27`

# Hack the version string
cp $IMAGE ${BOARD}.dev
printf "${ro_version}.dev" | dd of=${BOARD}.dev bs=1 seek=$((ro_version_offset)) count=32 conv=notrunc
printf "${rw_version}.dev" | dd of=${BOARD}.dev bs=1 seek=$((rw_version_offset)) count=32 conv=notrunc

# Resign the image with dev key
echo "Generating image signed with dev keys:"
~/trunk/src/platform/vboot_reference/scripts/image_signing/sign_official_build.sh accessory_rwsig ${BOARD}.dev .  ${BOARD}.dev.out
mv ${BOARD}.dev.out ${BOARD}.dev

# Show signature
futility show ${BOARD}.dev

echo "Generating image with rollback = 0:"

printf "Current rollback version: "
rb_offset=`dump_fmap ${BOARD}.dev RW_RBVER | sed -n 's/area_offset: *//p'`
dd if=${BOARD}.dev bs=1 skip=$((rb_offset)) count=4 2>/dev/null | xxd -l 4 -p

cp ${BOARD}.dev ${BOARD}.dev.rb0
# Decrement rollback to 0
echo $ROLLBACK0 | xxd -g 4 -p -r | dd of=${BOARD}.dev.rb0 bs=1 seek=$((rb_offset)) count=4 conv=notrunc
# Hack the version string
printf "${rw_version}.rb0" | dd of=${BOARD}.dev.rb0 bs=1 seek=$((rw_version_offset)) count=32 conv=notrunc
# Resign the image with dev key
~/trunk/src/platform/vboot_reference/scripts/image_signing/sign_official_build.sh accessory_rwsig ${BOARD}.dev.rb0 . ${BOARD}.dev.rb0.out
mv ${BOARD}.dev.rb0.out ${BOARD}.dev.rb0


echo "Generating image with rollback = 1:"

printf "Current rollback version: "
rb_offset=`dump_fmap ${BOARD}.dev RW_RBVER | sed -n 's/area_offset: *//p'`
dd if=${BOARD}.dev bs=1 skip=$((rb_offset)) count=4 2>/dev/null | xxd -l 4 -p

cp ${BOARD}.dev ${BOARD}.dev.rb1
# Increment rollback to 1
echo $ROLLBACK1 | xxd -g 4 -p -r | dd of=${BOARD}.dev.rb1 bs=1 seek=$((rb_offset)) count=4 conv=notrunc
# Hack the version string
printf "${rw_version}.rb1" | dd of=${BOARD}.dev.rb1 bs=1 seek=$((rw_version_offset)) count=32 conv=notrunc
# Resign the image with dev key
~/trunk/src/platform/vboot_reference/scripts/image_signing/sign_official_build.sh accessory_rwsig ${BOARD}.dev.rb1 . ${BOARD}.dev.rb1.out
mv ${BOARD}.dev.rb1.out ${BOARD}.dev.rb1

echo "Generating image with rollback = 9:"

printf "Current rollback version: "
rb_offset=`dump_fmap ${BOARD}.dev RW_RBVER | sed -n 's/area_offset: *//p'`
dd if=${BOARD}.dev bs=1 skip=$((rb_offset)) count=4 2>/dev/null | xxd -l 4 -p

cp ${BOARD}.dev ${BOARD}.dev.rb9
# Increment rollback to 9
echo $ROLLBACK9 | xxd -g 4 -p -r | dd of=${BOARD}.dev.rb9 bs=1 seek=$((rb_offset)) count=4 conv=notrunc
# Hack the version string
printf "${rw_version}.rb9" | dd of=${BOARD}.dev.rb9 bs=1 seek=$((rw_version_offset)) count=32 conv=notrunc
# Resign the image with dev key
~/trunk/src/platform/vboot_reference/scripts/image_signing/sign_official_build.sh accessory_rwsig ${BOARD}.dev.rb9 . ${BOARD}.dev.rb9.out
mv ${BOARD}.dev.rb9.out ${BOARD}.dev.rb9


echo "Generating image with bits corrupted at start of image:"
cp $IMAGE ${BOARD}_corrupt_first_byte.bin
offset=`dump_fmap ${BOARD}_corrupt_first_byte.bin EC_RW | sed -n 's/area_offset: *//p'`
dd if=/dev/random of=${BOARD}_corrupt_first_byte.bin bs=1 seek=$((offset+100)) count=1 conv=notrunc

echo "Generating image with bits corrupted at end of image:"
cp $IMAGE ${BOARD}_corrupt_last_byte.bin
offset=`dump_fmap ${BOARD}_corrupt_last_byte.bin SIG_RW | sed -n 's/area_offset: *//p'`
dd if=/dev/zero of=${BOARD}_corrupt_last_byte.bin bs=1 seek=$((offset-100)) count=1 conv=notrunc

# hexdumps are always nice to have to do diffs
for image in ${BOARD}.bin ${BOARD}_corrupt_first_byte.bin ${BOARD}_corrupt_last_byte.bin \
          ${BOARD}.dev ${BOARD}.dev.rb0 ${BOARD}.dev.rb1 ${BOARD}.dev.rb9; do
        hexdump -C $image > $image.hex
done

