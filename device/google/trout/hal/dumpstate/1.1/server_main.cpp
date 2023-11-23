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

#include <getopt.h>

#include <iostream>
#include <string>

int main(int argc, char** argv) {
    std::string serverAddr;

    // unique values to identify the options
    constexpr int OPT_SERVER_ADDR = 1001;

    struct option longOptions[] = {
            {"server_addr", 1, 0, OPT_SERVER_ADDR},
            {},
    };

    int optValue;
    while ((optValue = getopt_long_only(argc, argv, ":", longOptions, 0)) != -1) {
        switch (optValue) {
            case OPT_SERVER_ADDR:
                serverAddr = optarg;
                break;
            default:
                // ignore other options
                break;
        }
    }

    if (serverAddr.empty()) {
        std::cerr << "Dumpstate server addreess is missing" << std::endl;
        return 1;
    } else {
        std::cerr << "Dumpstate server addreess: " << serverAddr << std::endl;
    }

    DumpstateGrpcServer server(serverAddr);
    server.Start();
    return 0;
}
