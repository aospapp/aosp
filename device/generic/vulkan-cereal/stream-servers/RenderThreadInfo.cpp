/*
* Copyright (C) 2011 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

#include "RenderThreadInfo.h"

#include "aemu/base/synchronization/Lock.h"

#include <unordered_map>
#include <unordered_set>

namespace gfxstream {

using android::base::AutoLock;
using android::base::Stream;
using android::base::Lock;

static thread_local RenderThreadInfo* s_threadInfoPtr;

struct RenderThreadRegistry {
    Lock lock;
    std::unordered_set<RenderThreadInfo*> threadInfos;
};

static RenderThreadRegistry sRegistry;

RenderThreadInfo::RenderThreadInfo() {
    s_threadInfoPtr = this;
    AutoLock lock(sRegistry.lock);
    sRegistry.threadInfos.insert(this);
}

RenderThreadInfo::~RenderThreadInfo() {
    s_threadInfoPtr = nullptr;
    AutoLock lock(sRegistry.lock);
    sRegistry.threadInfos.erase(this);
}

RenderThreadInfo* RenderThreadInfo::get() {
    return s_threadInfoPtr;
}

// Loop over all active render thread infos. Takes the global render thread info lock.
void RenderThreadInfo::forAllRenderThreadInfos(std::function<void(RenderThreadInfo*)> f) {
    AutoLock lock(sRegistry.lock);
    for (auto info: sRegistry.threadInfos) {
        f(info);
    }
}

void RenderThreadInfo::initGl() {
    m_glInfo.emplace();
}

void RenderThreadInfo::onSave(Stream* stream) {
    m_glInfo->onSave(stream);
}

bool RenderThreadInfo::onLoad(Stream* stream) {
    return m_glInfo->onLoad(stream);
}

void RenderThreadInfo::postLoadRefreshCurrentContextSurfacePtrs() {
    return m_glInfo->postLoadRefreshCurrentContextSurfacePtrs();
}

}  // namespace gfxstream
