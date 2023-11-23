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

#include "hci/hci_packet_transport.h"

#include <limits>
#include <memory>
#include <optional>

#include "hci/hci_debug.h"
#include "hci_packet.pb.h"
#include "model/hci/hci_transport.h"
#include "packet_hub/packet_hub.h"
#include "util/log.h"

using netsim::packet::HCIPacket;

namespace netsim {
namespace hci {

std::unordered_map<uint32_t, std::shared_ptr<HciPacketTransport>>
    device_to_transport_;

/**
 * @class HciPacketTransport
 *
 * Connects hci packets between packet_hub and rootcanal.
 *
 */
HciPacketTransport::HciPacketTransport(
    std::shared_ptr<rootcanal::AsyncManager> async_manager)
    : mDeviceId(std::nullopt), mAsyncManager(std::move(async_manager)) {}

/**
 * @brief Connect the phy device to the transport
 *
 * @param - device_id identifier of the owning device
 */
void HciPacketTransport::Connect(rootcanal::PhyDevice::Identifier device_id) {
  assert(!mDeviceId.has_value());
  mDeviceId.emplace(device_id);
}

// Called by HCITransport (rootcanal)
void HciPacketTransport::SendEvent(const std::vector<uint8_t> &data) {
  this->Response(HCIPacket::EVENT, data);
}

// Called by HCITransport (rootcanal)
void HciPacketTransport::SendAcl(const std::vector<uint8_t> &data) {
  this->Response(HCIPacket::ACL, data);
}

// Called by HCITransport (rootcanal)
void HciPacketTransport::SendSco(const std::vector<uint8_t> &data) {
  this->Response(HCIPacket::SCO, data);
}

// Called by HCITransport (rootcanal)
void HciPacketTransport::SendIso(const std::vector<uint8_t> &data) {
  this->Response(HCIPacket::ISO, data);
}

// Called by HCITransport (rootcanal)
void HciPacketTransport::RegisterCallbacks(
    rootcanal::PacketCallback commandCallback,
    rootcanal::PacketCallback aclCallback,
    rootcanal::PacketCallback scoCallback,
    rootcanal::PacketCallback isoCallback,
    rootcanal::CloseCallback closeCallback) {
  BtsLog("hci_packet_transport: registered");
  mCommandCallback = commandCallback;
  mAclCallback = aclCallback;
  mScoCallback = scoCallback;
  mIsoCallback = isoCallback;
  mCloseCallback = closeCallback;
}

// Called by HCITransport (rootcanal)
void HciPacketTransport::Tick() {}

void HciPacketTransport::Request(
    packet::HCIPacket_PacketType packet_type,
    const std::shared_ptr<std::vector<uint8_t>> &packet) {
  auto packet_callback = PacketTypeCallback(packet_type);
  if (!packet_callback) {
    BtsLog("hci_transport: unknown packet_callback");
    return;
  }
  if (packet_type == HCIPacket::COMMAND) {
    auto cmd = HciCommandToString(packet->at(0), packet->at(1));
  }
  // Copy the packet bytes for rootcanal.
  mAsyncManager->Synchronize(
      [packet_callback, packet]() { packet_callback(packet); });
}

void HciPacketTransport::Add(
    rootcanal::PhyDevice::Identifier device_id,
    const std::shared_ptr<HciPacketTransport> &transport) {
  transport->Connect(device_id);
  device_to_transport_[device_id] = transport;
}

void HciPacketTransport::Close() {
  if (mDeviceId.has_value()) {
    device_to_transport_.erase(mDeviceId.value());
  }

  BtsLog("hci_packet_transport close from rootcanal");
  mDeviceId = std::nullopt;
}

// Send response to packet_hub.
// TODO: future optimization by having rootcanal send shared_ptr.
void HciPacketTransport::Response(packet::HCIPacket_PacketType packet_type,
                                  const std::vector<uint8_t> &packet) {
  if (!mDeviceId.has_value()) {
    BtsLog("hci_packet_transport: response with no device.");
    return;
  }
  auto shared_packet = std::make_shared<std::vector<uint8_t>>(packet);
  netsim::packet_hub::HandleBtResponse(mDeviceId.value(), packet_type,
                                       shared_packet);
}

rootcanal::PacketCallback HciPacketTransport::PacketTypeCallback(
    packet::HCIPacket_PacketType packet_type) {
  switch (packet_type) {
    case HCIPacket::COMMAND:
      assert(mCommandCallback);
      return mCommandCallback;
    case HCIPacket::ACL:
      return mAclCallback;
    case HCIPacket::SCO:
      return mScoCallback;
    case HCIPacket::ISO:
      return mIsoCallback;
    default:
      BtsLog("hci_transport Ignoring unknown packet.");
      return nullptr;
  }
}

// handle_request is the main entry for incoming packets called by
// netsim::packet_hub
//
// Transfer the request to the HciTransport to deliver to Rootcanal via the
// acl/sco/iso/command callback methods under synchronization.
void handle_bt_request(uint32_t facade_id,
                       packet::HCIPacket_PacketType packet_type,
                       const std::shared_ptr<std::vector<uint8_t>> &packet) {
  if (device_to_transport_.find(facade_id) != device_to_transport_.end() &&
      device_to_transport_[facade_id]) {
    auto transport = device_to_transport_[facade_id];
    transport->Request(packet_type, packet);
  } else {
    std::cout << "device_to_transport_ ids ";
    for (auto [k, _] : device_to_transport_) std::cout << k << " ";
    std::cout << std::endl;
    BtsLog(
        "hci_packet_transport: handle_request with no transport for device %d",
        facade_id);
  }
}

}  // namespace hci
}  // namespace netsim
