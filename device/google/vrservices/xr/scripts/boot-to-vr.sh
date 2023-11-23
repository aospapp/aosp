#
# This script finds the init.rc file for a certain Pixel XR device and updates
# the value of ro.boot.vr being set during the init process.
#
SYSTEM_INIT_XR_RC_FILE="/system/etc/init/init.xr.rc"
PROP_RO_HARDWARE="$(getprop ro.hardware)"
PROP_RO_BOOT_HARDWARE_PLATFORM="$(getprop ro.boot.hardware.platform)"
PROP_RO_PRODUCT_NAME="$(getprop ro.product.name)"

function print_usage {
  echo "Update $(get_init_rc_file)"
  echo "Usage:"
  echo "  boot-to-vr.sh (true|false))"
  echo "      Enable or disable whether the system should boot into VR."
  exit 1
}

function get_hardware_name() {
  case $PROP_RO_HARDWARE in
    walleye) echo walleye ;;
    taimen) echo taimen ;;
    blueline) echo $PROP_RO_BOOT_HARDWARE_PLATFORM ;;
    crosshatch) echo $PROP_RO_BOOT_HARDWARE_PLATFORM ;;
  esac
}

function get_init_rc_file() {
  if [ -f $SYSTEM_INIT_XR_RC_FILE ]; then
    echo $SYSTEM_INIT_XR_RC_FILE
  else
    echo "/vendor/etc/init/hw/init.$(get_hardware_name).rc"
  fi
}

function print_init_rc() {
  cat $(get_init_rc_file) | grep -A10 -B10 ro.boot.vr
}

function fail_to_write_file() {
  echo "Cannot modify $(get_init_rc_file). The following commands may help:
    adb disable-verity
    adb reboot
    adb remount"
  exit 1
}

function enable_boot_to_vr() {
  sed -i "s/setprop ro.boot.vr 0/setprop ro.boot.vr 1/" $(get_init_rc_file)
  rc=$?

  if [[ $rc != 0 ]]; then
    fail_to_write_file
  else
    print_init_rc
  fi
}

function disable_boot_to_vr() {
  sed -i "s/setprop ro.boot.vr 1/setprop ro.boot.vr 0/" $(get_init_rc_file)
  rc=$?

  if [[ $rc != 0 ]]; then
    fail_to_write_file
  else
    print_init_rc
  fi
}

WHOAMI=$(whoami)
if ! [ "$WHOAMI" == "root" ]; then
  echo "*** Root access required. Run 'adb root' first."
  exit 1
fi

case "$1" in
  true) enable_boot_to_vr ;;
  false) disable_boot_to_vr ;;
  *) print_usage ;;
esac
