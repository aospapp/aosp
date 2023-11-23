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

#include <atomic>
#include <memory>
#include <string>

namespace android::tools::automotive::tracing {

class TracingServerImpl : public tracing_vm_proto::TracingServer::Service {
  public:
    TracingServerImpl(const std::string& addr);

    grpc::Status StartTracing(::grpc::ServerContext* context,
                              const tracing_vm_proto::StartTracingRequest* request,
                              tracing_vm_proto::RequestStatus* request_status);

    grpc::Status StopTracing(::grpc::ServerContext* context,
                             const tracing_vm_proto::TracingSessionIdentifier* session_id,
                             tracing_vm_proto::RequestStatus* request_status);

    grpc::Status GetTracingFile(::grpc::ServerContext* context,
                                const tracing_vm_proto::TracingSessionIdentifier* session_id,
                                ::grpc::ServerWriter<tracing_vm_proto::TracingFileBuffer>* stream);
    void Start();

  private:
    std::atomic_uint64_t mSessionId = 1;
    std::string mServiceAddr;
    std::unique_ptr<::grpc::Server> mGrpcServer;
};

}  // namespace android::tools::automotive::tracing
