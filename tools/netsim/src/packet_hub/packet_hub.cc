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

#include "packet_hub/packet_hub.h"

#include "backend/backend_packet_hub.h"
#include "common.pb.h"
#include "hci/hci_packet_hub.h"
#include "hci_packet.pb.h"
#include "netsim-cxx/src/lib.rs.h"
#include "wifi/wifi_packet_hub.h"

namespace netsim {
namespace packet_hub {

using netsim::common::ChipKind;

// forward from transport to facade via packet_hub
void HandleRequest(ChipKind kind, uint32_t facade_id,
                   const std::vector<uint8_t> &packet,
                   packet::HCIPacket_PacketType packet_type) {
  // Copied
  auto shared_packet = std::make_shared<std::vector<uint8_t>>(packet);
  if (kind == ChipKind::BLUETOOTH) {
    netsim::hci::handle_bt_request(facade_id, packet_type, shared_packet);
  } else if (kind == ChipKind::WIFI) {
    netsim::wifi::HandleWifiRequest(facade_id, shared_packet);
  }
  netsim::pcap::HandleRequest(kind, facade_id, packet, packet_type);
}

void HandleRequestCxx(uint32_t kind, uint32_t facade_id,
                      const rust::Vec<uint8_t> &packet, uint8_t packet_type) {
  std::vector<uint8_t> buffer(packet.begin(), packet.end());
  HandleRequest(static_cast<ChipKind>(kind), facade_id, buffer,
                static_cast<packet::HCIPacket_PacketType>(packet_type));
}

// forward from facade to transport via packet_hub
void HandleBtResponse(uint32_t facade_id,
                      packet::HCIPacket_PacketType packet_type,
                      const std::shared_ptr<std::vector<uint8_t>> &packet) {
  netsim::backend::HandleResponse(ChipKind::BLUETOOTH, facade_id, *packet,
                                  packet_type);
  netsim::fd::HandleResponse(ChipKind::BLUETOOTH, facade_id, *packet,
                             packet_type);
  netsim::pcap::HandleResponse(ChipKind::BLUETOOTH, facade_id, *packet,
                               packet_type);
}

// forward from facade to transport via packet_hub
void HandleWifiResponse(uint32_t facade_id,
                        const std::shared_ptr<std::vector<uint8_t>> &packet) {
  netsim::backend::HandleResponse(ChipKind::WIFI, facade_id, *packet,
                                  packet::HCIPacket::HCI_PACKET_UNSPECIFIED);
  netsim::fd::HandleResponse(ChipKind::WIFI, facade_id, *packet,
                             packet::HCIPacket::HCI_PACKET_UNSPECIFIED);
  netsim::pcap::HandleResponse(ChipKind::WIFI, facade_id, *packet,
                               packet::HCIPacket::HCI_PACKET_UNSPECIFIED);
}

}  // namespace packet_hub
}  // namespace netsim
