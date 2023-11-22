/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Based on the FlounderPowerHAL
 */

#include <dirent.h>
#include <errno.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdbool.h>
#include <cutils/properties.h>
//#define LOG_NDEBUG 0

#define LOG_TAG "HiKeyPowerHAL"
#include <utils/Log.h>

#include <hardware/hardware.h>
#include <hardware/power.h>

#define BOOSTPULSE_PATH "/sys/devices/system/cpu/cpufreq/interactive/boostpulse"
#define CPU_MAX_FREQ_PATH "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
#define IO_IS_BUSY_PATH "/sys/devices/system/cpu/cpufreq/interactive/io_is_busy"
#define LOW_POWER_MAX_FREQ "729000"
#define NORMAL_MAX_FREQ "1200000"
#define SVELTE_PROP "ro.boot.svelte"
#define SVELTE_MAX_FREQ_PROP "ro.config.svelte.max_cpu_freq"
#define SVELTE_LOW_POWER_MAX_FREQ_PROP "ro.config.svelte.low_power_max_cpu_freq"

struct hikey_power_module {
    struct power_module base;
    pthread_mutex_t lock;
    int boostpulse_fd;
    int boostpulse_warned;
};

static bool low_power_mode = false;

static char *max_cpu_freq = NORMAL_MAX_FREQ;
static char *low_power_max_cpu_freq = LOW_POWER_MAX_FREQ;

static void sysfs_write(const char *path, char *s)
{
    char buf[80];
    int len;
    int fd = open(path, O_WRONLY);

    if (fd < 0) {
        strerror_r(errno, buf, sizeof(buf));
        ALOGE("Error opening %s: %s\n", path, buf);
        return;
    }

    len = write(fd, s, strlen(s));
    if (len < 0) {
        strerror_r(errno, buf, sizeof(buf));
        ALOGE("Error writing to %s: %s\n", path, buf);
    }

    close(fd);
}

static void calculate_max_cpu_freq() {
    int32_t is_svelte = property_get_int32(SVELTE_PROP, 0);

    if (is_svelte) {
        char prop_buffer[PROPERTY_VALUE_MAX];
        int len = property_get(SVELTE_MAX_FREQ_PROP, prop_buffer, LOW_POWER_MAX_FREQ);
        max_cpu_freq = strndup(prop_buffer, len);
        len = property_get(SVELTE_LOW_POWER_MAX_FREQ_PROP, prop_buffer, LOW_POWER_MAX_FREQ);
        low_power_max_cpu_freq = strndup(prop_buffer, len);
    }
}

static void power_init(struct power_module __unused *module)
{
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/timer_rate",
                "20000");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/timer_slack",
                "20000");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/min_sample_time",
                "80000");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/hispeed_freq",
                "1200000");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/go_hispeed_load",
                "99");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/target_loads",
                "65 729000:75 960000:85");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/above_hispeed_delay",
                "20000");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/boostpulse_duration",
                "1000000");
    sysfs_write("/sys/devices/system/cpu/cpufreq/interactive/io_is_busy", "0");

    calculate_max_cpu_freq();
}

static void power_set_interactive(struct power_module __unused *module, int on)
{
    ALOGV("power_set_interactive: %d\n", on);

    /*
     * Lower maximum frequency when screen is off.
     */
    sysfs_write(CPU_MAX_FREQ_PATH,
                (!on || low_power_mode) ? low_power_max_cpu_freq : max_cpu_freq);
    sysfs_write(IO_IS_BUSY_PATH, on ? "1" : "0");
    ALOGV("power_set_interactive: %d done\n", on);
}

static int boostpulse_open(struct hikey_power_module *hikey)
{
    char buf[80];
    int len;

    if (hikey->boostpulse_fd < 0) {
        hikey->boostpulse_fd = open(BOOSTPULSE_PATH, O_WRONLY);

        if (hikey->boostpulse_fd < 0) {
            if (!hikey->boostpulse_warned) {
                strerror_r(errno, buf, sizeof(buf));
                ALOGE("Error opening %s: %s\n", BOOSTPULSE_PATH, buf);
                hikey->boostpulse_warned = 1;
            }
        }
    }
    return hikey->boostpulse_fd;
}

static void set_feature(struct power_module *module, feature_t feature, int state)
{
    struct hikey_power_module *hikey =
            (struct hikey_power_module *) module;
    switch (feature) {
    default:
        ALOGW("Error setting the feature, it doesn't exist %d\n", feature);
        break;
    }
}

static void hikey_power_hint(struct power_module *module, power_hint_t hint,
                                void *data)
{
    struct hikey_power_module *hikey =
            (struct hikey_power_module *) module;
    char buf[80];
    int len;

    pthread_mutex_lock(&hikey->lock);
    switch (hint) {
     case POWER_HINT_INTERACTION:
        if (boostpulse_open(hikey) >= 0) {
            len = write(hikey->boostpulse_fd, "1", 1);

            if (len < 0) {
                strerror_r(errno, buf, sizeof(buf));
                ALOGE("Error writing to %s: %s\n", BOOSTPULSE_PATH, buf);
            }
        }

        break;

   case POWER_HINT_VSYNC:
        break;

    case POWER_HINT_LOW_POWER:
        if (data) {
            sysfs_write(CPU_MAX_FREQ_PATH, low_power_max_cpu_freq);
        } else {
            sysfs_write(CPU_MAX_FREQ_PATH, max_cpu_freq);
        }
        low_power_mode = data;
        break;

    default:
            break;
    }
    pthread_mutex_unlock(&hikey->lock);
}

static struct hw_module_methods_t power_module_methods = {
    .open = NULL,
};

struct hikey_power_module HAL_MODULE_INFO_SYM = {
    base: {
        common: {
            tag: HARDWARE_MODULE_TAG,
            module_api_version: POWER_MODULE_API_VERSION_0_2,
            hal_api_version: HARDWARE_HAL_API_VERSION,
            id: POWER_HARDWARE_MODULE_ID,
            name: "HiKey Power HAL",
            author: "The Android Open Source Project",
            methods: &power_module_methods,
        },

        init: power_init,
        setInteractive: power_set_interactive,
        powerHint: hikey_power_hint,
        setFeature: set_feature,
    },

    lock: PTHREAD_MUTEX_INITIALIZER,
    boostpulse_fd: -1,
    boostpulse_warned: 0,
};

