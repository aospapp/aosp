#!/vendor/bin/sh
path="/sys/devices/virtual/sec/tsp"

echo "--- Force Touch Active ---"
echo "force_touch_active,2,1" > $path/cmd && cat $path/cmd_result

echo "--- LSI firmware version ---"
cat $path/fw_version

echo "--- LSI touch status ---"
cat $path/status

echo "--- Calibration info ---"
echo "get_mis_cal_info" > $path/cmd && cat $path/cmd_result

echo "--- Mutual Strength ---"
echo "run_delta_read_all" > $path/cmd && cat $path/cmd_result

echo "--- Self Strength ---"
echo "run_self_delta_read_all" > $path/cmd && cat $path/cmd_result

echo "--- TYPE_AMBIENT_DATA ---"
echo "run_rawdata_read_type,3" > $path/cmd && cat $path/cmd_result

echo "--- TYPE_DECODED_DATA ---"
echo "run_rawdata_read_type,5" > $path/cmd && cat $path/cmd_result

echo "--- TYPE_OFFSET_DATA_SEC ---"
echo "run_rawdata_read_type,19" > $path/cmd && cat $path/cmd_result

echo "--- TYPE_NOI_P2P_MIN ---"
echo "run_rawdata_read_type,30" > $path/cmd && cat $path/cmd_result

echo "--- TYPE_NOI_P2P_MAX ---"
echo "run_rawdata_read_type,31" > $path/cmd && cat $path/cmd_result

echo "--- Mutual Raw Cap ---"
echo "run_rawcap_read_all" > $path/cmd && cat $path/cmd_result

echo "--- Self Raw Cap ---"
echo "run_self_rawcap_read_all" > $path/cmd && cat $path/cmd_result

echo "--- CM2 ---"
echo "run_rawcap_high_freq_read_all" > $path/cmd && cat $path/cmd_result

echo "--- Force Touch Active ---"
echo force_touch_active,2,0 > $path/cmd && cat $path/cmd_result

