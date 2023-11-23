#!/vendor/bin/sh
path="/proc/focaltech_touch"

echo "------ Enable Force Touch Active ------"
echo 21 > $path/force_active

echo "------ Touch Firmware Version ------"
cat $path/FW_Version

echo "------ Touch INT PIN Test ------"
cat $path/INT_PIN

echo "------ Get Raw Data - Panel_Differ ------"
cat $path/selftest/Panel_Differ

echo "------ Get Raw Data - Raw ------"
cat $path/selftest/Rawdata

echo "------ Get Raw Data - Baseline ------"
cat $path/selftest/Baseline

echo "------ Get Raw Data - Noise ------"
cat $path/selftest/Noise

echo "------ Get Raw Data - Uniformity ------"
cat $path/selftest/Rawdata_Uniformity

echo "------ Get Scap_CB ------"
cat $path/selftest/Scap_CB

echo "------ Get Scap_Rawdata ------"
cat $path/selftest/Scap_Rawdata

echo "------ Get Short Test ------"
cat $path/selftest/Short

echo "------ Get HeatMap(ms,ss) ------"
cat $path/selftest/Strength

echo "------ Disable Force Touch Active ------"
echo 20 > $path/force_active

