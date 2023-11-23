#!/vendor/bin/sh
echo "Citadel VERSION\n"
vendor/bin/hw/citadel_updater -lv
echo "Citadel STATS\n"
vendor/bin/hw/citadel_updater --stats
echo "GSC DEBUG DUMP\n"
vendor/bin/hw/citadel_updater -D
