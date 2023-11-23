/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "TracingVM.grpc.pb.h"
#include "TracingVM.pb.h"

#include <grpc++/grpc++.h>
#include <memory>
#include <string>

namespace android::tools::automotive::tracing {

class TracingClient {
  public:
    explicit TracingClient(const std::string& addr);

    bool StartTracing(const std::string& host_config, uint64_t& session_num);
    bool StopTracing(const uint64_t session_num);
    bool GetTracingFile(const uint64_t session_number, const std::string& file_path);

  private:
    std::string mServiceAddr;
    std::shared_ptr<::grpc::Channel> mGrpcChannel;
    std::unique_ptr<tracing_vm_proto::TracingServer::Stub> mGrpcStub;
};

}  // namespace android::tools::automotive::tracing
