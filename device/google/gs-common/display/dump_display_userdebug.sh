#!/vendor/bin/sh
echo "------ HWC Fence States ------"
for f in $(ls /data/vendor/log/hwc/*_hwc_fence_state*.txt)
do
  echo $f
  cat $f
done

echo "------ HWC Error Logs ------"
for f in $(ls /data/vendor/log/hwc/*_hwc_error_log*.txt)
do
  echo $f
  cat $f
done

echo "------ HWC Debug Dumps ------"
for f in $(ls /data/vendor/log/hwc/*_hwc_debug*.dump)
do
  echo $f
  cat $f
done

