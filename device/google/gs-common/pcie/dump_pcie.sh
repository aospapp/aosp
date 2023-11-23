#!/vendor/bin/sh
echo "--- pcie0 dump ---"
cat /dev/logbuffer_pcie0
echo "--- pcie1 dump ---"
cat /dev/logbuffer_pcie1

echo "--- pcie link statistics ---"
for f in /sys/devices/platform/*.pcie/link_stats/*
do
  echo "$f: `cat $f`"
done
