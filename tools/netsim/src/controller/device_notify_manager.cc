// Copyright 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "controller/device_notify_manager.h"

#include <climits>

namespace netsim::controller {

unsigned int DeviceNotifyManager::Register(std::function<void(void)> callback) {
  std::unique_lock<std::mutex> lock(mutex_);
  if (next_available_callback_id_ == UINT_MAX) next_available_callback_id_ = 0;
  ++next_available_callback_id_;
  registered_callbacks_[next_available_callback_id_] = std::move(callback);
  return next_available_callback_id_;
}

void DeviceNotifyManager::Unregister(unsigned int callback_id) {
  std::unique_lock<std::mutex> lock(mutex_);
  registered_callbacks_.erase(callback_id);
}

void DeviceNotifyManager::Notify() {
  std::unique_lock<std::mutex> lock(mutex_);
  for (auto &[callback_id, callback] : registered_callbacks_) callback();
}

unsigned int DeviceNotifyManager::next_available_callback_id_ = 0;

}  // namespace netsim::controller
