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

#include "unique_timeline.h"

#include <errno.h>
#include <limits>
#include <string.h>
#include <sw_sync.h>

#include <android-base/logging.h>

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

UniqueTimeline::UniqueTimeline(unsigned offset)
      : fd_(sw_sync_timeline_create()), fence_counter_(offset) {
    if (!fd_) {
        LOG(FATAL) << "Failed to create a timeline.";
    }
}

UniqueTimeline::~UniqueTimeline() {
    // Force any fences waiting on the timeline to be released by incrementing
    // by the difference between the two counters. The sw_sync driver has
    // changed behavior several times, and no longer releases fences when the
    // timeline fd is closed. While at one point adding MAX_UINT worked (by
    // adding MAX_INT with two separate calls), even that stopped working.
    // (See b/35115489 for background)
    BumpTimelineEventCounter(fence_counter_ - timeline_counter_);
}

bool UniqueTimeline::Supported() {
    UniqueFd fd{sw_sync_timeline_create()};
    return !!fd;
}

UniqueFence UniqueTimeline::CreateFence(const char* name) {
    UniqueFence fence(sw_sync_fence_create(fd_.Get(), name, fence_counter_));
    if (!fence) {
        PLOG(FATAL) << "Cannot create fence";
    }
    return fence;
}

void UniqueTimeline::BumpTimelineEventCounter() {
    BumpTimelineEventCounter(1);
}

void UniqueTimeline::BumpTimelineEventCounter(unsigned count) {
    timeline_counter_ += count;
    int err = sw_sync_timeline_inc(fd_.Get(), count);
    if (err < 0) {
        PLOG(FATAL) << "Cannot bump timeline counter";
    }
}

}  // namespace implementation
}  // namespace V1_1
}  // namespace evs
}  // namespace automotive
}  // namespace android
