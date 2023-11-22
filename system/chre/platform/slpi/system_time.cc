/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "chre/platform/system_time.h"
#include "chre/platform/slpi/system_time.h"

#include "chre/platform/fatal_error.h"
#include "chre/platform/host_link.h"
#include "chre/platform/log.h"
#include "chre/platform/system_timer.h"

extern "C" {

#include "uTimetick.h"

}  // extern "C"

namespace chre {

namespace {
int64_t gEstimatedHostTimeOffset = 0;

// A timer for scheduling a time sync request.
SystemTimer gTimeSyncRequestTimer;
bool gTimeSyncRequestTimerInitialized = false;

Nanoseconds gLastTimeSyncRequestNanos(0);

void setTimeSyncRequestTimer(Nanoseconds delay) {
  // Check for timer init since this method might be called before CHRE
  // init is called.
  if (!gTimeSyncRequestTimerInitialized) {
    if (!gTimeSyncRequestTimer.init()) {
      FATAL_ERROR("Failed to initialize time sync request timer.");
    } else {
      gTimeSyncRequestTimerInitialized = true;
    }
  }
  if (gTimeSyncRequestTimer.isActive()) {
    gTimeSyncRequestTimer.cancel();
  }
  auto callback = [](void* /* data */) {
    sendTimeSyncRequest();
  };
  if (!gTimeSyncRequestTimer.set(callback, nullptr, delay)) {
    LOGE("Failed to set time sync request timer.");
  }
}
} // anonymous namespace

Nanoseconds SystemTime::getMonotonicTime() {
  constexpr uint64_t kClockFreq = 19200000;  // 19.2MHz QTimer clock

  uint64_t ticks = uTimetick_Get();
  uint64_t nsec = 0;
  if (ticks >= kClockFreq) {
    uint64_t seconds = (ticks / kClockFreq);
    ticks %= kClockFreq;

    nsec = (seconds * kOneSecondInNanoseconds);
  }
  nsec += (ticks * kOneSecondInNanoseconds) / kClockFreq;

  return Nanoseconds(nsec);
}

int64_t SystemTime::getEstimatedHostTimeOffset() {
  return gEstimatedHostTimeOffset;
}

void setEstimatedHostTimeOffset(int64_t offset) {
  gEstimatedHostTimeOffset = offset;

  // Schedule a time sync request since offset may drift
  constexpr Seconds kTimeSyncLongInterval = Seconds(60 * 60 * 6); // 6 hours
  setTimeSyncRequestTimer(kTimeSyncLongInterval);
}

void requestTimeSyncIfStale() {
  constexpr Seconds kTimeSyncShortInterval = Seconds(60 * 60 * 1); // 1 hour
  if (SystemTime::getMonotonicTime() >
      gLastTimeSyncRequestNanos + kTimeSyncShortInterval) {
    sendTimeSyncRequest();
  }
}

void updateLastTimeSyncRequest() {
  gLastTimeSyncRequestNanos = SystemTime::getMonotonicTime();
}

}  // namespace chre
