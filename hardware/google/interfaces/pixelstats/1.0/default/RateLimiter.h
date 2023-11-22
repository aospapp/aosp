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

#ifndef HARDWARE_GOOGLE_PIXELSTATS_V1_0_RATELIMIT_H
#define HARDWARE_GOOGLE_PIXELSTATS_V1_0_RATELIMIT_H

#include <utils/Timers.h>

#include <map>
#include <mutex>

namespace hardware {
namespace google {
namespace pixelstats {
namespace V1_0 {
namespace implementation {

class RateLimiter {
  public:
    RateLimiter(int overall_limit = 0) { SetOverallDailyLimit(overall_limit); }

    // Returns true if you should rate limit the action reporting, false if not.
    // limit: the number of times the action can occur within 24hrs.
    bool RateLimit(int32_t action, int32_t limit);

    // Limit for all actions over a 24hr period.  0 disables overall limit.
    void SetOverallDailyLimit(int32_t limit);

    // for tests only
    void TurnBackHours(int32_t hours);

  private:
    std::mutex lock_;
    std::map<int, int> counts_; // action -> count of actions in a 24hr window.
    int32_t overall_count_;
    int32_t overall_limit_;
    int64_t nsecs_at_rollover_;
};

}  // namespace implementation
}  // namespace V1_0
}  // namespace pixelstats
}  // namespace google
}  // namespace hardware

#endif // HARDWARE_GOOGLE_PIXELSTATS_V1_0_RATELIMIT_H

