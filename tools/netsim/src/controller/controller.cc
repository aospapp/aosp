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

#include "controller/controller.h"

#include <google/protobuf/empty.pb.h>
#include <google/protobuf/util/json_util.h>

#include <chrono>
#include <cstdint>
#include <optional>
#include <string>

#include "common.pb.h"
#include "controller/scene_controller.h"
#include "frontend.pb.h"

namespace netsim::scene_controller {

unsigned int PatchDevice(const std::string &request, std::string &response,
                         std::string &error_message) {
  frontend::PatchDeviceRequest request_proto;
  google::protobuf::util::JsonStringToMessage(request, &request_proto);
  google::protobuf::Empty response_proto;

  auto status = netsim::controller::SceneController::Singleton().PatchDevice(
      request_proto.device());
  if (!status) {
    error_message = "device_serial not found: " + request_proto.device().name();
    return HTTP_STATUS_BAD_REQUEST;
  }

  google::protobuf::util::MessageToJsonString(response_proto, &response);
  return HTTP_STATUS_OK;
}

unsigned int GetDevices(const std::string &request, std::string &response,
                        std::string &error_message) {
  frontend::GetDevicesResponse response_proto;
  auto scene = netsim::controller::SceneController::Singleton().Get();
  for (const auto &device : scene.devices())
    response_proto.add_devices()->CopyFrom(device);
  google::protobuf::util::MessageToJsonString(response_proto, &response);
  return HTTP_STATUS_OK;
}

bool GetDevicesBytes(rust::Vec<::rust::u8> &vec) {
  auto scene = netsim::controller::SceneController::Singleton().Get();
  std::vector<unsigned char> message_vec(scene.ByteSizeLong());
  auto status = scene.SerializeToArray(message_vec.data(), message_vec.size());
  if (!status) {
    return false;
  }
  vec.reserve(message_vec.size());
  std::copy(message_vec.begin(), message_vec.end(), std::back_inserter(vec));
  return true;
}

int GetFacadeId(int chip_id) {
  for (auto &[_, device] :
       netsim::controller::SceneController::Singleton().devices_) {
    for (const auto &[_, chip] : device->chips_) {
      if (chip->id == chip_id) {
        return chip->facade_id;
      }
    }
  }
  return -1;
}

void RemoveChip(uint32_t device_id, uint32_t chip_id) {
  netsim::controller::SceneController::Singleton().RemoveChip(device_id,
                                                              chip_id);
}

std::unique_ptr<AddChipResult> AddChipCxx(const std::string &guid,
                                          const std::string &device_name,
                                          uint32_t chip_kind,
                                          const std::string &chip_name,
                                          const std::string &manufacturer,
                                          const std::string &product_name) {
  auto [device_id, chip_id, facade_id] =
      scene_controller::AddChip(guid, device_name, (common::ChipKind)chip_kind,
                                chip_name, manufacturer, product_name);
  return std::make_unique<AddChipResult>(device_id, chip_id, facade_id);
}

std::tuple<uint32_t, uint32_t, uint32_t> AddChip(
    const std::string &guid, const std::string &device_name,
    common::ChipKind chip_kind, const std::string &chip_name,
    const std::string &manufacturer, const std::string &product_name) {
  return netsim::controller::SceneController::Singleton().AddChip(
      guid, device_name, chip_kind, chip_name, manufacturer, product_name);
}

float GetDistance(uint32_t device_id, uint32_t other_device_id) {
  return netsim::controller::SceneController::Singleton().GetDistance(
      device_id, other_device_id);
}

std::optional<std::chrono::seconds> GetShutdownTime() {
  return netsim::controller::SceneController::Singleton().GetShutdownTime();
}

}  // namespace netsim::scene_controller
