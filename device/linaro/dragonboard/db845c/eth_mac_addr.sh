#! /system/bin/sh
# Set eth0 mac address.
#
# Get the unique board serial number from /proc/cmdline,
# prepend '0's to the serial number to fill 5 LSBs of the
# MAC address and prepend "02" as MSB to prepare a 6 byte
# locally administered unicast MAC address.
# Format the output in xx:xx:xx:xx:xx:xx format for the
# "ip" set address command to work.

SERIALNO=`cat /proc/cmdline | grep -o serialno.* | cut -f2 -d'=' | awk '{printf("02%010s\n", $1)}' | sed 's/\(..\)/\1:/g' | sed '$s/:$//'`

/system/bin/ip link set dev eth0 down
/system/bin/ip link set dev eth0 address "${SERIALNO}"
/system/bin/ip link set dev eth0 up
