#!/usr/bin/env bash

#1 is the Amlogic fip directory
#2 is u-boot directory

set -o errexit
set -o pipefail
set -o nounset

set -o xtrace

function fix_blx() {
	#bl2 file size 41K, bl21 file size 3K (file size not equal runtime size)
	#total 44K
	#after encrypt process, bl2 add 4K header, cut off 4K tail

	#bl30 limit 41K
	#bl301 limit 12K
	#bl2 limit 41K
	#bl21 limit 3K, but encrypt tool need 48K bl2.bin, so fix to 7168byte.

	declare blx_bin_limit=0
	declare blx01_bin_limit=0
	declare -i blx_size=0
	declare -i zero_size=0

	#$7:name flag
	if [ "$7" = "bl30" ]; then
		blx_bin_limit=40960   # PD#132613 2016-10-31 update, 41984->40960
		blx01_bin_limit=13312 # PD#132613 2016-10-31 update, 12288->13312
	elif [ "$7" = "bl2" ]; then
	    if [ "$SOCFAMILY" = "g12a" -o "$SOCFAMILY" = "sm1" -o "$SOCFAMILY" = "g12b" ]; then
		blx_bin_limit=57344
		blx01_bin_limit=4096
	    else
		blx_bin_limit=41984
		blx01_bin_limit=7168
	    fi
	else
		echo "blx_fix name flag not supported!"
		exit 1
	fi

	# blx_size: blx.bin size, zero_size: fill with zeros
	blx_size=`du -b $1 | awk '{print int($1)}'`
	zero_size=$blx_bin_limit-$blx_size
	dd if=/dev/zero of=$2 bs=1 count=$zero_size
	cat $1 $2 > $3
	rm $2

	blx_size=`du -b $4 | awk '{print int($1)}'`
	zero_size=$blx01_bin_limit-$blx_size
	dd if=/dev/zero of=$2 bs=1 count=$zero_size
	cat $4 $2 > $5

	cat $3 $5 > $6

	rm $2
}

FIPDIR=${1}
UBOOTBIN=${2:-u-boot.bin}

source ${FIPDIR}/soc-var.sh

TMP=$(mktemp -d)

if [ "$SOCFAMILY" = "gxl" ]
then

    fix_blx ${FIPDIR}/bl30.bin ${TMP}/zero_tmp ${TMP}/bl30_zero.bin ${FIPDIR}/bl301.bin ${TMP}/bl301_zero.bin ${TMP}/bl30_new.bin bl30
    /usr/bin/env python2 ${FIPDIR}/acs_tool.pyc ${FIPDIR}/bl2.bin ${TMP}/bl2_acs.bin ${FIPDIR}/acs.bin 0
    fix_blx ${TMP}/bl2_acs.bin ${TMP}/zero_tmp ${TMP}/bl2_zero.bin ${FIPDIR}/bl21.bin ${TMP}/bl21_zero.bin ${TMP}/bl2_new.bin bl2
    ${FIPDIR}/aml_encrypt --bl3enc --input ${TMP}/bl30_new.bin --output ${TMP}/bl30_new.bin.enc
    ${FIPDIR}/aml_encrypt --bl3enc --input ${FIPDIR}/bl31.img --output ${TMP}/bl31.img.enc
    ${FIPDIR}/aml_encrypt --bl3enc --input ${UBOOTBIN} --output ${TMP}/bl33.bin.enc
    ${FIPDIR}/aml_encrypt --bl2sig --input ${TMP}/bl2_new.bin --output ${TMP}/bl2.n.bin.sig
    ${FIPDIR}/aml_encrypt --bootmk --output ${TMP}/u-boot.bin \
	     --bl2 ${TMP}/bl2.n.bin.sig \
	     --bl30 ${TMP}/bl30_new.bin.enc \
	     --bl31 ${TMP}/bl31.img.enc \
	     --bl33 ${TMP}/bl33.bin.enc

elif [ "$SOCFAMILY" = "axg" ]
then
    fix_blx ${FIPDIR}/bl30.bin ${TMP}/zero_tmp ${TMP}/bl30_zero.bin ${FIPDIR}/bl301.bin ${TMP}/bl301_zero.bin ${TMP}/bl30_new.bin bl30
    /usr/bin/env python2 ${FIPDIR}/acs_tool.pyc ${FIPDIR}/bl2.bin ${TMP}/bl2_acs.bin ${FIPDIR}/acs.bin 0
    fix_blx ${TMP}/bl2_acs.bin ${TMP}/zero_tmp ${TMP}/bl2_zero.bin ${FIPDIR}/bl21.bin ${TMP}/bl21_zero.bin ${TMP}/bl2_new.bin bl2
    ${FIPDIR}/aml_encrypt --bl3sig --input ${TMP}/bl30_new.bin --output ${TMP}/bl30_new.bin.enc --level v3 --type bl30
    ${FIPDIR}/aml_encrypt --bl3sig --input ${FIPDIR}/bl31.img --output ${TMP}/bl31.img.enc --level v3 --type bl31
    ${FIPDIR}/aml_encrypt --bl3sig --input  ${UBOOTBIN} --output ${TMP}/bl33.bin.enc --level v3 --type bl33 --compress lz4
    ${FIPDIR}/aml_encrypt --bl2sig --input ${TMP}/bl2_new.bin --output ${TMP}/bl2.n.bin.sig
    ${FIPDIR}/aml_encrypt --bootmk --output ${TMP}/u-boot.bin \
	     --bl2 ${TMP}/bl2.n.bin.sig \
	     --bl30 ${TMP}/bl30_new.bin.enc \
	     --bl31 ${TMP}/bl31.img.enc \
	     --bl33 ${TMP}/bl33.bin.enc \
	     --level v3

