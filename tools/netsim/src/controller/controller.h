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

#pragma once

#include <chrono>
#include <optional>
#include <string>

#include "common.pb.h"
#include "rust/cxx.h"

namespace netsim::scene_controller {

const unsigned int HTTP_STATUS_OK = 200;
const unsigned int HTTP_STATUS_BAD_REQUEST = 400;

unsigned int PatchDevice(const std::string &request, std::string &response,
                         std::string &error_message);

unsigned int GetDevices(const std::string &request, std::string &response,
                        std::string &error_message);

bool GetDevicesBytes(rust::Vec<::rust::u8> &vec);

int GetFacadeId(int chip_id);

void RemoveChip(uint32_t device_id, uint32_t chip_id);

/// The C++ definition of AddChip response interface for CXX.
class AddChipResult {
 public:
  uint32_t device_id;
  uint32_t chip_id;
  uint32_t facade_id;

  uint32_t get_device_id() const { return device_id; }
  uint32_t get_chip_id() const { return chip_id; }
  uint32_t get_facade_id() const { return facade_id; }

  AddChipResult(uint32_t device_id, uint32_t chip_id, uint32_t facade_id)
      : device_id(device_id), chip_id(chip_id), facade_id(facade_id){};
};

std::unique_ptr<AddChipResult> AddChipCxx(const std::string &guid,
                                          const std::string &device_name,
                                          uint32_t chip_kind,
                                          const std::string &chip_name,
                                          const std::string &manufacturer,
                                          const std::string &product_name);

std::tuple<uint32_t, uint32_t, uint32_t> AddChip(
    const std::string &guid, const std::string &device_name,
    common::ChipKind chip_kind, const std::string &chip_name = "",
    const std::string &manufacturer = "", const std::string &product_name = "");

float GetDistance(uint32_t, uint32_t);

std::optional<std::chrono::seconds> GetShutdownTime();

}  // namespace netsim::scene_controller
