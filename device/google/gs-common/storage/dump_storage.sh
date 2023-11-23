#!/vendor/bin/sh
# F2FS
echo "--- F2FS status---"
cat "/sys/kernel/debug/f2fs/status"
echo "\n--- F2FS fsck ---"
cat "/dev/fscklogs/fsck"
echo "\n--- F2FS - fsck time (ms) ---"
getprop ro.boottime.init.fsck.data
echo "\n--- F2FS - checkpoint=disable time (ms) ---"
getprop ro.boottime.init.mount.data

# UFS
echo "\n------ UFS model (/sys/block/sda/device/model) ------"
cat "/sys/block/sda/device/model"
echo "\n------ UFS rev (/sys/block/sda/device/rev) ------"
cat "/sys/block/sda/device/rev"
echo "\n------ UFS size (/sys/block/sda/size) ------"
cat "/sys/block/sda/size"

echo "\n------ UFS Slow IO Read (/dev/sys/block/bootdevice/slowio_read_cnt) ------"
cat "/dev/sys/block/bootdevice/slowio_read_cnt"
echo "\n------ UFS Slow IO Write (/dev/sys/block/bootdevice/slowio_write_cnt) ------"
cat "/dev/sys/block/bootdevice/slowio_write_cnt"
echo "\n------ UFS Slow IO Unmap (/dev/sys/block/bootdevice/slowio_unmap_cnt) ------"
cat "/dev/sys/block/bootdevice/slowio_unmap_cnt"
echo "\n------ UFS Slow IO Sync (/dev/sys/block/bootdevice/slowio_sync_cnt) ------"
cat "/dev/sys/block/bootdevice/slowio_sync_cnt"

echo "\n------ UFS err_stats ------"
path="/dev/sys/block/bootdevice/err_stats";
for node in `ls $path/* | grep -v reset_err_status`
do
  printf "%s:%d\n" $(basename $node) $(cat $node)
done


echo "\n------ UFS io_stats ------"
path="/dev/sys/block/bootdevice/io_stats"
printf "\t\t%-12s %-12s %-12s %-12s %-12s %-12s\n" ReadCnt ReadBytes WriteCnt WriteBytes RWCnt RWBytes
str=$(cat $path/*_start); arr=($str);
printf "Started: \t%-12s %-12s %-12s %-12s %-12s %-12s\n" ${arr[1]} ${arr[0]} ${arr[5]} ${arr[4]} ${arr[3]} ${arr[2]}
str=$(cat $path/*_complete); arr=($str);
printf "Completed: \t%-12s %-12s %-12s %-12s %-12s %-12s\n" ${arr[1]} ${arr[0]} ${arr[5]} ${arr[4]} ${arr[3]} ${arr[2]}
str=$(cat $path/*_maxdiff); arr=($str);
printf "MaxDiff: \t%-12s %-12s %-12s %-12s %-12s %-12s\n\n" ${arr[1]} ${arr[0]} ${arr[5]} ${arr[4]} ${arr[3]} ${arr[2]}

echo "\n------ UFS req_stats ------"
path="/dev/sys/block/bootdevice/req_stats"
printf "\t%-12s %-12s %-12s %-12s %-12s %-12s\n" All Write Read Security Flush Discard
str=$(cat $path/*_min); arr=($str);
printf "Min:\t%-12s %-12s %-12s %-12s %-12s %-12s\n" ${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]}
str=$(cat $path/*_max); arr=($str);
printf "Max:\t%-12s %-12s %-12s %-12s %-12s %-12s\n" ${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]}
str=$(cat $path/*_avg); arr=($str);
printf "Avg.:\t%-12s %-12s %-12s %-12s %-12s %-12s\n" ${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]}
str=$(cat $path/*_sum); arr=($str);
printf "Count:\t%-12s %-12s %-12s %-12s %-12s %-12s\n\n" ${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]}

echo "\n------ UFS health ------"
for f in $(find /dev/sys/block/bootdevice/health_descriptor -type f)
do
  if [[ -r $f && -f $f ]]; then
    echo --- $f
    cat $f
    echo ''
  fi
done

