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

// Use gRPC HCI PacketType definitions so we don't expose Rootcanal's version
// outside of the Bluetooth Facade.
#include "common.pb.h"
#include "hci_packet.pb.h"

namespace netsim {
namespace backend {

using netsim::common::ChipKind;

/* Handle packet responses for the backend. */

void HandleResponse(ChipKind kind, uint32_t facade_id,
                    const std::vector<uint8_t> &packet,
                    /* optional */ packet::HCIPacket_PacketType packet_type);

}  // namespace backend
}  // namespace netsim
