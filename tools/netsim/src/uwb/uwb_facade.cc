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

#include "uwb/uwb_facade.h"

#include "model.pb.h"
#include "netsim-cxx/src/lib.rs.h"
#include "rust/cxx.h"
#include "util/log.h"

namespace netsim::uwb::facade {

void Patch(uint32_t id, const model::Chip::Radio &request) {
  std::vector<uint8_t> message_vec(request.ByteSizeLong());
  request.SerializeToArray(message_vec.data(), message_vec.size());
  rust::Slice<const uint8_t> message_rust_slice(message_vec.data(),
                                                message_vec.size());
  uwb::facade::PatchCxx(id, message_rust_slice);
}

model::Chip::Radio Get(uint32_t id) {
  model::Chip::Radio radio;
  auto radio_byte_vec = uwb::facade::GetCxx(id);
  radio.ParseFromArray(radio_byte_vec.data(), radio_byte_vec.size());
  return radio;
}

void HandleUwbRequest(uint32_t facade_id,
                      const std::shared_ptr<std::vector<uint8_t>> &packet) {
  BtsLog("netsim::uwb::HandleUwbRequest()");
  rust::Slice<const uint8_t> packet_rust_slice;
  rust::Slice<const uint8_t> message_rust_slice(packet_rust_slice.data(),
                                                packet_rust_slice.size());
  HandleUwbRequestCxx(facade_id, packet_rust_slice);
}

}  // namespace netsim::uwb::facade