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
#include <string_view>

#include "controller/chip.h"
#include "model.pb.h"

namespace netsim {
namespace controller {

class Device;

class Chip {
  friend Device;

 public:
  explicit Chip(uint32_t id, uint32_t facade_id, common::ChipKind kind,
                std::string name, std::string device_name,
                std::string manufacturer = "", std::string product_name = "")
      : id(id),
        facade_id(facade_id),
        kind(kind),
        name(std::move(name)),
        device_name(std::move(device_name)),
        manufacturer(std::move(manufacturer)),
        product_name(std::move(product_name)),
        capture(model::State::OFF){};

  ~Chip(){};

  /**
   * Patch processing for the chip. Validate and move state from the request
   * into the parent's model::Chip changing the ChipFacade as needed.
   */
  void Patch(const model::Chip &request);

  model::Chip Get();
  /**
   * Reset the state of the chip to defaults.
   */
  void Reset();

  /**
   * Remove resources own by the chip and remove it from the chip emulator.
   */
  void Remove();
  const uint32_t id;  // Global id.
  const uint32_t facade_id;

 protected:
  const common::ChipKind kind;
  const std::string name;
  const std::string device_name;
  // These are patchable
  std::string manufacturer;
  std::string product_name;
  model::State capture;
};

}  // namespace controller
}  // namespace netsim
