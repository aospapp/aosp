#!/vendor/bin/sh
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

panel_drv=`getprop ro.boot.primary_panel_drv`
if [[ -z "$panel_drv" ]]; then
  panel_drv="panel-samsung-emul"
fi
modprobe -d "${modules_dir}" exynos-drm.ko
modprobe -d "${modules_dir}" $panel_drv.ko

