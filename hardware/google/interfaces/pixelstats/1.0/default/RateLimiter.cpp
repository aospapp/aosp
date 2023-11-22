/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "RateLimiter.h"
#include <utils/Timers.h>

namespace hardware {
namespace google {
namespace pixelstats {
namespace V1_0 {
namespace implementation {

// for tests only
void RateLimiter::TurnBackHours(int32_t hours) {
    nsecs_at_rollover_ = systemTime(SYSTEM_TIME_BOOTTIME) - s2ns(hours * 60 * 60);
}

// Update the 24hr limit for all actions
void RateLimiter::SetOverallDailyLimit(int32_t limit) {
    std::lock_guard<std::mutex> lock(lock_);
    overall_limit_ = limit;
}

// Returns true if you should rate limit the action reporting, false if not.
// limit: the number of times the action can occur within 24hrs.
bool RateLimiter::RateLimit(int32_t action, int32_t limit) {
    std::lock_guard<std::mutex> lock(lock_);
    int64_t nsecsNow = systemTime(SYSTEM_TIME_BOOTTIME);

    if (nsecsNow < nsecs_at_rollover_) {
        return true;  // fail safe, rate limit.
    }
    int64_t elapsed = nsecsNow - nsecs_at_rollover_;
    constexpr int64_t kDaySeconds = 60 * 60 * 24;
    if (nanoseconds_to_seconds(elapsed) > kDaySeconds) {
        counts_.clear();
        overall_count_ = 0;
        nsecs_at_rollover_ = nsecsNow;
    }

    if (++counts_[action] > limit) return true;
    if (overall_limit_ && ++overall_count_ > overall_limit_) return true;
    return false;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace pixelstats
}  // namespace google
}  // namespace hardware