elif [ "$SOCFAMILY" = "g12a" -o "$SOCFAMILY" = "sm1" -o "$SOCFAMILY" = "g12b" ]
then
    cp ${FIPDIR}/acs.bin ${TMP}/acs.bin
    [ -e ${FIPDIR}/parse ] && ${FIPDIR}/parse ${TMP}/acs.bin
    fix_blx ${FIPDIR}/bl30.bin ${TMP}/zero_tmp ${TMP}/bl30_zero.bin ${FIPDIR}/bl301.bin ${TMP}/bl301_zero.bin ${TMP}/bl30_new.bin bl30
    fix_blx ${FIPDIR}/bl2.bin ${TMP}/zero_tmp ${TMP}/bl2_zero.bin ${TMP}/acs.bin ${TMP}/bl21_zero.bin ${TMP}/bl2_new.bin bl2
    ${FIPDIR}/aml_encrypt --bl30sig --input ${TMP}/bl30_new.bin --output ${TMP}/bl30_new.bin.g12.enc --level v3
    ${FIPDIR}/aml_encrypt --bl3sig  --input ${TMP}/bl30_new.bin.g12.enc --output ${TMP}/bl30_new.bin.enc --level v3 --type bl30
    ${FIPDIR}/aml_encrypt --bl3sig  --input ${FIPDIR}/bl31.img --output ${TMP}/bl31.img.enc --level v3 --type bl31
    ${FIPDIR}/aml_encrypt --bl3sig  --input ${UBOOTBIN} --compress lz4 --output ${TMP}/bl33.bin.enc --level v3 --type bl33
    ${FIPDIR}/aml_encrypt --bl2sig  --input ${TMP}/bl2_new.bin --output ${TMP}/bl2.n.bin.sig
    if [ -e ${FIPDIR}/lpddr3_1d.fw ]
    then
	    ${FIPDIR}/aml_encrypt --bootmk  --output ${TMP}/u-boot.bin \
		     --bl2 ${TMP}/bl2.n.bin.sig \
		     --bl30 ${TMP}/bl30_new.bin.enc \
		     --bl31 ${TMP}/bl31.img.enc \
		     --bl33 ${TMP}/bl33.bin.enc \
		     --ddrfw1 ${FIPDIR}/ddr4_1d.fw \
		     --ddrfw2 ${FIPDIR}/ddr4_2d.fw \
		     --ddrfw3 ${FIPDIR}/ddr3_1d.fw \
		     --ddrfw4 ${FIPDIR}/piei.fw \
		     --ddrfw5 ${FIPDIR}/lpddr4_1d.fw \
		     --ddrfw6 ${FIPDIR}/lpddr4_2d.fw \
		     --ddrfw7 ${FIPDIR}/diag_lpddr4.fw \
		     --ddrfw8 ${FIPDIR}/aml_ddr.fw \
		     --ddrfw9 ${FIPDIR}/lpddr3_1d.fw \
		     --level v3
    else
	    ${FIPDIR}/aml_encrypt --bootmk  --output ${TMP}/u-boot.bin \
		     --bl2 ${TMP}/bl2.n.bin.sig \
		     --bl30 ${TMP}/bl30_new.bin.enc \
		     --bl31 ${TMP}/bl31.img.enc \
		     --bl33 ${TMP}/bl33.bin.enc \
		     --ddrfw1 ${FIPDIR}/ddr4_1d.fw \
		     --ddrfw2 ${FIPDIR}/ddr4_2d.fw \
		     --ddrfw3 ${FIPDIR}/ddr3_1d.fw \
		     --ddrfw4 ${FIPDIR}/piei.fw \
		     --ddrfw5 ${FIPDIR}/lpddr4_1d.fw \
		     --ddrfw6 ${FIPDIR}/lpddr4_2d.fw \
		     --ddrfw7 ${FIPDIR}/diag_lpddr4.fw \
		     --ddrfw8 ${FIPDIR}/aml_ddr.fw \
		     --level v3
    fi
else
    echo "${SOCFAMILY} is not supported - should be [gxl, axg, g12a, sm1, g12b]"
    exit 22
fi

TMP2="uboot-bins-$(date +%Y%m%d-%H%M%S)"
mkdir $TMP2
ln -sfn $TMP2 uboot-bins

mv ${TMP}/u-boot.bin{,.sd.bin,.usb.bl2,.usb.tpl} ${TMP2}
rm -r ${TMP}
