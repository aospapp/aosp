/*
 * Copyright (C) 2019 The Android Open Source Project
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
#ifndef __CROS_EC_INCLUDE_CITADEL_EVENTS_H
#define __CROS_EC_INCLUDE_CITADEL_EVENTS_H

#include <assert.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef __packed
#define __packed __attribute__((packed))
#endif

/*
 * When Citadel needs to tell the AP something without waiting to be asked, the
 * process is as follows:
 *
 *   1. Citadel adds an event_record to its internal queue, then asserts
 *      the CTDL_AP_IRQ signal to notify the AP.
 *
 *   2. The AP (citadeld) requests pending events from Citadel until they've
 *      all been retrieved.
 *
 *   3. Citadel deasserts CTDL_AP_IRQ.
 *
 * Because we may want to compare the history and evolution of events over a
 * long time and for multiple releases, we should only APPEND to this file
 * instead of changing things.
 */

/* Please do not change the size of this struct */
#define EVENT_RECORD_SIZE 64
struct event_record {
  uint64_t reset_count;                 /* zeroed by Citadel power cycle */
  uint64_t uptime_usecs;                /* since last Citadel reset */
  uint32_t id;
  union {
    /* id-specific information goes here */
    struct {
      uint32_t intr_sts[3];
    } alert;
    struct {
      uint32_t bad_thing;
    } citadel;
    struct {
      uint32_t okay_thing;
    } info;

    /* uninterpreted */
    union {
      uint32_t w[11];
      uint16_t h[22];
      uint8_t  b[44];
    } raw;
  } u;
} __packed;
/* Please do not change the size of this struct */
static_assert(sizeof(struct event_record) == EVENT_RECORD_SIZE,
              "Muting the Immutable");

/*
 * Event ID values live forever.
 * Add to the list, but NEVER change or delete existing entries.
*/
enum event_id {
  EVENT_NONE = 0,                       /* No valid event exists with this ID */
  EVENT_ALERT = 1,                      /* Security alert reported */
  EVENT_CITADEL = 2,                    /* Bad: panic, stack overflow, etc. */
  EVENT_INFO = 3,                       /* FYI: normal reboot, etc. */
};

#ifdef __cplusplus
}
#endif

#endif  /* __CROS_EC_INCLUDE_CITADEL_EVENTS_H */
