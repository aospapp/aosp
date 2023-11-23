#!/vendor/bin/sh

echo "Temperatures"
for f in /sys/class/thermal/thermal* ; do
  printf "%s: %s\n" `cat $f/type` `cat $f/temp`
done

echo "Cooling Device Current State"
for f in /sys/class/thermal/cooling* ; do
  printf "%s: %s\n" `cat $f/type` `cat $f/cur_state`
done

echo "Cooling Device User Vote State"
for f in /sys/class/thermal/cooling* ; do
  if [ ! -f $f/user_vote ]; then continue; fi;
  printf "%s: %s\n" `cat $f/type` `cat $f/user_vote`
done

echo "Cooling Device Time in State"
for f in /sys/class/thermal/cooling* ; do
  printf "%s: %s\n" `cat $f/type` `cat $f/stats/time_in_state_ms`
done

echo "Cooling Device Trans Table"
for f in /sys/class/thermal/cooling* ; do
  printf "%s: %s\n" `cat $f/type` `cat $f/stats/trans_table`
done

echo "Cooling Device State2Power Table"
for f in /sys/class/thermal/cooling* ; do
  if [ ! -f $f/state2power_table ]; then continue; fi;
  printf "%s: %s\n" `cat $f/type` `cat $f/state2power_table`
done

echo "TMU state:"
cat /sys/module/gs_thermal/parameters/tmu_reg_dump_state
echo "TMU current temperature:"
cat /sys/module/gs_thermal/parameters/tmu_reg_dump_current_temp
echo "TMU_TOP rise thresholds:"
cat /sys/module/gs_thermal/parameters/tmu_top_reg_dump_rise_thres
echo "TMU_TOP fall thresholds:"
cat /sys/module/gs_thermal/parameters/tmu_top_reg_dump_fall_thres
echo "TMU_SUB rise thresholds:"
cat /sys/module/gs_thermal/parameters/tmu_sub_reg_dump_rise_thres
echo "TMU_SUB fall thresholds:"
cat /sys/module/gs_thermal/parameters/tmu_sub_reg_dump_fall_thres
