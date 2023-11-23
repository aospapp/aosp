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

#include <cstdint>
#include <memory>
#include <string_view>

#include "controller/chip.h"
#include "model.pb.h"

namespace netsim {
namespace controller {

class Chip;

class Device {
 public:
  const uint32_t id;
  const std::string guid;
  const std::string name;
  bool visible;
  model::Position position;
  model::Orientation orientation;

  Device(uint32_t id, const std::string &guid, const std::string &name)
      : id(id), guid(guid), name(name), visible(true) {}

  model::Device Get();
  void Patch(const model::Device &request);
  bool RemoveChip(uint32_t chip_id);
  std::pair<uint32_t, uint32_t> AddChip(common::ChipKind chip_kind,
                                        const std::string &chip_name,
                                        const std::string &manufacturer,
                                        const std::string &product_name);
  void Reset();
  void Remove();

  std::unordered_map<uint32_t, std::shared_ptr<Chip>> chips_;
};

}  // namespace controller
}  // namespace netsim
