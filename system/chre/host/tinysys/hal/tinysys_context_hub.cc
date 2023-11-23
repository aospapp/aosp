/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include "tinysys_context_hub.h"

namespace aidl::android::hardware::contexthub {
TinysysContextHub::TinysysContextHub() {
  mDeathRecipient = ndk::ScopedAIBinder_DeathRecipient(
      AIBinder_DeathRecipient_new(onClientDied));
  AIBinder_DeathRecipient_setOnUnlinked(
      mDeathRecipient.get(), [](void *cookie) {
        LOGI("Callback is unlinked. Releasing the death recipient cookie.");
        delete static_cast<HalDeathRecipientCookie *>(cookie);
      });
  mConnection = std::make_unique<TinysysChreConnection>(this);
  mHalClientManager = std::make_unique<HalClientManager>();
  mPreloadedNanoappLoader = std::make_unique<PreloadedNanoappLoader>(
      mConnection.get(), kPreloadedNanoappsConfigPath);
  if (mConnection->init()) {
    if (!kPreloadedNanoappsConfigPath.empty()) {
      mPreloadedNanoappLoader->loadPreloadedNanoapps();
    }
  }
}

void TinysysContextHub::onChreRestarted() {
  if (!kPreloadedNanoappsConfigPath.empty()) {
    mPreloadedNanoappLoader->loadPreloadedNanoapps();
  }
  MultiClientContextHubBase::onChreRestarted();
}
}  // namespace aidl::android::hardware::contexthub