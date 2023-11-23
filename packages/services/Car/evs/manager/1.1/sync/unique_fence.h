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

#include <utils/String8.h>

#include "unique_fd.h"

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

// This is a simple C++ wrapper around the sw_sync interface. It is used to
// create and maintain sync fences created from a timeline.
class UniqueFence {
public:
    UniqueFence();
    explicit UniqueFence(int fd);

    UniqueFence(UniqueFence&&);
    UniqueFence& operator=(UniqueFence&&);

    // Destroy the current fence.
    void Reset();

    // Duplicate the fence.
    UniqueFence Dup() const;

    // Gets the descriptor
    int Get() const;

    // Gets an unowned duplicate of the fence descriptor.
    int GetUnowned() const;

    // Returns true if the fence is set to a valid descriptor. False otherwise.
    explicit operator bool() const;

    // Waits on the fence for the indicated amount of time in milliseconds. The
    // default value of -1 means to wait forever.
    int Wait(int wait_time_ms = -1);

    // Gets a string containing debug information for the fence.
    void GetDebugStateDump(String8& result) const;

    // Creates a new fence that signals when both input fences are signaled. Note
    // that it is possible to merge multiple fences this way.
    static UniqueFence Merge(const char* name, const UniqueFence& fence1,
                             const UniqueFence& fence2);

private:
    // The fence file descriptor
    UniqueFd fd_;
};

}  // namespace implementation
}  // namespace V1_1
}  // namespace evs
}  // namespace automotive
}  // namespace android

