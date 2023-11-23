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

#include "TracingServerImpl.h"
#include "perfetto_trace.pb.h"

#include <android-base/logging.h>

#include <grpc++/grpc++.h>
#include <memory>
#include <string>

namespace android::tools::automotive::tracing {

static std::shared_ptr<::grpc::ServerCredentials> getServerCredentials() {
    return ::grpc::InsecureServerCredentials();
}

TracingServerImpl::TracingServerImpl(const std::string& addr) : mServiceAddr(addr) {}

void TracingServerImpl::Start() {
    LOG(INFO) << "mGrpcServer " << mGrpcServer << " starting at " << mServiceAddr << std::endl;
    if (mGrpcServer) {
        LOG(INFO) << __func__ << " GRPC Server is running.";
        return;
    }

    ::grpc::ServerBuilder builder;
    builder.AddListeningPort(mServiceAddr, getServerCredentials());
    builder.RegisterService(this);
    mGrpcServer = builder.BuildAndStart();

    if (!mGrpcServer) {
        LOG(ERROR) << __func__ << ": failed to create the GRPC server, "
                   << "please make sure the configuration and permissions are correct.";
        return;
    }
    LOG(INFO) << __func__ << " start server";
    mGrpcServer->Wait();
}

grpc::Status TracingServerImpl::StartTracing(::grpc::ServerContext* context,
                                             const tracing_vm_proto::StartTracingRequest* request,
                                             tracing_vm_proto::RequestStatus* request_status) {
    LOG(INFO) << "Received StartTracing rquest";
    request_status->mutable_session_id()->set_session_id(mSessionId.fetch_add(1));
    request_status->set_is_ok(true);
    return ::grpc::Status::OK;
}

grpc::Status TracingServerImpl::StopTracing(
        ::grpc::ServerContext* context,
        const tracing_vm_proto::TracingSessionIdentifier* session_id,
        tracing_vm_proto::RequestStatus* request_status) {
    LOG(INFO) << "Received StopTracing request";
    request_status->set_is_ok(true);
    return ::grpc::Status::OK;
}

grpc::Status TracingServerImpl::GetTracingFile(
        ::grpc::ServerContext* context,
        const tracing_vm_proto::TracingSessionIdentifier* session_id,
        ::grpc::ServerWriter<tracing_vm_proto::TracingFileBuffer>* stream) {
    LOG(INFO) << "Received GetTracingFile request";
    // TODO: b/222110159 maintain sessions information and validate session id.
    if (session_id->session_id() == 0) {
        LOG(ERROR) << "Received invalid session_id 0.";
        return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "");
    }

    tracing_vm_proto::TracingFileBuffer buffer;
    std::string data = "Test data";
    buffer.set_buffer(data);
    stream->Write(buffer);
    return ::grpc::Status::OK;
}

}  // namespace android::tools::automotive::tracing
