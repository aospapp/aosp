/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "DumpstateGrpcServer.h"

#include <iostream>
#include <unordered_set>

#include <grpc++/grpc++.h>

// clang-format off
static const std::unordered_set<std::string> kAvailableServices{
        "coqos-virtio-blk",
        "coqos-virtio-net",
        "coqos-virtio-video",
        "coqos-virtio-console",
        "coqos-virtio-rng",
        "coqos-virtio-vsock",
        "coqos-virtio-gpu-virgl",
        "coqos-virtio-scmi",
        "coqos-virtio-input",
        "coqos-virtio-snd",
        "dumpstate_grpc_server",
        "systemd",
        "vehicle_hal_grpc_server",
};
// clang-format on

static std::shared_ptr<::grpc::ServerCredentials> getServerCredentials() {
    // TODO(chenhaosjtuacm): get secured credentials here
    return ::grpc::InsecureServerCredentials();
}

grpc::Status DumpstateGrpcServer::GetSystemLogs(
        ::grpc::ServerContext*, const ::google::protobuf::Empty*,
        ::grpc::ServerWriter<dumpstate_proto::DumpstateBuffer>* stream) {
    return GetCommandOutput("/bin/dmesg -kuPT", stream);
}

grpc::Status DumpstateGrpcServer::GetAvailableServices(
        ::grpc::ServerContext*, const ::google::protobuf::Empty*,
        dumpstate_proto::ServiceNameList* serviceList) {
    static const dumpstate_proto::ServiceNameList kProtoAvailableServices = []() {
        dumpstate_proto::ServiceNameList serviceNameList;
        for (auto& serviceName : kAvailableServices) {
            serviceNameList.add_service_names(serviceName);
        }
        return serviceNameList;
    }();

    *serviceList = kProtoAvailableServices;
    return ::grpc::Status::OK;
}

grpc::Status DumpstateGrpcServer::GetServiceLogs(
        ::grpc::ServerContext*, const dumpstate_proto::ServiceLogRequest* request,
        ::grpc::ServerWriter<dumpstate_proto::DumpstateBuffer>* stream) {
    const auto& serviceName = request->service_name();
    if (serviceName.empty()) {
        return ::grpc::Status::OK;
    }
    if (kAvailableServices.find(serviceName) == kAvailableServices.end()) {
        return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT,
                              std::string("Bad service name: ") + serviceName);
    }
    return GetCommandOutput(std::string("/bin/journalctl --no-pager -t ") + serviceName, stream);
}

grpc::Status DumpstateGrpcServer::GetCommandOutput(
        const std::string& command,
        ::grpc::ServerWriter<dumpstate_proto::DumpstateBuffer>* stream) {
    int commandExitStatus = 0;
    auto pipeStreamDeleter = [&commandExitStatus](std::FILE* fp) {
        commandExitStatus = pclose(fp);
    };
    std::unique_ptr<std::FILE, decltype(pipeStreamDeleter)> pipeStream(popen(command.c_str(), "r"),
                                                                       pipeStreamDeleter);

    if (!pipeStream) {
        return ::grpc::Status(::grpc::StatusCode::INTERNAL,
                              std::string("Failed to execute ") + command + ", " + strerror(errno));
    }

    std::array<char, 65536> buffer;
    while (!std::feof(pipeStream.get())) {
        auto readLen = fread(buffer.data(), 1, buffer.size(), pipeStream.get());
        dumpstate_proto::DumpstateBuffer dumpstateBuffer;
        dumpstateBuffer.set_buffer(buffer.data(), readLen);
        stream->Write(dumpstateBuffer);
    }

    pipeStream.reset();

    if (commandExitStatus == 0) {
        return ::grpc::Status::OK;
    } else if (commandExitStatus < 0) {
        return ::grpc::Status(
                ::grpc::StatusCode::INTERNAL,
                std::string("Failed when pclose ") + command + ", " + strerror(errno));
    } else {
        return ::grpc::Status(::grpc::StatusCode::INTERNAL,
                              std::string("Error when executing ") + command +
                                      ", exit code: " + std::to_string(commandExitStatus));
    }
}

void DumpstateGrpcServer::Start() {
    ::grpc::ServerBuilder builder;
    builder.RegisterService(this);
    builder.AddListeningPort(mServiceAddr, getServerCredentials());
    std::unique_ptr<::grpc::Server> server(builder.BuildAndStart());

    if (!server) {
        std::cerr << __func__ << ": failed to create the GRPC server, "
                  << "please make sure the configuration and permissions are correct" << std::endl;
        std::abort();
    }

    server->Wait();
}
