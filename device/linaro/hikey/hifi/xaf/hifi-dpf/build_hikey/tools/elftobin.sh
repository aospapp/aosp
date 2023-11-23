#!/bin/bash

if [ $# != 2 ]
then
echo "elftobin.sh <input_elf_file> <output_img_file>"
exit
fi

if [ $XTENSA_CORE == "" ]
then 
echo "XTENSA_CORE is not set"
exit
fi

if [ $XTENSA_SYSTEM == "" ]
then 
echo "XTENSA_SYSTEM is not set"
exit
fi

export INPUT_ELF_FILE=$1
export OUTPUT_BIN_FILE=__hikey_temp.bin
export OUTPUT_IMG_FILE=$2
export OP_DIR=./

xt-objdump -h $INPUT_ELF_FILE > __elfdump
less __elfdump | sed -n '/ALLOC/{g;1!p;};h' > __header
less __header | awk '{print $2 " " $3 " " $4}' > __proc

sections=`less __proc | cut -f1 -d' ' | tr '\n' ',' | sed 's/\,$//g'`
SECTION_NUM=`awk 'END {print NR}' __proc`
echo "Total number of sections are $SECTION_NUM"
#sections=(".dram0.data" ".ResetVector.text" ".Reset.literal" ".WindowVectors.text" ".Level2InterruptVector.literal" ".Level2InterruptVector.text" ".Level3InterruptVector.literal" ".Level3InterruptVector.text" ".DebugExceptionVector.literal" ".DebugExceptionVector.text" ".NMIExceptionVector.literal" ".NMIExceptionVector.text" ".KernelExceptionVector.literal" ".KernelExceptionVector.text" ".UserExceptionVector.literal" ".UserExceptionVector.text" ".DoubleExceptionVector.literal" ".DoubleExceptionVector.text" ".Reset.text" ".iram0.text" ".rodata" ".text" ".data" ".bss")
#dst_addr=("0xe8058000" "0xe8080000" "0xe8080300" "0xe8080400" "0xe8080578" "0xe8080580" "0xe80805b8" "0xe80805c0" "0xe8080638" "0xe8080640" "0xe8080678" "0xe80806c0" "0xe80806f8" "0xe8080700" "0xe8080738" "0xe8080740" "0xe8080778" "0xe80807c0" "0xe8080800" "0xe8080a44" "0xc0000000" "0xc0000cc8" "0xc00025c0" "0xc0002a20")

ID_Sec=("0x01010000" "0x01000001" "0x01000002" "0x01000003" "0x01000004" "0x01000005" "0x01000006" "0x01000007" "0x01000008" "0x01000009" "0x0100000A" "0x0100000B" "0x0100000C" "0x0100000D" "0x0100000E" "0x0100000F" "0x01000010" "0x01000011" "0x01000012" "0x01000013" "0x00000014" "0x00000015" "0x00010016" "0x00020017")

head -c "4" /dev/zero > __zero.bin

i=0
for j in `seq 1 $SECTION_NUM`
do
	VAR=`less __proc | awk 'FNR == "'"$j"'" {print $1}'`
	VARBIN=`echo "$VAR" | sed 's/^./__/' | sed "s/\..*//" | sed 's/$/.bin/' `
	#echo "section: $VAR  $VARBIN"
	xt-objcopy $INPUT_ELF_FILE -O binary --only-section $VAR $VARBIN

	size_sec[${i}]=$(cat $VARBIN | wc -c)
	#echo "size : ${size_sec[${i}]}"
    mod0=`expr ${size_sec[${i}]} % 4`
    nbytes=`expr 4 - $mod0`
	if [ "$nbytes" -ne "4" ] ; then
		tail -c $nbytes __zero.bin >> $VARBIN
		size_sec[$i]=`expr ${size_sec[${i}]} + $nbytes`
	fi
	cat $VARBIN >> $OUTPUT_BIN_FILE		
	i=`expr $i + 1`;
done
cd $OP_DIR

tmp1=$(cat $OUTPUT_BIN_FILE | wc -c)
total_sections=$SECTION_NUM
main_header_size=32
section_header_size=16
size_total_img=`expr $main_header_size + $tmp1 + $total_sections \* $section_header_size`
current_date_time=`date "+%Y/%m/%d %H:%M:%S"`
echo "HIF:$current_date_time" > $OUTPUT_IMG_FILE

printf "0: %.8x" $size_total_img | sed -e 's/0\: \(..\)\(..\)\(..\)\(..\)/0\: \4\3\2\1/' | xxd -r -g0 >> $OUTPUT_IMG_FILE
printf "0: %.8x" $total_sections | sed -e 's/0\: \(..\)\(..\)\(..\)\(..\)/0\: \4\3\2\1/' | xxd -r -g0 >> $OUTPUT_IMG_FILE

cnt1=`expr $main_header_size + $total_sections \* $section_header_size`
i=0
for j in `seq 1 $SECTION_NUM`
do
	DST_ADDR=`less __proc | awk 'FNR == "'"$j"'" {print $3}'`
	printf "0: %.8x" ${ID_Sec[${i}]} | sed -e 's/0\: \(..\)\(..\)\(..\)\(..\)/0\: \4\3\2\1/' | xxd -r -g0 >> $OUTPUT_IMG_FILE
	printf "0: %.8x" $cnt1 | sed -e 's/0\: \(..\)\(..\)\(..\)\(..\)/0\: \4\3\2\1/' | xxd -r -g0 >> $OUTPUT_IMG_FILE
	printf "0: %.8x" 0x$DST_ADDR | sed -e 's/0\: \(..\)\(..\)\(..\)\(..\)/0\: \4\3\2\1/' | xxd -r -g0 >> $OUTPUT_IMG_FILE
	printf "0: %.8x" ${size_sec[${i}]} | sed -e 's/0\: \(..\)\(..\)\(..\)\(..\)/0\: \4\3\2\1/' | xxd -r -g0 >> $OUTPUT_IMG_FILE
	cnt_prev=$cnt1
	cnt1=`expr ${size_sec[${i}]} + $cnt_prev`
	i=`expr $i + 1`
done

cat $OUTPUT_BIN_FILE >> $OUTPUT_IMG_FILE                        
rm $OUTPUT_BIN_FILE
rm __*.bin
rm __proc
rm __elfdump
rm __header

echo "elftobin successful!"

