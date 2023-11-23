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

#include "backend/packet_streamer_client.h"

#include <chrono>
#include <cstddef>
#include <iostream>
#include <mutex>
#include <optional>
#include <thread>
#ifdef _WIN32
#include <Windows.h>
#else
#include <unistd.h>
#endif

#include "aemu/base/process/Command.h"
#include "android/base/system/System.h"
#include "grpcpp/channel.h"
#include "grpcpp/create_channel.h"
#include "grpcpp/security/credentials.h"
#include "util/log.h"
#include "util/os_utils.h"

namespace netsim::packet {
namespace {

const std::chrono::duration kConnectionDeadline = std::chrono::seconds(1);
std::string custom_packet_stream_endpoint = "";
std::shared_ptr<grpc::Channel> packet_stream_channel;
std::mutex channel_mutex;

std::shared_ptr<grpc::Channel> CreateGrpcChannel() {
  auto endpoint = custom_packet_stream_endpoint;
  if (endpoint.empty()) {
    auto port = netsim::osutils::GetServerAddress();
    if (!port.has_value()) return nullptr;
    endpoint = "localhost:" + port.value();
  }

  if (endpoint.empty()) return nullptr;
  BtsLog("Creating a Grpc channel to %s", endpoint.c_str());
  return grpc::CreateChannel(endpoint, grpc::InsecureChannelCredentials());
}

bool GrpcChannelReady(const std::shared_ptr<grpc::Channel> &channel) {
  if (channel) {
    auto deadline = std::chrono::system_clock::now() + kConnectionDeadline;
    return channel->WaitForConnected(deadline);
  }
  return false;
}

void RunNetsimd() {
  auto exe = android::base::System::get()->findBundledExecutable("netsimd");
  auto cmd = android::base::Command::create({exe, "-g"});

  auto netsimd = cmd.asDeamon().execute();
  if (netsimd) {
    BtsLog("Running netsimd as pid: %d.", netsimd->pid());
  }
}

}  // namespace

void SetPacketStreamEndpoint(const std::string &endpoint) {
  if (endpoint != "default") custom_packet_stream_endpoint = endpoint;
}

std::shared_ptr<grpc::Channel> GetChannel() {
  std::lock_guard<std::mutex> lock(channel_mutex);

  bool is_netsimd_started = false;
  for (int second : {1, 2, 4, 8}) {
    if (!packet_stream_channel) packet_stream_channel = CreateGrpcChannel();
    if (GrpcChannelReady(packet_stream_channel)) return packet_stream_channel;

    packet_stream_channel.reset();

    if (!is_netsimd_started && custom_packet_stream_endpoint.empty()) {
      BtsLog("Starting netsim.");
      RunNetsimd();
      is_netsimd_started = true;
    }
    BtsLog("Retry connecting to netsim in %d second.", second);
    std::this_thread::sleep_for(std::chrono::seconds(second));
  }

  BtsLog("Unable to get a packet stream channel.");
  return nullptr;
}

std::shared_ptr<grpc::Channel> CreateChannel(
    std::string _rootcanal_default_commands_file,
    std::string _rootcanal_controller_properties_file) {
  return GetChannel();
}

}  // namespace netsim::packet
