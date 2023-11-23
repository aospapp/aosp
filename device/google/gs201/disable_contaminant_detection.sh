#!/vendor/bin/sh

for f in /sys/devices/platform/10d60000.hsi2c/i2c-*/i2c-max77759tcpc; do
  if [ -d $f ]; then
    echo 0 > $f/contaminant_detection;
  fi
done
