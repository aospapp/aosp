/*
 * Copyright 2022 The Android Open Source Project
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

#include "osi/include/log.h"

#include <chrono>
#include <ctime>
#include <string>

#include "gd/common/circular_buffer.h"  // TimestamperInMilliseconds
#include "internal_include/bt_trace.h"
#include "stack/btm/btm_int_types.h"

std::chrono::system_clock::time_point _prev = std::chrono::system_clock::now();

extern uint8_t appl_trace_level;
extern uint8_t btu_trace_level;
extern tBTM_CB btm_cb;

bluetooth::common::TimestamperInMilliseconds timestamper_in_ms;
long long GetTimestampMs() { return timestamper_in_ms.GetTimestamp(); }

void enable_logging() {
  btm_cb.trace_level = BT_TRACE_LEVEL_DEBUG;
  btif_trace_level = BT_TRACE_LEVEL_DEBUG;
  appl_trace_level = BT_TRACE_LEVEL_DEBUG;
  btu_trace_level = BT_TRACE_LEVEL_DEBUG;
}

void log_logging() {
  LOG_INFO(
      "btm_cb.trace_level:%hhu btif_trace_level:%hhu appl_trace_level:%hhu "
      "btu_trace_level:%hhu",
      btm_cb.trace_level, btif_trace_level, appl_trace_level, btu_trace_level);
}
