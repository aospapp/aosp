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
#include "vsockinfo.h"

#include <iostream>
#include <string>

using ::android::hardware::automotive::utils::VsockConnectionInfo;
using ::android::tools::automotive::tracing::TracingServerImpl;

// This is used for testing purpose
static const std::string TRACING_SERVICE_ADDR = "vsock:1:50051";

int main(int argc, char* argv[]) {
    std::string server_addr;
    const auto si = VsockConnectionInfo::fromRoPropertyStore(
            {
                    "ro.boot.vendor.tracing.server.cid",
                    "ro.vendor.tracing.server.cid",
            },
            {
                    "ro.boot.vendor.tracing.server.port",
                    "ro.vendor.tracing.server.port",
            });

    if (!si) {
        std::cout << "Failed to get server connection cid/port from property file."
                  << "The default address for testing purpose will be used." << std::endl;
        server_addr = TRACING_SERVICE_ADDR;
    } else {
        server_addr = si->str();
    }

    TracingServerImpl server(server_addr);
    server.Start();
    return 0;
}
