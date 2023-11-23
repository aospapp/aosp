// Copyright 2023 The Android Open Source Project
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

#include "wifi/wifi_facade.h"

#include "util/log.h"

namespace netsim::wifi {
namespace {
// To detect bugs of misuse of chip_id more efficiently.
const int kGlobalChipStartIndex = 2000;

class ChipInfo {
 public:
  uint32_t simulation_device;
  std::shared_ptr<model::Chip::Radio> model;

  ChipInfo(uint32_t simulation_device,
           std::shared_ptr<model::Chip::Radio> model)
      : simulation_device(simulation_device), model(std::move(model)) {}
};

std::unordered_map<uint32_t, std::shared_ptr<ChipInfo>> id_to_chip_info_;

bool ChangedState(model::State a, model::State b) {
  return (b != model::State::UNKNOWN && a != b);
}

void IncrTx(uint32_t id) {
  if (auto it = id_to_chip_info_.find(id); it != id_to_chip_info_.end()) {
    auto &model = it->second->model;
    model->set_tx_count(model->tx_count() + 1);
  }
}

void IncrRx(uint32_t id) {
  if (auto it = id_to_chip_info_.find(id); it != id_to_chip_info_.end()) {
    auto &model = it->second->model;
    model->set_rx_count(model->rx_count() + 1);
  }
}

}  // namespace

namespace facade {

void Reset(uint32_t id) {
  BtsLog("wifi::facade::Reset(%d)", id);
  if (auto it = id_to_chip_info_.find(id); it != id_to_chip_info_.end()) {
    auto chip_info = it->second;
    chip_info->model->set_state(model::State::ON);
    chip_info->model->set_tx_count(0);
    chip_info->model->set_rx_count(0);
  }
}
void Remove(uint32_t id) {
  BtsLog("wifi::facade::Remove(%d)", id);
  id_to_chip_info_.erase(id);
}

void Patch(uint32_t id, const model::Chip::Radio &request) {
  BtsLog("wifi::facade::Patch(%d)", id);
  auto it = id_to_chip_info_.find(id);
  if (it == id_to_chip_info_.end()) {
    BtsLog("Patch an unknown id %d", id);
    return;
  }
  auto &model = it->second->model;
  if (ChangedState(model->state(), request.state())) {
    model->set_state(request.state());
  }
}

model::Chip::Radio Get(uint32_t id) {
  BtsLog("wifi::facade::Get(%d)", id);
  model::Chip::Radio radio;
  if (auto it = id_to_chip_info_.find(id); it != id_to_chip_info_.end()) {
    radio.CopyFrom(*it->second->model);
  }
  return radio;
}

uint32_t Add(uint32_t simulation_device) {
  BtsLog("wifi::facade::Add(%d)", simulation_device);
  static uint32_t global_chip_id = kGlobalChipStartIndex;

  auto model = std::make_shared<model::Chip::Radio>();
  model->set_state(model::State::ON);
  id_to_chip_info_.emplace(
      global_chip_id, std::make_shared<ChipInfo>(simulation_device, model));

  return global_chip_id++;
}

void Start() { BtsLog("wifi::facade::Start()"); }
void Stop() { BtsLog("wifi::facade::Stop()"); }

}  // namespace facade

void HandleWifiRequest(uint32_t facade_id,
                       const std::shared_ptr<std::vector<uint8_t>> &packet) {
  BtsLog("netsim::wifi::HandleWifiRequest()");
  netsim::wifi::IncrTx(facade_id);

  // TODO: Broadcast the packet to other emulators.
  // TODO: Send the packet to the WiFi service.
}
}  // namespace netsim::wifi
