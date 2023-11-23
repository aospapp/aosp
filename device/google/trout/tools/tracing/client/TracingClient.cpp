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

#include "TracingClient.h"
#include "perfetto_trace.pb.h"

#include <android-base/logging.h>

#include <fstream>
#include <string>

namespace android::tools::automotive::tracing {

// TODO: Using a secure channel.
static std::shared_ptr<::grpc::ChannelCredentials> getChannelCredentials() {
    return ::grpc::InsecureChannelCredentials();
}

TracingClient::TracingClient(const std::string& addr)
    : mServiceAddr(addr),
      mGrpcChannel(::grpc::CreateChannel(mServiceAddr, getChannelCredentials())),
      mGrpcStub(tracing_vm_proto::TracingServer::NewStub(mGrpcChannel)) {}

bool TracingClient::StartTracing(const std::string& host_config, uint64_t& session_num) {
    ::grpc::ClientContext context;
    tracing_vm_proto::StartTracingRequest request;
    if (host_config.empty()) {
        std::cerr << __func__ << ": missing HostConfigFile.\n";
        return false;
    }

    std::ifstream config_file_stream(host_config, std::fstream::binary);
    if (!config_file_stream) {
        std::cerr << __func__ << ": file not found " << host_config << std::endl;
        return false;
    }

    perfetto::protos::TraceConfig* trace_config = request.mutable_host_config();
    if (trace_config->ParseFromIstream(&config_file_stream)) {
        std::cerr << __func__ << ": faled to parse the host_config file " << std::endl;
        return false;
    }

    tracing_vm_proto::RequestStatus request_status;
    auto grpc_status = mGrpcStub->StartTracing(&context, request, &request_status);
    if (!grpc_status.ok()) {
        std::cerr << __func__ << ": failed due to grpc error: " << grpc_status.error_message()
                  << std::endl;
        return false;
    }
    if (!request_status.is_ok()) {
        std::cerr << __func__ << ": failed with error: " << request_status.error_str() << std::endl;
        return false;
    }

    std::cout << __func__ << ": succeed for session_id "
              << request_status.session_id().session_id();
    session_num = request_status.session_id().session_id();
    return true;
}

bool TracingClient::StopTracing(const uint64_t session_num) {
    ::grpc::ClientContext context;
    tracing_vm_proto::TracingSessionIdentifier session_id;
    tracing_vm_proto::RequestStatus request_status;
    session_id.set_session_id(session_num);
    auto grpc_status = mGrpcStub->StopTracing(&context, session_id, &request_status);
    if (!grpc_status.ok()) {
        std::cerr << __func__ << ": for session_id " << session_num
                  << " failed due to grpc error: " << grpc_status.error_message() << std::endl;
        return false;
    }
    if (!request_status.is_ok()) {
        std::cerr << __func__ << ": for session_id " << session_num
                  << " failed with error code: " << request_status.error_str();
        return false;
    }

    std::cout << __func__ << ": succeed for session_id " << session_num << std::endl;
    return true;
}

bool TracingClient::GetTracingFile(const uint64_t session_num, const std::string& file_path) {
    if (file_path.empty()) {
        std::cerr << __func__ << ": missing file_path.\n";
        return false;
    }
    std::ofstream file_stream(file_path, std::fstream::binary);

    if (!file_stream) {
        std::cerr << __func__ << ": for session_id " << session_num << " failed to open file "
                  << file_path << std::endl;
        return false;
    }

    ::grpc::ClientContext context;
    tracing_vm_proto::TracingSessionIdentifier session_id;
    session_id.set_session_id(session_num);
    auto grpc_reader = mGrpcStub->GetTracingFile(&context, session_id);
    tracing_vm_proto::TracingFileBuffer file_buffer;

    while (grpc_reader->Read(&file_buffer)) {
        const auto& write_buffer = file_buffer.buffer();
        file_stream.write(write_buffer.c_str(), write_buffer.size());
    }

    auto grpcStatus = grpc_reader->Finish();
    if (!grpcStatus.ok()) {
        std::cerr << __func__ << " failed for session_id " << session_num
                  << "due to grpc error:" << grpcStatus.error_message() << std::endl;
        return false;
    }

    return true;
}

}  // namespace android::tools::automotive::tracing
