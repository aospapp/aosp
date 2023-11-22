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

#include <gtest/gtest.h>
#include <utils/Timers.h>

namespace {

using hardware::google::pixelstats::V1_0::implementation::RateLimiter;

class RateLimiterTest : public ::testing::Test {};

TEST_F(RateLimiterTest, RateLimiterStartZero) {
    RateLimiter limiter;
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_TRUE(limiter.RateLimit(0, 2));
}

TEST_F(RateLimiterTest, RateLimiterTwoBins) {
    RateLimiter limiter;
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_FALSE(limiter.RateLimit(1, 2));
    EXPECT_FALSE(limiter.RateLimit(1, 2));
    EXPECT_TRUE(limiter.RateLimit(1, 2));
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_TRUE(limiter.RateLimit(1, 2));
    EXPECT_TRUE(limiter.RateLimit(0, 2));
}

TEST_F(RateLimiterTest, RateLimiterTimeReset) {
    RateLimiter limiter;
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_TRUE(limiter.RateLimit(0, 2));

    // Still ratelimit after 23 hrs have passed
    limiter.TurnBackHours(23);
    EXPECT_TRUE(limiter.RateLimit(0, 2));

    // Expect reset after 25hrs have passed
    limiter.TurnBackHours(25);
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_FALSE(limiter.RateLimit(0, 2));
    EXPECT_TRUE(limiter.RateLimit(0, 2));
}

TEST_F(RateLimiterTest, AllActionLimit) {
    RateLimiter limiter(2);

    // Log three actions, expect the third to be
    // ratelimited due to the daily overall ratelimit of 2.
    EXPECT_FALSE(limiter.RateLimit(1, 6));
    EXPECT_FALSE(limiter.RateLimit(2, 6));
    EXPECT_TRUE(limiter.RateLimit(3, 6));

    // Still ratelimit after 23 hrs have passed
    limiter.TurnBackHours(23);
    EXPECT_TRUE(limiter.RateLimit(4, 6));

    // Expect reset after 25hrs have passed
    limiter.TurnBackHours(25);
    EXPECT_FALSE(limiter.RateLimit(5, 6));
}

}  // namespace
