#!/vendor/bin/sh

echo "------ Modem Stat ------"
cat /data/vendor/modem_stat/debug.txt

echo "\n------ Modem SSR history ------"
for f in $(ls /data/vendor/ssrdump/crashinfo_modem*); do
  echo $f
  cat $f
done

echo "\n------ RFSD error log ------"
for f in $(ls /data/vendor/log/rfsd/rfslog_*); do
  echo $f
  cat $f
done

