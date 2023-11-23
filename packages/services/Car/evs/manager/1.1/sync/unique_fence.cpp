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

#include "unique_fence.h"

#include <errno.h>
#include <cinttypes>
#include <cstring>
#include <memory>
#include <string>

#include <android-base/logging.h>
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wzero-length-array"
#endif  // __clang__
#include <sync/sync.h>
#ifdef __clang__
#pragma clang diagnostic pop
#endif  // __clang__
#include <utils/String8.h>


constexpr int kWarningTimeout = 2000;

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

namespace {

const char* GetStatusString(int status) {
    if (status == 0) {
        return "active";
    } else if (status == 1) {
        return "signaled";
    } else {
        return "error";
    }
}

}  // namespace

UniqueFence::UniqueFence() {}

UniqueFence::UniqueFence(int fd) : fd_(fd) {}

UniqueFence::UniqueFence(UniqueFence&& other) = default;
UniqueFence& UniqueFence::operator=(UniqueFence&& other) = default;

void UniqueFence::Reset() {
    fd_.Reset();
}

UniqueFence UniqueFence::Dup() const {
    return UniqueFence(fd_.GetUnowned());
}

int UniqueFence::Get() const {
    return fd_.Get();
}

int UniqueFence::GetUnowned() const {
    return fd_.GetUnowned();
}

UniqueFence::operator bool() const {
    return static_cast<bool>(fd_);
}

void UniqueFence::GetDebugStateDump(String8& result) const {
    constexpr int INDENT = 8;
    struct sync_file_info* finfo = sync_file_info(fd_.Get());
    if (finfo == nullptr) {
        result.append("no debug info available");
        return;
    }
    result.appendFormat("name: %s status: %d (%s)", finfo->name, finfo->status,
                        GetStatusString(finfo->status));

    struct sync_fence_info* pinfo = sync_get_fence_info(finfo);
    for (uint32_t i = 0; i < finfo->num_fences; i++) {
        result.appendFormat("\n%*spt %u driver: %s obj: %s: status: %d(%s) timestamp: %llu", INDENT,
                            "", i, pinfo[i].driver_name, pinfo[i].obj_name, pinfo[i].status,
                            GetStatusString(pinfo[i].status), pinfo[i].timestamp_ns);
    }
    sync_file_info_free(finfo);
}

int UniqueFence::Wait(int wait_time_ms) {
    if (wait_time_ms == -1) {
        int err = sync_wait(fd_.Get(), kWarningTimeout);
        if (err >= 0 || errno != ETIME) return err;

        String8 dump;
        GetDebugStateDump(dump);
        LOG(WARNING) << "Waited on fence " << fd_.Get()
                     << " for " << kWarningTimeout << " ms. " << dump.string();
    }
    return sync_wait(fd_.Get(), wait_time_ms);
}

UniqueFence UniqueFence::Merge(const char* name, const UniqueFence& fence1,
                               const UniqueFence& fence2) {
    UniqueFence merged_fence;
    if (fence1.fd_ || fence2.fd_) {
        if (fence1.fd_ && fence2.fd_) {
            merged_fence.fd_.Reset(sync_merge(name, fence1.fd_.Get(), fence2.fd_.Get()));
        } else if (fence1.fd_) {
            // We merge the fence with itself so that we always generate a fence with
            // a new name.
            merged_fence.fd_.Reset(sync_merge(name, fence1.fd_.Get(), fence1.fd_.Get()));
        } else if (fence2.fd_) {
            // We merge the fence with itself so that we always generate a fence with
            // a new name.
            merged_fence.fd_.Reset(sync_merge(name, fence2.fd_.Get(), fence2.fd_.Get()));
        }

        if (!merged_fence.fd_) {
            PLOG(ERROR) << "Failed to merge fences";
        }
    }
    return merged_fence;
}

}  // namespace implementation
}  // namespace V1_1
}  // namespace evs
}  // namespace automotive
}  // namespace android
