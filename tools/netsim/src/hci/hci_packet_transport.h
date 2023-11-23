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

#include <memory>

#include "hci_packet.pb.h"
#include "model/hci/hci_transport.h"    // for HciTransport
#include "model/setup/async_manager.h"  // for AsyncManager
#include "model/setup/phy_device.h"     // for Identifier

namespace netsim {
namespace hci {
using rootcanal::CloseCallback;
using rootcanal::PacketCallback;

/**
 * @class HciPacketTransport
 *
 * Connects Rootcanal's HciTransport to the packet_hub.
 *
 */
class HciPacketTransport : public rootcanal::HciTransport {
 public:
  HciPacketTransport(std::shared_ptr<rootcanal::AsyncManager>);
  ~HciPacketTransport() = default;

  static void Add(rootcanal::PhyDevice::Identifier id,
                  const std::shared_ptr<HciPacketTransport> &transport);

  /**
   * @brief Constructor for HciPacketTransport class.
   *
   * Moves HCI packets between packet_hub and rootcanal HciTransport
   */
  void Connect(rootcanal::PhyDevice::Identifier device_id);

  void SendEvent(const std::vector<uint8_t> &packet) override;

  void SendAcl(const std::vector<uint8_t> &packet) override;

  void SendSco(const std::vector<uint8_t> &packet) override;

  void SendIso(const std::vector<uint8_t> &packet) override;

  void RegisterCallbacks(PacketCallback command_callback,
                         PacketCallback acl_callback,
                         PacketCallback sco_callback,
                         PacketCallback iso_callback,
                         CloseCallback close_callback) override;

  void Tick() override;

  void Close() override;

  void Request(packet::HCIPacket_PacketType packet_type,
               const std::shared_ptr<std::vector<uint8_t>> &packet);

 private:
  void Response(packet::HCIPacket_PacketType packet_type,
                const std::vector<uint8_t> &packet);
  rootcanal::PacketCallback PacketTypeCallback(
      packet::HCIPacket_PacketType packet_type);

  rootcanal::PacketCallback mAclCallback;
  rootcanal::PacketCallback mCommandCallback;
  rootcanal::PacketCallback mScoCallback;
  rootcanal::PacketCallback mIsoCallback;
  rootcanal::CloseCallback mCloseCallback;
  // Device ID is the same as Chip Id externally.
  std::optional<rootcanal::PhyDevice::Identifier> mDeviceId;
  std::shared_ptr<rootcanal::AsyncManager> mAsyncManager;
};

}  // namespace hci
}  // namespace netsim
