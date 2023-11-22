/*
 * Copyright 2018 The Android Open Source Project
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

#include <hardware_legacy/power.h>

#include <csignal>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include <gtest/gtest.h>

using namespace std::chrono_literals;

namespace android {

// Test acquiring/releasing WakeLocks concurrently with process exit.
TEST(LibpowerTest, ProcessExitTest) {
    std::atexit([] {
        // We want to give the other thread enough time trigger a failure and
        // dump the stack traces.
        std::this_thread::sleep_for(1s);
    });

    ASSERT_EXIT(
    {
        constexpr int numThreads = 20;
        std::vector<std::thread> tds;
        for (int i = 0; i < numThreads; i++) {
            tds.emplace_back([] {
            while (true) {
                // We want ids to be unique.
                std::string id = std::to_string(rand());
                ASSERT_EQ(acquire_wake_lock(PARTIAL_WAKE_LOCK, id.c_str()), 0);
                ASSERT_EQ(release_wake_lock(id.c_str()), 0);
            }
        });
        }
        for (auto& td : tds) {
            td.detach();
        }

        // Give some time for the threads to actually start.
        std::this_thread::sleep_for(100ms);
        std::exit(0);
    },
    ::testing::ExitedWithCode(0), "");
}

// Stress test acquiring/releasing WakeLocks.
TEST(LibpowerTest, WakeLockStressTest) {
    // numThreads threads will acquire/release numLocks locks each.
    constexpr int numThreads = 20;
    constexpr int numLocks = 1000;
    std::vector<std::thread> tds;

    for (int i = 0; i < numThreads; i++) {
        tds.emplace_back([i] {
            for (int j = 0; j < numLocks; j++) {
                // We want ids to be unique.
                std::string id = std::to_string(i) + "/" + std::to_string(j);
                ASSERT_EQ(acquire_wake_lock(PARTIAL_WAKE_LOCK, id.c_str()), 0);
                ASSERT_EQ(release_wake_lock(id.c_str()), 0);
            }
        });
    }
    for (auto& td : tds) {
        td.join();
    }
}

}  // namespace android
