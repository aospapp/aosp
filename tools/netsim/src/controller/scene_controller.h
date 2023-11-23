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

#include <memory>
#include <optional>
#include <vector>

#include "common.pb.h"
#include "controller/device.h"
#include "model.pb.h"

namespace netsim {
namespace controller {

class SceneController {
 public:
  SceneController(const SceneController &) = delete;
  SceneController &operator=(const SceneController &) = delete;
  SceneController(SceneController &&) = delete;
  SceneController &operator=(SceneController &&) = delete;

  static SceneController &Singleton();

  // When a packet stream goes away the chip is removed. When there are no more
  // chips the device is remove.
  void RemoveChip(uint32_t device_id, uint32_t chip_id);

  std::tuple<uint32_t, uint32_t, uint32_t> AddChip(
      const std::string &guid, const std::string &device_name,
      common::ChipKind chip_kind, const std::string &chip_name = "",
      const std::string &manufacturer = "",
      const std::string &product_name = "");

  bool PatchDevice(const model::Device &);

  float GetDistance(uint32_t, uint32_t);

  model::Scene Get();

  void Reset();

  std::optional<std::chrono::seconds> GetShutdownTime();

  std::unordered_map<uint32_t, std::shared_ptr<Device>> devices_;

 protected:
  friend class SceneControllerTest;
  friend class FrontendServerTest;

  std::shared_ptr<Device> GetDevice(const std::string &guid,
                                    const std::string &name);
  std::shared_ptr<Device> MatchDevice(const std::string &query);

 private:
  SceneController() = default;  // Disallow instantiation outside of the class.

  void RemoveDevice(uint32_t device_id);

  std::mutex mutex_;
  std::optional<std::chrono::time_point<std::chrono::system_clock>>
      inactive_timestamp_{std::chrono::system_clock::now()};
};

}  // namespace controller
}  // namespace netsim
