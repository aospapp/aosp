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

/**
 * packet_hub is a central point for packet transfer between HCI, UWB, WiFi
 * facades and gRPC and socket transports.
 *
 * It allows a single point for:
 * - statistics collection
 * - pcap trace management
 * - inspection/analysis (NYI)
 */

#pragma once

// Use gRPC HCI PacketType definitions so we don't expose Rootcanal's version
// outside of the Bluetooth Facade.
#include "common.pb.h"
#include "hci_packet.pb.h"
#include "rust/cxx.h"

namespace netsim {
namespace packet_hub {

/* Handle packet request/response for the Bluetooth Facade which may come over
   different transports. */

void HandleRequest(common::ChipKind kind, uint32_t facade_id,
                   const std::vector<uint8_t> &packet,
                   packet::HCIPacket_PacketType packet_type);

void HandleRequestCxx(uint32_t kind, uint32_t facade_id,
                      const rust::Vec<uint8_t> &packet, uint8_t packet_type);

void HandleBtResponse(uint32_t facade_id,
                      packet::HCIPacket_PacketType packet_type,
                      const std::shared_ptr<std::vector<uint8_t>> &packet);

void HandleWifiResponse(uint32_t facade_id,
                        const std::shared_ptr<std::vector<uint8_t>> &packet);

}  // namespace packet_hub
}  // namespace netsim
