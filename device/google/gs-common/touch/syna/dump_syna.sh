#!/vendor/bin/sh
path="/sys/devices/platform/10d10000.spi/spi_master/spi0/spi0.0/synaptics_tcm.0/sysfs"

echo "Enable Force Touch Active"
echo 21 > $path/force_active

echo "Touch Firmware Information"
cat $path/info

echo "Get Raw Data - Delta"
echo 12 > $path/get_raw_data && cat $path/get_raw_data

echo "Get Raw Data - Raw"
echo 13 > $path/get_raw_data && cat $path/get_raw_data

echo "Get Raw Data - Baseline"
echo 14 > $path/get_raw_data && cat $path/get_raw_data

echo "Disable Force Touch Active"
echo 20 > $path/force_active

