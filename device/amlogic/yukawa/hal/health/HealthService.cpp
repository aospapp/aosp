#include <health2/service.h>
#include <healthd/healthd.h>

int main() {
    return health_service_main();
}

void healthd_board_init(struct healthd_config*) {}

int healthd_board_battery_update(struct android::BatteryProperties*) {
    return 0;
}
