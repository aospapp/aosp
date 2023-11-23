/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef CHRE_PLATFORM_TRACING_H_
#define CHRE_PLATFORM_TRACING_H_

#include <cstdint>

/**
 * @file
 * Tracing support for CHRE.
 * Platforms must supply an implementation of the trace functions.
 */

namespace chre {
/**
 * Registers a nanoapp instance with the tracing infrastructure.
 *
 * @param instanceId instance ID of the nanoapp.
 * @param name name of the nanoapp.
 */
void traceRegisterNanoapp(uint16_t instanceId, const char *name);

/**
 * Marks the start of the nanoappHandleEvent function.
 *
 * @param instanceId instance ID of the nanoapp.
 * @param eventType event being handled.
 */
void traceNanoappHandleEventStart(uint16_t instanceId, uint16_t eventType);

/**
 * Marks the end of the nanoappHandleEvent function.
 *
 * @param instanceId instance ID of the nanoapp.
 */
void traceNanoappHandleEventEnd(uint16_t instanceId);

}  // namespace chre

#endif  // CHRE_PLATFORM_TRACING_H_
