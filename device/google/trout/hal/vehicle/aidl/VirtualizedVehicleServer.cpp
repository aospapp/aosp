/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include "FakeVehicleHardware.h"
#include "GRPCVehicleProxyServer.h"
#include "vsockinfo.h"

#include <android-base/logging.h>
#include <cutils/properties.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>

#include <memory>

using ::android::hardware::automotive::utils::VsockConnectionInfo;
using ::android::hardware::automotive::vehicle::fake::FakeVehicleHardware;
using ::android::hardware::automotive::vehicle::virtualization::GrpcVehicleProxyServer;

int main(int argc, char* argv[]) {
    auto vsock = VsockConnectionInfo::fromRoPropertyStore(
            {
                    "ro.boot.vendor.vehiclehal.server.cid",
                    "ro.vendor.vehiclehal.server.cid",
            },
            {
                    "ro.boot.vendor.vehiclehal.server.port",
                    "ro.vendor.vehiclehal.server.port",
            });
    CHECK(vsock.has_value()) << "Cannot read VHAL server address.";
    // For now we do not know where does the connection come from.
    // If we do, change this to the expected client CID.
    vsock->cid = VMADDR_CID_ANY;
    LOG(INFO) << "VHAL Server is listening on " << vsock->str();

    auto fakeHardware = std::make_unique<FakeVehicleHardware>();
    auto proxyServer =
            std::make_unique<GrpcVehicleProxyServer>(vsock->str(), std::move(fakeHardware));

    proxyServer->Start().Wait();
    return 0;
}
