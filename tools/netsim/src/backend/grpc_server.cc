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

#include "backend/grpc_server.h"

#include <google/protobuf/util/json_util.h>
#include <stdlib.h>

#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>

#include "common.pb.h"
#include "controller/controller.h"
#include "google/protobuf/empty.pb.h"
#include "grpcpp/server_context.h"
#include "grpcpp/support/status.h"
#include "packet_hub/packet_hub.h"
#include "packet_streamer.grpc.pb.h"
#include "packet_streamer.pb.h"
#include "util/log.h"

namespace netsim {
namespace backend {
namespace {

using netsim::common::ChipKind;

using Stream =
    ::grpc::ServerReaderWriter<packet::PacketResponse, packet::PacketRequest>;

using netsim::startup::Chip;

// Mapping from <chip kind, facade id> to streams.
std::unordered_map<std::string, Stream *> facade_to_stream;

std::string ChipFacade(ChipKind chip_kind, uint32_t facade_id) {
  return std::to_string(chip_kind) + "/" + std::to_string(facade_id);
}

// Service handles the gRPC StreamPackets requests.

class ServiceImpl final : public packet::PacketStreamer::Service {
 public:
  ::grpc::Status StreamPackets(::grpc::ServerContext *context,
                               Stream *stream) override {
    // Now connected to a peer issuing a bi-directional streaming grpc
    auto peer = context->peer();
    BtsLog("grpc_server new packet_stream for peer %s", peer.c_str());

    packet::PacketRequest request;

    // First packet must have initial_info describing the peer
    bool success = stream->Read(&request);
    if (!success || !request.has_initial_info()) {
      BtsLog("ServiceImpl no initial information or stream closed");
      return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT,
                            "Missing initial_info in first packet.");
    }

    auto device_name = request.initial_info().name();
    auto chip_kind = request.initial_info().chip().kind();
    // multiple chips of the same chip_kind for a device have a name
    auto chip_name = request.initial_info().chip().id();
    auto manufacturer = request.initial_info().chip().manufacturer();
    auto product_name = request.initial_info().chip().product_name();
    // Add a new chip to the device
    auto [device_id, chip_id, facade_id] = scene_controller::AddChip(
        peer, device_name, chip_kind, chip_name, manufacturer, product_name);

    BtsLog("grpc_server: adding chip %d with facade %d to %s", chip_id,
           facade_id, device_name.c_str());
    // connect packet responses from chip facade to the peer
    facade_to_stream[ChipFacade(chip_kind, facade_id)] = stream;
    this->ProcessRequests(stream, device_id, chip_kind, facade_id);

    // no longer able to send responses to peer
    facade_to_stream.erase(ChipFacade(chip_kind, facade_id));

    // Remove the chip from the device
    scene_controller::RemoveChip(device_id, chip_id);

    BtsLog("grpc_server: removing chip %d from %s", chip_id,
           device_name.c_str());

    return ::grpc::Status::OK;
  }

  // Convert a protobuf bytes field into shared_ptr<<vec<uint8_t>>.
  //
  // Release ownership of the bytes field and convert it to a vector using move
  // iterators. No copy when called with a mutable reference.
  std::shared_ptr<std::vector<uint8_t>> ToSharedVec(std::string *bytes_field) {
    return std::make_shared<std::vector<uint8_t>>(
        std::make_move_iterator(bytes_field->begin()),
        std::make_move_iterator(bytes_field->end()));
  }

  // Process requests in a loop forwarding packets to the packet_hub and
  // returning when the channel is closed.
  void ProcessRequests(Stream *stream, uint32_t device_id,
                       common::ChipKind chip_kind, uint32_t facade_id) {
    packet::PacketRequest request;
    while (true) {
      if (!stream->Read(&request)) {
        BtsLog("grpc_server: reading stopped for %d", facade_id);
        break;
      }
      // All kinds possible (bt, uwb, wifi), but each rpc only streames one.
      if (chip_kind == common::ChipKind::BLUETOOTH) {
        if (!request.has_hci_packet()) {
          BtsLog("grpc_server: unknown packet type from %d", facade_id);
          continue;
        }
        auto packet_type = request.hci_packet().packet_type();
        auto packet =
            ToSharedVec(request.mutable_hci_packet()->mutable_packet());
        packet_hub::HandleRequest(chip_kind, facade_id, *packet, packet_type);
      } else if (chip_kind == common::ChipKind::WIFI) {
        if (!request.has_packet()) {
          BtsLog("grpc_server: unknown packet type from %d", facade_id);
          continue;
        }
        auto packet = ToSharedVec(request.mutable_packet());
        packet_hub::HandleRequest(chip_kind, facade_id,

                                  *packet,
                                  packet::HCIPacket::HCI_PACKET_UNSPECIFIED);
      } else {
        // TODO: add UWB here
        BtsLog("grpc_server: unknown chip kind");
      }
    }
  }
};
}  // namespace

// handle_response is called by packet_hub to forward a response to the gRPC
// stream associated with chip_kind and facade_id.
//
// When writing, the packet is copied because is borrowed from a shared_ptr and
// grpc++ doesn't know about smart pointers.
void HandleResponse(ChipKind kind, uint32_t facade_id,
                    const std::vector<uint8_t> &packet,
                    packet::HCIPacket_PacketType packet_type) {
  auto stream = facade_to_stream[ChipFacade(kind, facade_id)];
  if (stream) {
    // TODO: lock or caller here because gRPC does not allow overlapping writes.
    packet::PacketResponse response;
    // Copies the borrowed packet for output
    auto str_packet = std::string(packet.begin(), packet.end());
    if (kind == ChipKind::BLUETOOTH) {
      response.mutable_hci_packet()->set_packet_type(packet_type);
      response.mutable_hci_packet()->set_packet(str_packet);
    } else {
      response.set_packet(str_packet);
    }
    if (!stream->Write(response)) {
      BtsLog("grpc_server: write failed %d", facade_id);
    }
  } else {
    BtsLog("grpc_server: no stream for %d", facade_id);
  }
}

}  // namespace backend

std::unique_ptr<packet::PacketStreamer::Service> GetBackendService() {
  return std::make_unique<backend::ServiceImpl>();
}
}  // namespace netsim
