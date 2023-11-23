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

#include "controller/chip.h"

#include "common.pb.h"
#include "hci/bluetooth_facade.h"
#include "model.pb.h"
#include "util/log.h"
#include "uwb/uwb_facade.h"
#include "wifi/wifi_facade.h"

namespace netsim {
namespace controller {

// Create the model protobuf

model::Chip Chip::Get() {
  model::Chip model;
  model.set_kind(kind);
  model.set_id(id);
  model.set_name(name);
  model.set_manufacturer(manufacturer);
  model.set_product_name(product_name);
  model.set_capture(capture);
  if (kind == common::ChipKind::BLUETOOTH) {
    auto bt = hci::facade::Get(facade_id);
    model.mutable_bt()->CopyFrom(bt);
  } else if (kind == common::ChipKind::WIFI) {
    auto radio = wifi::facade::Get(facade_id);
    model.mutable_wifi()->CopyFrom(radio);
  } else if (kind == common::ChipKind::UWB) {
    auto radio = uwb::facade::Get(facade_id);
    model.mutable_uwb()->CopyFrom(radio);
  } else {
    BtsLog("Chip::Model - unknown chip kind");
  }
  return model;
}

void Chip::Patch(const model::Chip &request) {
  BtsLog("Chip::Patch %d", id);

  if (request.capture() != model::State::UNKNOWN &&
      this->capture != request.capture()) {
    this->capture = request.capture();
    hci::facade::SetPacketCapture(this->facade_id,
                                  request.capture() == model::State::ON,
                                  this->device_name);
  }
  if (!request.manufacturer().empty()) {
    this->manufacturer = request.manufacturer();
  }
  if (!request.product_name().empty()) {
    this->product_name = request.product_name();
  }
  if (kind == common::ChipKind::BLUETOOTH) {
    if (request.has_bt()) {
      hci::facade::Patch(facade_id, request.bt());
    }
  } else if (kind == common::ChipKind::WIFI) {
    if (request.has_wifi()) {
      wifi::facade::Patch(facade_id, request.wifi());
    }
  } else if (kind == common::ChipKind::UWB) {
    if (request.has_uwb()) {
      uwb::facade::Patch(facade_id, request.uwb());
    }
  } else {
    BtsLog("Chip::Patch - unknown chip kind");
  }
}

void Chip::Remove() {
  BtsLog("Chip::Remove %d", id);
  if (kind == common::ChipKind::BLUETOOTH) {
    hci::facade::Remove(facade_id);
  } else if (kind == common::ChipKind::WIFI) {
    wifi::facade::Remove(facade_id);
  } else if (kind == common::ChipKind::UWB) {
    uwb::facade::Remove(facade_id);
  } else {
    BtsLog("Chip::Remove - unknown chip kind");
  }
}

void Chip::Reset() {
  BtsLog("Chip::Reset %d", id);
  // TODO RESET THE CHIP
  if (kind == common::ChipKind::BLUETOOTH) {
    hci::facade::Reset(facade_id);
  } else if (kind == common::ChipKind::WIFI) {
    wifi::facade::Reset(facade_id);
  } else if (kind == common::ChipKind::UWB) {
    uwb::facade::Reset(facade_id);
  } else {
    BtsLog("Chip::Reset - unknown chip kind");
  }
}

}  // namespace controller
}  // namespace netsim
