/* Copyright (C) 2007-2008 The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#pragma once

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include "aemu/base/logging/LogSeverity.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    kLogDefaultOptions = 0,
    kLogEnableDuplicateFilter = 1,
    kLogEnableTime = 1 << 2,
    kLogEnableVerbose = 1 << 3,
} LoggingFlags;


// Enable/disable verbose logs from the base/* family.
extern void base_enable_verbose_logs();
extern void base_disable_verbose_logs();

// Configure the logging framework.
extern void base_configure_logs(LoggingFlags flags);
extern void __emu_log_print(LogSeverity prio,
                            const char* file,
                            int line,
                            const char* fmt,
                            ...);

#ifndef EMULOG
#define EMULOG(priority, fmt, ...) \
    __emu_log_print(priority, __FILE__, __LINE__, fmt, ##__VA_ARGS__);
#endif

// Logging support.
#define dprint(fmt, ...)                                 \
    if (EMULATOR_LOG_DEBUG >= android_log_severity) {  \
        EMULOG(EMULATOR_LOG_DEBUG, fmt, ##__VA_ARGS__) \
    }

#define dinfo(fmt, ...)                               \
    if (EMULATOR_LOG_INFO >= android_log_severity) {  \
        EMULOG(EMULATOR_LOG_INFO, fmt, ##__VA_ARGS__) \
    }
#define dwarning(fmt, ...)                               \
    if (EMULATOR_LOG_WARNING >= android_log_severity) {  \
        EMULOG(EMULATOR_LOG_WARNING, fmt, ##__VA_ARGS__) \
    }
#define derror(fmt, ...)                               \
    if (EMULATOR_LOG_ERROR >= android_log_severity) {  \
        EMULOG(EMULATOR_LOG_ERROR, fmt, ##__VA_ARGS__) \
    }
#define dfatal(fmt, ...) EMULOG(EMULATOR_LOG_FATAL, fmt, ##__VA_ARGS__)

#ifdef __cplusplus
}
#endif

