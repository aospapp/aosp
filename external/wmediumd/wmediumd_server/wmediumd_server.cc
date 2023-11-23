/*
 *
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#include <android-base/strings.h>
#include <gflags/gflags.h>
#include <grpcpp/ext/proto_server_reflection_plugin.h>
#include <grpcpp/grpcpp.h>
#include <grpcpp/health_check_service_interface.h>
#include <sys/msg.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <iostream>
#include <memory>
#include <string>

#include "wmediumd.grpc.pb.h"
#include "wmediumd/api.h"
#include "wmediumd/grpc.h"

using google::protobuf::Empty;
using grpc::Server;
using grpc::ServerBuilder;
using grpc::ServerContext;
using grpc::Status;
using grpc::StatusCode;
using wmediumdserver::SetPositionRequest;
using wmediumdserver::WmediumdService;

#define MAC_ADDR_LEN 6
#define STR_MAC_ADDR_LEN 17

template <class T>
static void AppendBinaryRepresentation(std::string& buf, const T& data) {
  std::copy(reinterpret_cast<const char*>(&data),
            reinterpret_cast<const char*>(&data) + sizeof(T),
            std::back_inserter(buf));
}

bool IsValidMacAddr(const std::string& mac_address) {
  if (mac_address.size() != STR_MAC_ADDR_LEN) {
    return false;
  }

  if (mac_address[2] != ':' || mac_address[5] != ':' || mac_address[8] != ':' ||
      mac_address[11] != ':' || mac_address[14] != ':') {
    return false;
  }

  for (int i = 0; i < STR_MAC_ADDR_LEN; ++i) {
    if ((i - 2) % 3 == 0) continue;
    char c = mac_address[i];

    if (isupper(c)) {
      c = tolower(c);
    }

    if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
  }

  return true;
}

static std::array<uint8_t, 6> ParseMacAddress(const std::string& mac_address) {
  auto split_mac = android::base::Split(mac_address, ":");
  std::array<uint8_t, 6> mac;
  for (int i = 0; i < 6; i++) {
    char* end_ptr;
    mac[i] = (uint8_t)strtol(split_mac[i].c_str(), &end_ptr, 16);
  }

  return mac;
}

class WmediumdServiceImpl final : public WmediumdService::Service {
 public:
  WmediumdServiceImpl(int event_fd, int msq_id)
      : event_fd_(event_fd), msq_id_(msq_id) {}

  Status SetPosition(ServerContext* context, const SetPositionRequest* request,
                     Empty* reply) override {
    // Validate parameters
    if (!IsValidMacAddr(request->mac_address())) {
      return Status(StatusCode::INVALID_ARGUMENT, "Got invalid mac address");
    }
    auto mac = ParseMacAddress(request->mac_address());

    // Construct request data
    struct wmediumd_set_position data;
    memcpy(data.mac, &mac, sizeof(mac));
    data.x = request->x_pos();
    data.y = request->y_pos();

    // Fill data in the message queue
    struct wmediumd_grpc_message msg;
    msg.type = GRPC_REQUEST;
    memcpy(msg.data, &data, sizeof(data));
    msgsnd(msq_id_, &msg, sizeof(data), 0);

    // Throw an event to wmediumd
    uint64_t value = REQUEST_SET_POSITION;
    write(event_fd_, &value, sizeof(uint64_t));

    return Status::OK;
  }

 private:
  int event_fd_;
  int msq_id_;
};

void RunWmediumdServer(std::string grpc_uds_path, int event_fd, int msq_id) {
  std::string server_address("unix:" + grpc_uds_path);
  WmediumdServiceImpl service(event_fd, msq_id);

  grpc::EnableDefaultHealthCheckService(true);
  grpc::reflection::InitProtoReflectionServerBuilderPlugin();
  ServerBuilder builder;
  // Listen on the given address without any authentication mechanism.
  builder.AddListeningPort(server_address, grpc::InsecureServerCredentials());
  // Register "service" as the instance through which we'll communicate with
  // clients. In this case it corresponds to an *synchronous* service.
  builder.RegisterService(&service);
  // Finally assemble the server.
  std::unique_ptr<Server> server(builder.BuildAndStart());
  std::cout << "Server listening on " << server_address << std::endl;

  // Wait for the server to shutdown. Note that some other thread must be
  // responsible for shutting down the server for this call to ever return.
  server->Wait();
}
