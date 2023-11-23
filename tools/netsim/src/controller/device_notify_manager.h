/*
 * Copyright 2022 The Android Open Source Project
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

#include <functional>
#include <mutex>
#include <unordered_map>

namespace netsim::controller {

class DeviceNotifyManager {
 public:
  DeviceNotifyManager(const DeviceNotifyManager &) = delete;
  DeviceNotifyManager &operator=(const DeviceNotifyManager &) = delete;
  DeviceNotifyManager(DeviceNotifyManager &&) = delete;
  DeviceNotifyManager &operator=(DeviceNotifyManager &&) = delete;

  static DeviceNotifyManager &Get() {
    static DeviceNotifyManager *kInstance = new DeviceNotifyManager();
    return *kInstance;
  }

  // Register a callback from an observer.
  unsigned int Register(std::function<void(void)> callback);

  // Unregister a callback from an observer.
  void Unregister(unsigned int callback_id);

  // Notify observers for device updates.
  void Notify();

 private:
  DeviceNotifyManager() =
      default;  // Disallow instantiation outside of the class.

  std::unordered_map<unsigned int, std::function<void(void)>>
      registered_callbacks_;
  std::mutex mutex_;
  static unsigned int next_available_callback_id_;
};

}  // namespace netsim::controller
