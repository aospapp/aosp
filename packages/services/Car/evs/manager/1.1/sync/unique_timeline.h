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

#pragma once

#include "unique_fd.h"
#include "unique_fence.h"

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

// This is a simple C++ wrapper around the sw_sync interface. It is used to
// create sync fences using timeline semantics.
//
// The timeline has two counters, a fence event counter maintained here in this
// class, and the timeline counter hidden in the driver. The one in the driver
// is initialized to zero when creating the timeline, and the one here is
// initialized to one. The counters are meant to be independently incremented.
//
// When the driver counter is incremented, all fences that were created with
// counts after the previous value of the timeline counter, and before (and
// including) the new value are signaled by the driver.
//
// All fences are signaled if the timeline is also destroyed.
//
// The typical uses of these fences is to acquire a fence for some future point
// on the timeline, and incrementing the local fence event counter to
// distinguish between separate events. Then later when the event actually
// occurs you increment the drivers count.
//
// Since the fences are file descriptors, they can be easily sent to another
// process, which can wait for them to signal without needing to define some
// other IPC mechanism to communicate the event. If the fence is sent well in
// advance, there should be minimal latency too.
//
// Instances of this class cannot be copied, but can be moved.
class UniqueTimeline {
public:
    // Initializes the timeline, using the given initial_fence_couter value.
    explicit UniqueTimeline(unsigned initial_fence_counter);

    ~UniqueTimeline();

    // Returns true if it is possible to create timelines.
    static bool Supported();

    // Creates a fence fd using the current value of the fence counter.
    // A negative value is returned on error.
    UniqueFence CreateFence(const char* name);

    // Increments the counter used when creating fences
    void BumpFenceEventCounter() { fence_counter_ += 1; }

    // Increments the drivers version of the counter, signaling any fences in the
    // range.
    void BumpTimelineEventCounter();

private:
    void BumpTimelineEventCounter(unsigned);

    // The timeline file descriptor.
    UniqueFd fd_{-1};

    // The counter used when creating fences on the timeline.
    unsigned fence_counter_{0};

    // The effective count for the timeline. The kernel driver has the actual
    // value, we just track what it should be. If it ever becomes out of sync,
    // it could be a problem for releasing fences on destruction.
    unsigned timeline_counter_{0};
};

}  // namespace implementation
}  // namespace V1_1
}  // namespace evs
}  // namespace automotive
}  // namespace android

