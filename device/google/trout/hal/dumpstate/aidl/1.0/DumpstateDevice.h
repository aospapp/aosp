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
#pragma once

#include <aidl/android/hardware/dumpstate/BnDumpstateDevice.h>
#include <aidl/android/hardware/dumpstate/IDumpstateDevice.h>
#include <android/binder_status.h>

#include <automotive/filesystem>
#include <functional>

#include <grpc++/grpc++.h>

#include "DumpstateServer.grpc.pb.h"
#include "DumpstateServer.pb.h"

namespace aidl::android::hardware::dumpstate::implementation {

namespace fs = ::android::hardware::automotive::filesystem;

class DumpstateDevice : public BnDumpstateDevice {
  public:
    explicit DumpstateDevice(const std::string& addr);

    ::ndk::ScopedAStatus dumpstateBoard(const std::vector<::ndk::ScopedFileDescriptor>& in_fds,
                                        IDumpstateDevice::DumpstateMode in_mode,
                                        int64_t in_timeoutMillis) override;

    ::ndk::ScopedAStatus getVerboseLoggingEnabled(bool* _aidl_return) override;
    ::ndk::ScopedAStatus setVerboseLoggingEnabled(bool in_enable) override;

    bool isHealthy();

  private:
    bool dumpRemoteLogs(::grpc::ClientReaderInterface<dumpstate_proto::DumpstateBuffer>* reader,
                        const fs::path& dumpPath);
    bool dumpString(const std::string& text, const fs::path& dumpPath);

    bool dumpHelperSystem(int textFd, int binFd);

    std::vector<std::string> getAvailableServices();

    std::string mServiceAddr;
    std::shared_ptr<::grpc::Channel> mGrpcChannel;
    std::unique_ptr<dumpstate_proto::DumpstateServer::Stub> mGrpcStub;
};

}  // namespace aidl::android::hardware::dumpstate::implementation
