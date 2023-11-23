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

#include "core/server.h"

#include <chrono>
#include <memory>
#include <optional>
#include <string>
#include <thread>

#ifdef NETSIM_ANDROID_EMULATOR
#include "backend/grpc_server.h"
#endif
#include "controller/controller.h"
#include "frontend/frontend_server.h"
#include "grpcpp/security/server_credentials.h"
#include "grpcpp/server.h"
#include "grpcpp/server_builder.h"
#include "netsim-cxx/src/lib.rs.h"
#include "util/filesystem.h"
#include "util/ini_file.h"
#include "util/log.h"
#include "util/os_utils.h"
#ifdef _WIN32
#include <Windows.h>
#else
#include <unistd.h>
#endif

namespace netsim::server {

namespace {
constexpr std::chrono::seconds InactivityCheckInterval(5);

std::unique_ptr<grpc::Server> RunGrpcServer(int netsim_grpc_port) {
  grpc::ServerBuilder builder;
  int selected_port;
  builder.AddListeningPort("0.0.0.0:" + std::to_string(netsim_grpc_port),
                           grpc::InsecureServerCredentials(), &selected_port);
  static auto frontend_service = GetFrontendService();
  builder.RegisterService(frontend_service.get());
#ifdef NETSIM_ANDROID_EMULATOR
  static auto backend_service = GetBackendService();
  builder.RegisterService(backend_service.get());
#endif
  std::unique_ptr<grpc::Server> server(builder.BuildAndStart());

  BtsLog("Grpc server listening on localhost: %s",
         std::to_string(selected_port).c_str());

  // Writes grpc port to ini file.
  auto filepath = osutils::GetNetsimIniFilepath();
  IniFile iniFile(filepath);
  iniFile.Read();
  iniFile.Set("grpc.port", std::to_string(selected_port));
  iniFile.Write();

  return std::move(server);
}
}  // namespace

void Run() {
  // Clear all pcap files in temp directory
  if (netsim::pcap::ClearPcapFiles()) {
    BtsLog("netsim generated pcap files in temp directory has been removed.");
  }

  // Environment variable "NETSIM_GRPC_PORT" is set in google3 forge. If set:
  // 1. Use the fixed port for grpc server.
  // 2. Don't start http server.
  auto netsim_grpc_port = std::stoi(osutils::GetEnv("NETSIM_GRPC_PORT", "0"));
  // Run frontend and backend grpc servers.
  auto grpc_server = RunGrpcServer(netsim_grpc_port);
  if (netsim_grpc_port == 0) {
    // Run frontend http server.
    std::thread(RunHttpServer).detach();
  }

  while (true) {
    std::this_thread::sleep_for(InactivityCheckInterval);
    if (auto seconds_to_shutdown = netsim::scene_controller::GetShutdownTime();
        seconds_to_shutdown.has_value() &&
        seconds_to_shutdown.value() < std::chrono::seconds(0)) {
      grpc_server->Shutdown();
      BtsLog("Netsim has been shutdown due to inactivity.");
      break;
    }
  }
}

}  // namespace netsim::server
