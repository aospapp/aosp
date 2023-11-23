/*
 * Copyright (C) 2010 The Android Open Source Project
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
 */

/* this implements a GPS hardware library for the Android emulator.
 * the following code should be built as a shared library that will be
 * placed into /system/lib/hw/gps.goldfish.so
 *
 * it will be loaded by the code in hardware/libhardware/hardware.c
 * which is itself called from android_location_GpsLocationProvider.cpp
 */


#include <errno.h>
#include <pthread.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <math.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>

#define  LOG_TAG  "gps_dummy"
#include <log/log.h>
//#include <cutils/sockets.h>
#include <hardware/gps.h>

#define  GPS_DEBUG  0

#if GPS_DEBUG
#  define  D(...)   ALOGD(__VA_ARGS__)
#else
#  define  D(...)   ((void)0)
#endif

static int
dummy_gps_init(GpsCallbacks* callbacks)
{
    return 0;
}

static void
dummy_gps_cleanup(void)
{
}


static int
dummy_gps_start()
{
    return 0;
}


static int
dummy_gps_stop()
{
    return 0;
}


static int
dummy_gps_inject_time(GpsUtcTime __unused time,
                     int64_t __unused timeReference,
                     int __unused uncertainty)
{
    return 0;
}

static int
dummy_gps_inject_location(double __unused latitude,
                         double __unused longitude,
                         float __unused accuracy)
{
    return 0;
}

static void
dummy_gps_delete_aiding_data(GpsAidingData __unused flags)
{
}

static int dummy_gps_set_position_mode(GpsPositionMode __unused mode,
                                      GpsPositionRecurrence __unused recurrence,
                                      uint32_t __unused min_interval,
                                      uint32_t __unused preferred_accuracy,
                                      uint32_t __unused preferred_time)
{
    // FIXME - support fix_frequency
    return 0;
}

static const void*
dummy_gps_get_extension(const char* __unused name)
{
    // no extensions supported
    return NULL;
}

static const GpsInterface  dummyGpsInterface = {
    sizeof(GpsInterface),
    dummy_gps_init,
    dummy_gps_start,
    dummy_gps_stop,
    dummy_gps_cleanup,
    dummy_gps_inject_time,
    dummy_gps_inject_location,
    dummy_gps_delete_aiding_data,
    dummy_gps_set_position_mode,
    dummy_gps_get_extension,
};

const GpsInterface* gps__get_gps_interface(struct gps_device_t* __unused dev)
{
    return &dummyGpsInterface;
}

static int open_gps(const struct hw_module_t* module,
                    char const* __unused name,
                    struct hw_device_t** device)
{
    struct gps_device_t *dev = (struct gps_device_t *)malloc(sizeof(struct gps_device_t));
    memset(dev, 0, sizeof(*dev));

    dev->common.tag = HARDWARE_DEVICE_TAG;
    dev->common.version = 0;
    dev->common.module = (struct hw_module_t*)module;
    dev->get_gps_interface = gps__get_gps_interface;

    *device = (struct hw_device_t*)dev;
    return 0;
}

static struct hw_module_methods_t gps_module_methods = {
    .open = open_gps
};

struct hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .version_major = 1,
    .version_minor = 0,
    .id = GPS_HARDWARE_MODULE_ID,
    .name = "Dummy GPS Module",
    .author = "The Android Open Source Project",
    .methods = &gps_module_methods,
};
