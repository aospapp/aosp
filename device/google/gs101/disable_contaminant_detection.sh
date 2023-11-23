#!/vendor/bin/sh

echo 0 > "$(find /sys/devices/platform/10d50000.hsi2c -name contaminant_detection)"
