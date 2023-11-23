#!/vendor/bin/sh

#############################################################
### init.insmod.cfg format:                               ###
### ----------------------------------------------------- ###
### [insmod|setprop|enable/moprobe|wait] [path|prop name] ###
### ...                                                   ###
#############################################################

modules_dir=

for f in /vendor/lib/modules/*/modules.dep /vendor/lib/modules/modules.dep; do
  if [[ -f "$f" ]]; then
    modules_dir="$(dirname "$f")"
    break
  fi
done

if [[ -z "${modules_dir}" ]]; then
  echo "Unable to locate kernel modules directory" 2>&1
  exit 1
fi

# imitates wait_for_file() in init
wait_for_file()
{
    filename="${1}"
    timeout="${2:-5}"

    expiry=$(($(date "+%s")+timeout))
    while [[ ! -e "${filename}" ]] && [[ "$(date "+%s")" -le "${expiry}" ]]
    do
        sleep 0.01
    done
}

install_display_drivers()
{
  panel_drv=`getprop ro.boot.primary_panel_drv`
  if [[ -z "$panel_drv" ]]; then
    panel_drv="panel-samsung-emul"
  fi
  modprobe -d "${modules_dir}" exynos-drm.ko
  modprobe -d "${modules_dir}" $panel_drv.ko
}

if [ $# -eq 1 ]; then
  cfg_file=$1
else
  # Set property even if there is no insmod config
  # to unblock early-boot trigger
  setprop vendor.common.modules.ready
  setprop vendor.device.modules.ready
  exit 1
fi

if [ -f $cfg_file ]; then
  while IFS="|" read -r action arg
  do
    case $action in
      "insmod") insmod $arg ;;
      "setprop") setprop $arg 1 ;;
      "enable") echo 1 > $arg ;;
      "modprobe")
        case ${arg} in
          "-b *" | "-b")
            arg="-b --all=${modules_dir}/modules.load" ;;
          "*" | "")
            arg="--all=${modules_dir}/modules.load" ;;
        esac
        modprobe -a -d "${modules_dir}" $arg ;;
      "wait") wait_for_file $arg ;;
      "install_display_drivers") install_display_drivers ;;
    esac
  done < $cfg_file
fi
