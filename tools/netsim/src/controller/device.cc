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

#include "controller/device.h"

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "common.pb.h"
#include "hci/bluetooth_facade.h"
#include "model.pb.h"
#include "netsim-cxx/src/lib.rs.h"
#include "util/log.h"
#include "wifi/wifi_facade.h"

namespace netsim {
namespace controller {

namespace {
// To detect bugs of misuse of chip_id more efficiently.
const int kGlobalChipStartIndex = 1000;
}  // namespace

model::Device Device::Get() {
  model::Device model;
  model.set_id(id);
  model.set_name(name);
  model.set_visible(visible);
  model.mutable_position()->CopyFrom(position);
  model.mutable_orientation()->CopyFrom(orientation);

  // populate chips models
  for (auto &[_, chip] : chips_) {
    model.add_chips()->CopyFrom(chip->Get());
  }
  return model;
}

void Device::Patch(const model::Device &request) {
  if (this->visible != request.visible()) {
    this->visible = request.visible();
  }
  if (request.has_position()) {
    this->position.CopyFrom(request.position());
  }
  if (request.has_orientation()) {
    this->orientation.CopyFrom(request.orientation());
  }
  for (const auto &request_chip_model : request.chips()) {
    // TODO: use request_chip_model.kind()
    auto request_chip_kind =
        request_chip_model.kind() != common::ChipKind::UNSPECIFIED
            ? request_chip_model.kind()
            : common::ChipKind::BLUETOOTH;
    const auto &request_chip_name = request_chip_model.name();
    BtsLog("request kind:%d, name:%s", request_chip_kind,
           request_chip_name.c_str());
    for (auto &[_, chip] : chips_) {
      BtsLog("chip kind:%d, name:%s", chip->kind, chip->name.c_str());
      if (chip->name == request_chip_name && chip->kind == request_chip_kind) {
        chip->Patch(request_chip_model);
      }
    }
  }
}

bool Device::RemoveChip(uint32_t chip_id) {
  if (chips_.find(chip_id) != chips_.end()) {
    BtsLog("Device::RemoveChip: removed %d", chip_id);
    chips_[chip_id]->Remove();
    chips_.erase(chip_id);
  } else {
    BtsLog("Device::RemoveChip: %d not found", chip_id);
  }
  return chips_.size() == 0;
}

std::pair<uint32_t, uint32_t> Device::AddChip(common::ChipKind chip_kind,
                                              const std::string &chip_name,
                                              const std::string &manufacturer,
                                              const std::string &product_name) {
  for (auto &[_, chip] : chips_) {
    auto chip_model = chip->Get();
    if (chip_model.kind() == chip_kind && chip_model.name() == chip_name) {
      BtsLog("Device::AddChip - duplicate, skipping.");
      return {-1, -1};
    }
  }
  static uint32_t global_chip_id = kGlobalChipStartIndex;
  auto chip_id = global_chip_id++;
  uint32_t facade_id;
  if (chip_kind == common::ChipKind::BLUETOOTH) {
    facade_id = hci::facade::Add(this->id);
    BtsLog("hci::facade::Add chip_id:%d, facade_id:%d", id, facade_id);
  } else if (chip_kind == common::ChipKind::WIFI) {
    facade_id = wifi::facade::Add(this->id);
  } else if (chip_kind == common::ChipKind::UWB) {
    facade_id = uwb::facade::Add(this->id);
  } else {
    BtsLog("Device::AdChip: unable to add chip");
    return {-1, -1};
  }
  auto chip = std::make_shared<Chip>(chip_id, facade_id, chip_kind, chip_name,
                                     this->name, manufacturer, product_name);
  chips_[chip_id] = std::move(chip);
  return {chip_id, facade_id};
}

void Device::Reset() {
  this->visible = true;
  this->position.Clear();
  this->orientation.Clear();
  for (auto &[_, chip] : chips_) {
    chip->Reset();
  }
}

void Device::Remove() {
  for (auto &[_, chip] : chips_) {
    chip->Remove();
  }
}

}  // namespace controller
}  // namespace netsim
