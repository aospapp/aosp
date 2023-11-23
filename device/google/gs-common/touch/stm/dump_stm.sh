#!/vendor/bin/sh
spi=`getprop ro.vendor.touch.dump.sys`
proc=`getprop ro.vendor.touch.dump.proc`

echo "------ Force Set AP as Bus Owner with Bugreport Flag ------"
echo A0 01 01 > $proc

echo "------ STM touch firmware version ------"
cat $spi/appid

echo "------ STM touch status ------"
cat $spi/status

echo "------ Mutual Raw ------"
echo 13 00 01 > $spi/stm_fts_cmd && cat $spi/stm_fts_cmd

echo "------ Mutual Strength ------"
echo 17 01 > $spi/stm_fts_cmd && cat $spi/stm_fts_cmd

echo "------ Self Raw ------"
echo 15 00 01 > $spi/stm_fts_cmd && cat $spi/stm_fts_cmd

echo "------ Mutual Raw Data ------"
echo 23 00 > $proc && cat $proc

echo "------ Mutual Baseline Data ------"
echo 23 03 > $proc && cat $proc

echo "------ Mutual Strength Data ------"
echo 23 02 > $proc && cat $proc

echo "------ Self Raw Data ------"
echo 24 00 > $proc && cat $proc

echo "------ Self Baseline Data ------"
echo 24 03 > $proc && cat $proc

echo "------ Self Strength Data ------"
echo 24 02 > $proc && cat $proc

echo "------ Mutual Compensation ------"
echo 32 10 > $proc && cat $proc

echo "------ Mutual Low Power Compensation ------"
echo 32 11 > $proc && cat $proc

echo "------ Self Compensation ------"
echo 33 12 > $proc && cat $proc

echo "------ Golden Mutual Raw Data ------"
echo 34 > $proc && cat $proc

echo "------ Packaging Plant - HW reset ------"
echo 01 FA 20 00 00 24 80 > $proc

echo "------ Packaging Plant - Hibernate Memory ------"
echo 01 FA 20 00 00 68 08 > $proc

echo "------ Packaging Plant - Read 16 bytes from Address 0x00041FD8 ------"
echo 02 FB 00 04 3F D8 00 10 01 > $proc && cat $proc

echo "------ ITO Raw ------"
echo 01 > $spi/stm_fts_cmd && cat $spi/stm_fts_cmd

echo "------ Restore Bus Owner ------"
echo A0 00 01 > $proc

