/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef CHRE_UTIL_CONTAINER_SUPPORT_H_
#define CHRE_UTIL_CONTAINER_SUPPORT_H_

/**
 * @file Provides replacements for macros and functions that are normally
 * provided by the CHRE framework implementation. These portable implementations
 * are implemented using the CHRE API rather than private system APIs.
 */

#ifdef CHRE_IS_NANOAPP_BUILD

#include <chre.h>

#include "chre/util/nanoapp/log.h"

/**
 * Provides the CHRE_ASSERT macro that uses chreAbort to abort the nanoapp upon
 * failure.
 *
 * @param the condition to check for non-zero.
 */
#define CHRE_ASSERT(condition) do { \
  if (!(condition)) {               \
    chreAbort(UINT32_MAX);          \
  }                                 \
} while (0)


/**
 * Provides the CHRE_ASSERT_LOG macro that logs the assertion failure followed
 * by CHRE_ASSERT if the condition is non-zero.
 *
 * @param condition the condition to check for non-zero.
 * @param fmt the format string to log.
 * @param ... arguments to format into the log message.
 */
#define CHRE_ASSERT_LOG(condition, fmt, ...) do { \
  if (!(condition)) {                             \
    LOGE("Assert: " fmt, ##__VA_ARGS__);          \
    CHRE_ASSERT(condition);                       \
  }                                               \
} while (0)

namespace chre {

/**
 * Provides the memoryAlloc function that is normally provided by the CHRE
 * runtime. It maps into chreHeapAlloc.
 *
 * @param size the size of the allocation to make.
 * @return a pointer to allocated memory or nullptr if allocation failed.
 */
inline void *memoryAlloc(size_t size) {
  return chreHeapAlloc(static_cast<uint32_t>(size));
}

/**
 * Provides the memoryFree function that is normally provided by the CHRE
 * runtime. It maps into chreHeapFree.
 *
 * @param pointer the allocation to release.
 */
inline void memoryFree(void *pointer) {
  chreHeapFree(pointer);
}

}  // namespace chre

#else
#include "chre/platform/assert.h"
#include "chre/platform/memory.h"
#endif

#endif  // CHRE_UTIL_CONTAINER_SUPPORT_H_
