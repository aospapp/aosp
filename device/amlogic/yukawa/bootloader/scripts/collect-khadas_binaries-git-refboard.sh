#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset

set -o xtrace

# The goal of this script is gather all binaries provides by AML in order to generate
# our final u-boot image from the u-boot.bin (bl33)
#
# Some binaries come from the u-boot vendor kernel (bl21, acs, bl301)
# Others from the buildroot package (aml_encrypt tool, bl2.bin, bl30)

function usage() {
    echo "Usage: $0 [openlinux branch] [soc] [refboard]"
}

if [[ $# -lt 3 ]]
then
    usage
    exit 22
fi

GITBRANCH=${1}
SOCFAMILY=${2}
REFBOARD=${3}

if [[ "$SOCFAMILY" == "sm1" ]]
then
    SOCFAMILY="g12a"
fi

if ! [[ "$SOCFAMILY" == "g12a" || "$SOCFAMILY" == "g12b" || "$SOCFAMILY" == "sm1" ]]
then
    echo "${SOCFAMILY} is not supported - should be [g12a, g12b, sm1]"
    usage
    exit 22
fi

BIN_LIST="$SOCFAMILY/bl2.bin \
	  $SOCFAMILY/bl30.bin \
	  $SOCFAMILY/bl31.bin \
	  $SOCFAMILY/bl31.img \
	  $SOCFAMILY/aml_encrypt_$SOCFAMILY "

FW_LIST="$SOCFAMILY/*.fw"

# path to clone the openlinux repos
TMP_GIT=$(mktemp -d)

TMP="fip-collect-${SOCFAMILY}-${REFBOARD}-${GITBRANCH}-$(date +%Y%m%d-%H%M%S)"
mkdir $TMP

# U-Boot
git clone --depth=2 https://github.com/khadas/u-boot -b $GITBRANCH $TMP_GIT/u-boot

mkdir $TMP_GIT/gcc-linaro-aarch64-none-elf
wget -qO- https://releases.linaro.org/archive/13.11/components/toolchain/binaries/gcc-linaro-aarch64-none-elf-4.8-2013.11_linux.tar.xz | tar -xJ --strip-components=1 -C $TMP_GIT/gcc-linaro-aarch64-none-elf
mkdir $TMP_GIT/gcc-linaro-arm-none-eabi
wget -qO- https://releases.linaro.org/archive/13.11/components/toolchain/binaries/gcc-linaro-arm-none-eabi-4.8-2013.11_linux.tar.xz | tar -xJ --strip-components=1 -C $TMP_GIT/gcc-linaro-arm-none-eabi
sed -i "s,/opt/gcc-.*/bin/,," $TMP_GIT/u-boot/Makefile
(
    cd $TMP_GIT/u-boot
    make ${REFBOARD}_defconfig
    PATH=$TMP_GIT/gcc-linaro-aarch64-none-elf/bin:$TMP_GIT/gcc-linaro-arm-none-eabi/bin:$PATH CROSS_COMPILE=aarch64-none-elf- make -j8 > /dev/null
    cd fip/tools/ddr_parse && make clean && make
)

cp $TMP_GIT/u-boot/build/board/khadas/*/firmware/acs.bin $TMP/
cp $TMP_GIT/u-boot/build/scp_task/bl301.bin $TMP/
# cp $TMP_GIT/u-boot/fip/tools/ddr_parse/parse $TMP/
$TMP_GIT/u-boot/fip/tools/ddr_parse/parse ${TMP}/acs.bin
# FIP/BLX
echo $BIN_LIST
for item in $BIN_LIST
do
    BIN=$(echo $item)
    DIR1=$TMP_GIT/u-boot/$(basename --suffix=.bin $item)/bin/
    DIR2=$TMP_GIT/u-boot/$(basename --suffix=.img $item)_1.3/bin/
    DIR21=$TMP_GIT/u-boot/$(basename --suffix=.bin $item)_1.3/bin/
    DIR22=$TMP_GIT/u-boot/$(basename --suffix=.img $item)_1.3/bin/
    BRANCH=$GITBRANCH

    if [[ -d $DIR1/$SOCFAMILY/ ]]
    then
      cp $DIR1/$BIN ${TMP}
    elif [[ -d $DIR2/$SOCFAMILY/ ]]
    then
      cp $DIR2/$BIN ${TMP}
    elif [[ -d $DIR21/$SOCFAMILY/ ]]
    then
      cp $DIR21/$BIN ${TMP}
    elif [[ -d $DIR22/$SOCFAMILY/ ]]
    then
      cp $DIR22/$BIN ${TMP}
    fi

done

echo $FW_LIST
cp $TMP_GIT/u-boot/fip/$FW_LIST ${TMP}


# Normalize
mv $TMP_GIT/u-boot/fip/$SOCFAMILY/aml_encrypt_$SOCFAMILY $TMP/aml_encrypt

date > $TMP/info.txt
echo "SOC: $SOCFAMILY" >> $TMP/info.txt
echo "BRANCH: $GITBRANCH ($(date +%Y%m%d))" >> $TMP/info.txt
for component in $TMP_GIT/*
do
    if [[ -d $component/.git ]]
    then
        echo "$(basename $component): $(git --git-dir=$component/.git log --pretty=format:%H HEAD~1..HEAD)" >> $TMP/info.txt
    fi
done
echo "BOARD: $REFBOARD" >> $TMP/info.txt
echo "export SOCFAMILY=$SOCFAMILY" > $TMP/soc-var.sh

rm -rf ${TMP_GIT}
