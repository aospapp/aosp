#!/vendor/bin/sh

# Switch into /proc/irq/$IRQ for the dwc3 usb controller
cd /proc/irq/*/dwc3/.. || exit 7

# Move the USB Controller (DWC3) interrupt as requested
# Modem and DIT are on 0/2/5 depending on throughput, so avoid those.
# 0-3 small, 4-5 medium, 6-7 big
case "$1" in
  medium) core=4;;
  big) core=6;;
  *) core=0;;
esac

# This can sometimes fail due to smp_affinity_list no longer existing...
echo "${core}" > smp_affinity_list
