#!/vendor/bin/sh

# Check if WiFi is enabled. If it is run the WiFi init script. If not we just
# have to run the DHCP client in the default namespace and that will set up
# all the networking.
wifi=`getprop ro.kernel.qemu.wifi`
case "$wifi" in
    1) /vendor/bin/init.wifi.sh
       ;;
    *) setprop ctl.start dhcpclient_def
       ;;
esac

# set up the second interface (for inter-emulator connections)
# if required
my_ip=`getprop net.shared_net_ip`
case "$my_ip" in
    "")
    ;;
    *) ifconfig eth1 "$my_ip" netmask 255.255.255.0 up
    ;;
esac

